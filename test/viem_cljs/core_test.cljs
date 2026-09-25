(ns viem-cljs.core-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.core :as core]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Clients + option translation
;; ---------------------------------------------------------------------------

(deftest public-client-over-custom-stub
  (let [s (tu/stub {"eth_blockNumber" "0x10"})
        c (tu/public-client s {:polling-interval 100 :key "mine" :name "Mine"})
        info (core/client-info c)]
    (is (core/client? c))
    (is (not (core/client? {})))
    (is (= :mainnet (:chain info)))
    (is (= 1 (:chain-id info)))
    (is (= "Ethereum" (:chain-name info)))
    (is (= "custom" (:transport-type info)))
    (is (= "publicClient" (:type info)))
    (is (= "mine" (:key info)))
    (is (= "Mine" (:name info)))
    (is (= 100 (:polling-interval info)) ":polling-interval camelled")
    (is (nil? (:account info)))
    (is (core/chain-object? (core/client-chain c)))))

(deftest wallet-and-test-clients
  (let [s (tu/stub {})
        acct (core/mnemonic->account tu/dev-mnemonic)
        w (tu/wallet-client s acct)
        t (tu/test-client s)
        t2 (core/test-client {:mode :hardhat :transport {:custom (:request s)}})]
    (is (= {:address tu/dev-address-0 :type "local" :source "hd"}
           (select-keys (:account (core/client-info w)) [:address :type :source])))
    (is (= "walletClient" (:type (core/client-info w))))
    (is (core/account-object? (core/client-account w)))
    (is (= "anvil" (:mode (core/client-info t))) "test client defaults to anvil")
    (is (= "hardhat" (:mode (core/client-info t2))))
    (testing "json-rpc account from an address / map"
      (is (= {:address tu/weth-address :type "json-rpc"}
             (:account (core/client-info (tu/wallet-client s tu/weth-address)))))
      (is (= "json-rpc" (:type (:account (core/client-info (tu/wallet-client s {:address tu/weth-address})))))))))

(deftest transports-and-config-errors
  (testing "http without URL and without chain is rejected early"
    (let [e (tu/thrown #(core/public-client {:transport (core/http)}))]
      (is (= :viem-cljs/config-error (util/error-type e)))
      (is (= :url-required (:reason (ex-data e)))))
    (is (= :url-required (:reason (ex-data (tu/thrown #(core/public-client {:transport {:http nil}})))))))
  (testing "http falls back to the chain's default RPC when a chain is given"
    (let [c (core/public-client {:chain :mainnet :transport (core/http)})]
      (is (= "http" (:transport-type (core/client-info c))))))
  (testing "http with URL, no chain"
    (let [c (core/public-client {:transport (core/http "http://127.0.0.1:8545" {:timeout 1000 :retry-count 0})})]
      (is (= "http" (:transport-type (core/client-info c))))
      (is (nil? (:chain-id (core/client-info c))))))
  (testing "shorthand maps"
    (is (= "http" (:transport-type (core/client-info (core/client {:transport {:http "http://127.0.0.1:8545" :timeout 500}})))))
    (is (= "webSocket" (:transport-type (core/client-info (core/client {:transport {:websocket "ws://127.0.0.1:8545"}})))) "socket opens lazily")
    (is (= "webSocket" (:transport-type (core/client-info (core/client {:transport (core/web-socket "ws://127.0.0.1:8545" {:retry-count 0})}))))))
  (testing "fallback"
    (is (= "fallback" (:transport-type (core/client-info (core/client {:transport (core/fallback [(core/http "http://a") {:http "http://b"}])})))))
    (is (= :empty-fallback (:reason (ex-data (tu/thrown #(core/client {:transport (core/fallback [])})))))))
  (testing "custom"
    (is (= :bad-provider (:reason (ex-data (tu/thrown #(core/client {:transport (core/custom "nope")}))))))
    (is (= "custom" (:transport-type (core/client-info (core/client {:transport {:request (fn [_] "0x1")}}))))))
  (testing "missing / unknown transport"
    (is (= :missing-transport (:reason (ex-data (tu/thrown #(core/client {:chain :mainnet}))))))
    (is (= :bad-transport (:reason (ex-data (tu/thrown #(core/client {:transport {:magic 1}}))))))
    (is (= :unknown-chain (:reason (ex-data (tu/thrown #(core/client {:chain :narnia :transport (core/http "http://x")}))))))))

(deftest option-translation
  (let [o (core/opts->js {:block-tag :latest
                          :value "1"
                          :max-fee-per-gas 5
                          :strict? true
                          :emit-on-begin? false
                          :from-block :earliest
                          :to-block "0x10"
                          :abi tu/erc20-abi
                          :account tu/weth-address
                          :chain :mainnet
                          :args ["7" {:token-id 1}]
                          :calls [{:to tu/weth-address :value 3}]
                          :nil-opt nil})]
    (is (= "latest" (util/jsget o "blockTag")))
    (is (= (utils/big 1) (util/jsget o "value")) "quantity strings coerced")
    (is (= (utils/big 5) (util/jsget o "maxFeePerGas")) "quantity numbers coerced")
    (is (true? (util/jsget o "strict")) "? stripped")
    (is (false? (util/jsget o "emitOnBegin")))
    (is (= "earliest" (util/jsget o "fromBlock")) "tag keywords stay tags")
    (is (= (utils/big 16) (util/jsget o "toBlock")))
    (is (= 3 (count (util/jsget o "abi"))) "human-readable ABI parsed")
    (is (= "balanceOf" (util/jsget o "abi" 0 "name")))
    (is (= "json-rpc" (util/jsget o "account" "type")) "address -> json-rpc account")
    (is (= 1 (util/jsget o "chain" "id")) "chain keyword resolved")
    (is (= "7" (util/jsget o "args" 0)) "args untouched")
    (is (= 1 (util/jsget o "args" 1 "tokenId")) "struct args re-cased")
    (is (= (utils/big 3) (util/jsget o "calls" 0 "value")) "nested quantities")
    (is (nil? (util/jsget o "nilOpt")))
    (is (= 0 (count (js-keys (core/opts->js nil)))))))

;; ---------------------------------------------------------------------------
;; Chain registry primitives
;; ---------------------------------------------------------------------------

(deftest chain-registry-primitives
  (is (= 737 (count (core/chain-keywords))))
  (is (= 1 (util/jsget (core/chain-object :mainnet) "id")))
  (is (= 84532 (util/jsget (core/chain-object :base-sepolia) "id")) "kebab keyword")
  (is (= 84532 (util/jsget (core/chain-object "baseSepolia") "id")) "viem export name")
  (is (= 31337 (util/jsget (core/chain-object 31337) "id")) "by id")
  (is (= :anvil (core/chain-id->keyword 31337)) "first registry name wins")
  (is (= :mainnet (core/chain-id->keyword 1)))
  (is (nil? (core/chain-id->keyword 424242424)))
  (is (= :zora-sepolia (core/chain-id->keyword 999999999)) "big ids are real chains")
  (is (= "baseSepolia" (core/chain-keyword->name :base-sepolia)))
  (is (identical? (core/chain-object :mainnet) (core/chain-object (core/chain-object :mainnet))) "objects pass through")
  (is (nil? (core/chain-object nil)))
  (let [c (core/chain-object {:id 424242 :name "Test" :native-currency {:name "Ether" :symbol "ETH" :decimals 18}
                              :rpc-urls {:default {:http ["http://127.0.0.1:8545"]}}})]
    (is (core/chain-object? c))
    (is (= 424242 (:id (core/chain->map c))))
    (is (= ["http://127.0.0.1:8545"] (get-in (core/chain->map c) [:rpc-urls :default :http]))))
  (let [m (core/chain->map :optimism)]
    (is (= 10 (:id m)))
    (is (true? (:formatters? m)) "OP Stack chain has formatters")
    (is (not (contains? m :extend)))
    (is (not (contains? m :formatters)))))

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(deftest private-key-accounts
  (let [pk (core/generate-private-key)
        a (core/private-key->account pk)
        m (core/account->map a)]
    (is (re-matches #"0x[0-9a-f]{64}" pk))
    (is (core/account-object? a))
    (is (= (core/private-key->address pk) (:address m)) "deterministic address")
    (is (utils/address? (:address m)))
    (is (= "local" (:type m)))
    (is (= "privateKey" (:source m)))
    (is (str/starts-with? (:public-key m) "0x04"))
    (is (= [:address :type :source :public-key] (vec (keys m))) "no key material in the projection")
    (is (= (:address m) (core/public-key->address (:public-key m))))
    (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/private-key->account "0x1234")))))))

(deftest mnemonic-and-hd-accounts
  (let [a0 (core/mnemonic->account tu/dev-mnemonic)
        a1 (core/mnemonic->account tu/dev-mnemonic {:address-index 1})
        a1-path (core/mnemonic->account tu/dev-mnemonic {:path "m/44'/60'/0'/0/1"})
        hd (core/account-hd-key a0)
        root (core/hd-key-from-seed (utils/hex->bytes "0x000102030405060708090a0b0c0d0e0f"))
        r0 (core/hd-key->account root)
        r1 (core/hd-key->account root {:address-index 1})
        r0' (core/hd-key->account (core/hd-key-from-extended-key (util/jsget root "privateExtendedKey")))]
    (is (= tu/dev-address-0 (:address (core/account->map a0))))
    (is (= "hd" (:source (core/account->map a0))))
    (is (= tu/dev-address-1 (:address (core/account->map a1))))
    (is (= tu/dev-address-1 (:address (core/account->map a1-path))) "explicit :path")
    (is (some? hd) "the account's derived HDKey is exposed (opaque)")
    (is (utils/address? (:address (core/account->map r0))))
    (is (not= (:address (core/account->map r0)) (:address (core/account->map r1))))
    (is (= (:address (core/account->map r0)) (:address (core/account->map r0'))) "seed and xprv roots agree")
    (is (nil? (core/account-hd-key (core/address-account tu/weth-address))))
    (is (= 12 (count (str/split (core/generate-mnemonic) #" "))))
    (is (= 24 (count (str/split (core/generate-mnemonic :english 256) #" "))))
    (is (= 2048 (count (core/wordlist :english))))
    (is (= "abandon" (first (core/wordlist :english))))
    (is (= 10 (count core/wordlist-names)))
    (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/wordlist :klingon)))))
    (is (= :viem-cljs/viem-error (util/error-type (tu/thrown #(core/mnemonic->account "not a mnemonic" nil)))) "viem errors are wrapped")))

(deftest json-rpc-and-custom-accounts
  (async done
    (tu/async-test done
      (let [local (core/private-key->account (core/generate-private-key))
            address (:address (core/account->map local))
            custom (core/custom-account {:address address
                                         :sign-message (fn [{:keys [message]}] (core/sign-message local {:message message}))
                                         :sign-transaction (fn [tx] (core/sign-transaction local tx))
                                         :sign-typed-data (fn [td] (core/sign-typed-data local td))})
            jr (core/address-account tu/weth-address)]
        (is (= {:address tu/weth-address :type "json-rpc"} (core/account->map jr)))
        (is (= "json-rpc" (:type (core/account->map (core/parse-account tu/weth-address)))))
        (is (= "custom" (:source (core/account->map custom))))
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/custom-account {})))))
        (p/let [s1 (core/sign-message local {:message "hi"})
                s2 (core/sign-message custom {:message "hi"})
                ok (utils/verify-message {:address address :message "hi" :signature s1})
                raw (core/sign-message local {:message {:raw "0x68690a"}})
                td {:domain {:name "T" :version "1" :chain-id 1}
                    :types {:Mail [{:name "body" :type "string"}]}
                    :primary-type "Mail"
                    :message {:body "yo"}}
                s3 (core/sign-typed-data local td)
                signer (utils/recover-typed-data-address (assoc td :address address :signature s3))
                stx (core/sign-transaction local {:chain-id 1 :to tu/weth-address :value "1" :gas 21000
                                                  :max-fee-per-gas 10 :max-priority-fee-per-gas 1 :nonce 0 :type :eip1559})
                parsed (utils/parse-transaction stx)
                bad (tu/rejection (core/sign-message jr {:message "hi"}))]
          (is (= 132 (count s1)))
          (is (= s1 s2) "custom signer delegates")
          (is (true? ok))
          (is (= 132 (count raw)))
          (is (= address signer) "typed data signature recovers to the account")
          (is (str/starts-with? stx "0x02") "eip1559 envelope")
          (is (= "eip1559" (:type parsed)))
          (is (= (utils/big 1) (:value parsed)))
          (is (= :viem-cljs/argument-error (util/error-type bad)) "json-rpc accounts cannot sign locally")
          (is (= "json-rpc" (:account-type (ex-data bad)))))))))

(deftest keystore-round-trip
  (async done
    (tu/async-test done
      (let [pk (core/generate-private-key)
            address (core/private-key->address pk)
            ks-scrypt (tu/encrypt-keystore pk "correct horse" :scrypt)
            ks-pbkdf2 (tu/encrypt-keystore pk "correct horse" :pbkdf2)
            json (utils/stringify ks-scrypt)]
        (p/let [a1 (core/account-from-keystore! ks-scrypt "correct horse")
                a2 (core/account-from-keystore! ks-pbkdf2 "correct horse")
                a3 (core/account-from-keystore! json "correct horse")
                wrong (tu/rejection (core/account-from-keystore! ks-scrypt "wrong"))
                cipher (tu/rejection (core/account-from-keystore! (assoc-in ks-scrypt [:crypto :cipher] "aes-256-gcm") "correct horse"))
                kdf (tu/rejection (core/account-from-keystore! (assoc-in ks-scrypt [:crypto :kdf] "argon2") "correct horse"))
                junk (tu/rejection (core/account-from-keystore! 42 "x"))]
          (is (= address (:address (core/account->map a1))) "scrypt")
          (is (= "local" (:type (core/account->map a1))))
          (is (= address (:address (core/account->map a2))) "pbkdf2")
          (is (= address (:address (core/account->map a3))) "JSON string input")
          (is (= :mac-mismatch (:reason (ex-data wrong))))
          (is (= :viem-cljs/argument-error (util/error-type wrong)))
          (is (= :viem-cljs/argument-error (util/error-type cipher)))
          (is (= :viem-cljs/argument-error (util/error-type kdf)))
          (is (= :viem-cljs/argument-error (util/error-type junk))))))))

;; ---------------------------------------------------------------------------
;; Raw request + error walking
;; ---------------------------------------------------------------------------

(deftest raw-request-and-errors
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" "0x10"
                        "eth_getBlockByNumber" (tu/block-json "0x10")})
            c (tu/public-client s)]
        (p/let [n (core/request! c {:method "eth_blockNumber"})
                b (core/request! c {:method "eth_getBlockByNumber" :params ["latest" false]})
                e (tu/rejection (core/request! c {:method "eth_nope" :params []}))
                bad (tu/rejection (core/request! {} {:method "eth_blockNumber"}))]
          (is (= "0x10" n) "raw hex, not converted")
          (is (= "0x10" (:number b)) "kebab keys, raw values")
          (is (= "0x3b9aca00" (:base-fee-per-gas b)))
          (is (= ["eth_blockNumber" "eth_getBlockByNumber" "eth_nope"] (tu/calls s)))
          (is (= ["latest" false] (tu/call-params s "eth_getBlockByNumber")))
          (is (= :viem-cljs/viem-error (util/error-type e)))
          (is (= "MethodNotFoundRpcError" (util/error-name e)))
          (is (= -32601 (:code (util/error-data e))))
          (is (= :request (:action (util/error-data e))))
          (is (util/error-cause? e "MethodNotFoundRpcError"))
          (is (= "Error" (:name (:root (util/error-data e)))) "root is the stub's plain error")
          (is (= :viem-cljs/argument-error (util/error-type bad)) "non-client rejects, never throws"))))))

;; ---------------------------------------------------------------------------
;; Dispatch guards, bare client, nonce manager
;; ---------------------------------------------------------------------------

(deftest dispatch-guards
  (async done
    (tu/async-test done
      (let [s (tu/stub {})
            c (tu/public-client s)]
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/watch! :watch-block-number {} {} {}))))
            "watchers validate the client synchronously (they return unwatch, not a promise)")
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/invoke-util "noSuchUtility" 1)))))
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/constant "noSuchConstant")))))
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/chain-object 1.5)))))
        (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(core/account-object 42)))))
        (p/let [e1 (tu/rejection (core/action! :no-such-action c {}))
                e2 (tu/rejection (core/action-scalar! :set-automine {} true))]
          (is (= :viem-cljs/argument-error (util/error-type e1)))
          (is (= :no-such-action (:action (ex-data e1))))
          (is (= :viem-cljs/argument-error (util/error-type e2)))
          (is (= [] (tu/calls s))))))))

(deftest bare-client-runs-any-action
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" "0x2a"})
            c (core/client {:transport {:custom (:request s)} :chain :sepolia :cache-time 0})
            info (core/client-info c)]
        (is (= "base" (:key info)))
        (is (= "base" (:type info)))
        (is (= :sepolia (:chain info)))
        (is (= 11155111 (:chain-id info)))
        (is (= 0 (:cache-time info)))
        (p/let [n (core/action! :get-block-number c)]
          (is (= (utils/big 42) n) "tree-shakable actions work on a bare client"))))))

(deftest nonce-manager-tracks-nonces-locally
  (async done
    (tu/async-test done
      (let [s (tu/stub (tu/sending-responses))
            nm (core/create-nonce-manager)
            local (core/private-key->account (core/generate-private-key) {:nonce-manager nm})
            w (tu/wallet-client s local)]
        (is (some? (core/nonce-manager)) "viem's shared default manager is reachable")
        (p/let [_ (p/all [(core/action! :send-transaction w {:to tu/weth-address :value 1})
                          (core/action! :send-transaction w {:to tu/weth-address :value 1})])
                raws (map first (keep (fn [[m ps]] (when (= m "eth_sendRawTransaction") ps)) @(:calls s)))
                nonces (map #(:nonce (utils/parse-transaction %)) raws)]
          (is (= 2 (count raws)))
          (is (= [0 1] (sort nonces)) "concurrent sends get distinct nonces from the manager"))))))
