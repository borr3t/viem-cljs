(ns viem-cljs.examples-test
  "The examples of the README, run. Network-free: the quick start is pure,
   and the functions that take a client run against an in-memory node (the
   `custom` transport over a request fn).

   The README is held to the code:
   - what stands between a README:begin and a README:end mark of
     dev/viem_cljs/examples.cljs stands in README.md as written;
   - every line the quick start prints stands in README.md;
   - every one-liner of `shown` stands in README.md with the value it
     evaluates to;
   - the results README.md shows for the examples over a node are the
     results they resolve to here.
   Spacing and commas are not compared."
  (:require ["fs" :as fs]
            [cljs.core.async :as async]
            [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.chains :as chains]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as viem]
            [viem-cljs.errors :as errors]
            [viem-cljs.events :as events]
            [viem-cljs.examples :as examples :refer [abi account recipient]]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils])
  (:require-macros [viem-cljs.doctest :refer [claims]]))

;; ---------------------------------------------------------------------------
;; The one-liners
;; ---------------------------------------------------------------------------

(def shown
  "The one-liners of the README, each with its value."
  (claims
   (utils/parse-ether "1.5")
   (utils/format-ether (utils/parse-gwei "21000"))
   (utils/format-units (utils/mul (utils/parse-units "25.5" 6) 2) 6)
   (utils/lt? (utils/parse-gwei "30") (utils/parse-ether "1"))
   (= (utils/big "25500000") (utils/parse-units "25.5" 6))
   (utils/get-address "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
   (utils/address? "0xf39fd6e51aad88f6f4ce6ab8827279cfffb9226")
   (utils/function-selector "transfer(address,uint256)")
   (utils/namehash "vitalik.eth")
   (contract/format-abi abi)
   (contract/encode-abi-parameters "address, bool" [recipient true])
   (chains/chain-id :base)
   (chains/chain-name (chains/by-id 10))
   (chains/native-currency :polygon)
   (chains/testnet? :sepolia)
   (viem/account->map account)
   (:address (viem/account->map (viem/mnemonic->account "test test test test test test test test test test test junk" {:address-index 1})))
   (pr-str (util/secret "a key, a mnemonic or a password"))
   (util/redact {:address recipient :private-key "0x01"})))

;; ---------------------------------------------------------------------------
;; An in-memory node
;; ---------------------------------------------------------------------------

(def token
  "The address the examples take for an ERC-20 token."
  "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")

(def sender
  (:address (viem/account->map examples/account)))

(defn- result
  "The return data of `function-name` of an ERC-20 for the value `v`."
  [function-name v]
  (contract/encode-function-result {:abi contract/erc20-abi
                                    :function-name function-name
                                    :result v}))

(defn- erc20-call
  "An eth_call responder of a token: USDC, 6 decimals, a balance of 25.5."
  [[{:keys [data]} _]]
  (case (:function-name (contract/decode-function-data {:abi contract/erc20-abi :data data}))
    "symbol" (result "symbol" "USDC")
    "decimals" (result "decimals" 6)
    "balanceOf" (result "balanceOf" (utils/parse-units "25.5" 6))
    "transfer" (result "transfer" true)))

(defn- word
  "`address` as a 32-byte topic."
  [address]
  (utils/pad (str/lower-case address)))

(defn- transfer-log
  "The Transfer log of the quick start, as a node returns it inside a block."
  []
  (merge (tu/transfer-log-json)
         {:address token
          :transaction-hash tu/tx-hash}
         examples/transfer-log))

(defn- node
  "Responses of a node on which the transfer of the README succeeds."
  []
  (merge (tu/sending-responses)
         {"eth_blockNumber" "0x10"
          "eth_call" erc20-call
          "eth_sendRawTransaction" tu/tx-hash
          "eth_getTransactionReceipt" (assoc (tu/receipt-json)
                                             :from (str/lower-case sender)
                                             :to (str/lower-case token)
                                             :logs [(transfer-log)])}))

(def ^:private revert-data
  "The revert data of `require(false, \"ERC20: transfer amount exceeds balance\")`."
  (contract/encode-error-result {:abi ["error Error(string message)"]
                                 :error-name "Error"
                                 :args ["ERC20: transfer amount exceeds balance"]}))

(defn- reverting
  "An eth_call responder of a node on which every call reverts."
  [_]
  (throw (ex-info "execution reverted"
                  {:code 3
                   :message "execution reverted: ERC20: transfer amount exceeds balance"
                   :data revert-data})))

(defn- take!
  "Promise of the next value on `ch` (nil when closed), with a timeout."
  [ch]
  (p/create (fn [resolve _]
              (let [t (js/setTimeout #(resolve ::timeout) 2000)]
                (async/take! ch (fn [v] (js/clearTimeout t) (resolve v)))))))

;; ---------------------------------------------------------------------------
;; What the README states the examples resolve to
;; ---------------------------------------------------------------------------

(def balance-shown
  "{:symbol \"USDC\" :decimals 6 :raw 25500000N :amount \"25.5\" :block-number 16N}")

(def transfer-shown
  "{:hash \"0x1111111111111111111111111111111111111111111111111111111111111111\"
    :status \"success\"
    :block-number 16N
    :transfers [{:from \"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266\"
                 :to \"0x70997970C51812dc3A010C7d01b50e0d17dc79C8\"
                 :value 25500000N}]}")

(def failure-shown
  "{:failed {:category :reverted
             :name \"ContractFunctionExecutionError\"
             :action :simulate-contract
             :revert \"Error\"
             :revert-args [\"ERC20: transfer amount exceeds balance\"]
             :reason \"ERC20: transfer amount exceeds balance\"
             :retryable? false
             :retry-hint nil}}")

;; ---------------------------------------------------------------------------
;; Reading the README
;; ---------------------------------------------------------------------------

(defn- slurp [path]
  (fs/readFileSync path "utf8"))

(defn- plain
  "`text` without what is not compared: commas, and any run of whitespace
   as one space."
  [text]
  (-> text
      (str/replace "," "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- region
  "The text between the marks README:begin `label` and README:end `label`
   of the file at `path`, nil when either mark is missing."
  [path label]
  (let [[_ found] (re-find (re-pattern (str "(?s);; README:begin " label "\\n(.*?)\\n\\s*;; README:end " label))
                           (slurp path))]
    found))

(defn- printed
  "`m` as the README prints a map."
  [m]
  (plain (pr-str m)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest the-quick-start-computes-what-it-says
  (async done
    (tu/async-test done
      (p/let [{:keys [amount calldata call balance formatted log topic digest
                      signature signer valid?]
               :as computed} (examples/quick-start)]
        (testing "every printed key is computed, and no other"
          (is (= (set examples/printed) (set (keys computed))))
          (is (= (count examples/printed) (count (set examples/printed)))))
        (testing "amounts are BigInt, never a JS number"
          (is (util/bigint? amount))
          (is (util/bigint? balance))
          (is (util/bigint? (get-in log [:args :value])))
          (is (= (utils/big 25500000) amount balance)))
        (testing "the calldata is the selector and two 32-byte words"
          (is (= (+ 2 8 64 64) (count calldata)))
          (is (str/starts-with? calldata (utils/function-selector "transfer(address,uint256)")))
          (is (= {:function-name "transfer" :args [examples/recipient amount]} call)))
        (testing "a result decodes against the outputs of the function"
          (is (= "25.5" formatted)))
        (testing "a log decodes into the named parameters of its event"
          (is (= {:event-name "Transfer"
                  :args {:from sender :to examples/recipient :value amount}}
                 log))
          (is (= topic (first (:topics examples/transfer-log)))))
        (testing "the hash is keccak-256 of the bytes of the string"
          (is (= "0x1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8" digest)))
        (testing "the signature is 65 bytes and recovers to the account"
          (is (= (+ 2 130) (count signature)))
          (is (= sender signer))
          (is (true? valid?)))
        (testing "a signature of another message does not verify"
          (p/let [other (utils/verify-message {:address sender
                                               :message "hello!"
                                               :signature signature})]
            (is (false? other))))))))

(deftest reading-a-token-balance
  (async done
    (tu/async-test done
      (let [s (tu/stub (node))
            c (tu/public-client s)]
        (p/let [balance (examples/token-balance! c token sender)]
          (is (= {:symbol "USDC"
                  :decimals 6
                  :raw (utils/big 25500000)
                  :amount "25.5"
                  :block-number (utils/big 16)}
                 balance))
          (is (= (plain balance-shown) (printed balance)))
          (testing "one block number, then three calls pinned to it"
            (is (= ["eth_blockNumber" "eth_call" "eth_call" "eth_call"] (tu/calls s)))
            (is (= #{"0x10"} (set (map (comp second second)
                                       (filter #(= "eth_call" (first %)) @(:calls s))))))))))))

(deftest sending-a-transfer
  (async done
    (tu/async-test done
      (let [s (tu/stub (node))
            w (tu/wallet-client s examples/account {:polling-interval 5})]
        (p/let [sent (examples/try-transfer! w token examples/recipient "25.5" 6)]
          (is (= {:hash tu/tx-hash
                  :status "success"
                  :block-number (utils/big 16)
                  :transfers [{:from sender
                               :to examples/recipient
                               :value (utils/big 25500000)}]}
                 sent))
          (is (= (plain transfer-shown) (printed sent)))
          (testing "simulated first, then signed locally and broadcast raw"
            (let [methods (tu/calls s)]
              (is (= "eth_call" (first methods)))
              (is (= 1 (count (filter #{"eth_sendRawTransaction"} methods))))
              (is (not-any? #{"eth_sendTransaction"} methods))))
          (testing "what was broadcast is the call of the quick start, signed by the account"
            (p/let [raw (first (tu/call-params s "eth_sendRawTransaction"))
                    from (utils/recover-transaction-address {:serialized-transaction raw})
                    tx (utils/parse-transaction raw)]
              (is (= sender from))
              (is (utils/address-equal? token (:to tx)))
              (p/let [{:keys [calldata]} (examples/quick-start)]
                (is (= calldata (:data tx)))))))))))

(deftest a-failure-is-data
  (async done
    (tu/async-test done
      (let [s (tu/stub (assoc (node) "eth_call" reverting))
            w (tu/wallet-client s examples/account {:polling-interval 5})]
        (p/let [failed (examples/try-transfer! w token examples/recipient "25.5" 6)
                e (tu/rejection (examples/transfer! w token examples/recipient "25.5" 6))]
          (is (= {:failed {:category :reverted
                           :name "ContractFunctionExecutionError"
                           :action :simulate-contract
                           :revert "Error"
                           :revert-args ["ERC20: transfer amount exceeds balance"]
                           :reason "ERC20: transfer amount exceeds balance"
                           :retryable? false
                           :retry-hint nil}}
                 failed))
          (is (= (plain failure-shown) (printed failed)))
          (testing "nothing is sent after a simulation that reverts"
            (is (not-any? #{"eth_sendRawTransaction"} (tu/calls s))))
          (testing "the rejection is an ex-info of the library"
            (is (= :viem-cljs/viem-error (:type (ex-data e))))
            (is (= ["ContractFunctionExecutionError" "ContractFunctionRevertedError"]
                   (take 2 (errors/causes e))))
            (is (errors/reverted? e))))))))

(deftest a-refusal-before-any-request
  (testing "a private key that is not 32 bytes of hex"
    (let [data (ex-data (tu/thrown #(viem/private-key->account "0x1234")))]
      (is (= :viem-cljs/argument-error (:type data)))))
  (testing "a client without a transport"
    (let [e (tu/thrown #(viem/public-client {:chain :mainnet}))]
      (is (= {:type :viem-cljs/config-error :reason :missing-transport} (ex-data e)))
      (is (= :config (errors/category e)))))
  (testing "neither carries the key"
    (let [e (tu/thrown #(viem/private-key->account "0x1234"))]
      (is (not (str/includes? (pr-str (ex-data e)) "1234")))
      (is (not (str/includes? (ex-message e) "1234"))))))

(deftest watching-transfers
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newFilter" "0x1"
                        "eth_getFilterChanges" [(transfer-log)]
                        "eth_uninstallFilter" true})
            c (tu/public-client s {:polling-interval 5})
            seen (async/chan 8)
            ch (examples/watch-transfers! c token #(async/put! seen %)
                                          {:poll? true :polling-interval 5 :buf 64})]
        (p/let [log (take! seen)]
          (is (= "Transfer" (:event-name log)))
          (is (= {:from sender :to examples/recipient :value (utils/big 25500000)}
                 (:args log)))
          (is (events/watching? ch))
          (events/stop! ch)
          (is (not (events/watching? ch)))
          (is (utils/address-equal? token (:address (first (tu/call-params s "eth_newFilter"))))))))))

(deftest the-one-liners-hold
  (let [value (into {} shown)]
    (is (= (count shown) (count value)) "no one-liner is shown twice")
    (is (= (utils/big "1500000000000000000") (value "(utils/parse-ether \"1.5\")")))
    (is (= "0.000021" (value "(utils/format-ether (utils/parse-gwei \"21000\"))")))
    (is (= "51" (value "(utils/format-units (utils/mul (utils/parse-units \"25.5\" 6) 2) 6)")))
    (is (= sender (value "(utils/get-address \"0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266\")")))
    (is (false? (value "(utils/address? \"0xf39fd6e51aad88f6f4ce6ab8827279cfffb9226\")")))
    (is (= "0xa9059cbb" (value "(utils/function-selector \"transfer(address,uint256)\")")))
    (is (= ["balanceOf(address)" "transfer(address,uint256)" "Transfer(address,address,uint256)"]
           (value "(contract/format-abi abi)")))
    (is (= 8453 (value "(chains/chain-id :base)")))
    (is (= "OP Mainnet" (value "(chains/chain-name (chains/by-id 10))")))
    (is (= {:name "POL" :symbol "POL" :decimals 18} (value "(chains/native-currency :polygon)")))
    (is (true? (value "(chains/testnet? :sepolia)")))
    (is (= sender (:address (value "(viem/account->map account)"))))
    (is (= #{:address :type :source :public-key}
           (set (keys (value "(viem/account->map account)"))))
        "the printable view of an account has no key")
    (is (= examples/recipient
           (second (first (filter #(str/includes? (first %) "mnemonic->account") shown)))))
    (is (= "\"#viem-cljs/secret <redacted>\""
           (pr-str (value "(pr-str (util/secret \"a key, a mnemonic or a password\"))"))))
    (is (= {:address examples/recipient :private-key "<redacted>"}
           (value "(util/redact {:address recipient, :private-key \"0x01\"})")))
    (is (every? (comp some? second) shown))))

(deftest the-readme-shows-what-runs
  (let [readme (plain (slurp "README.md"))
        shows? (fn [text] (str/includes? readme (plain text)))]
    (testing "the marked code of the examples"
      (doseq [label ["quick-start" "read" "send" "watch" "errors"]
              :let [code (region "dev/viem_cljs/examples.cljs" label)]]
        (is (some? code) (str "no region " label))
        (is (shows? code) (str "README.md does not show " label ":\n" code))))
    (testing "every one-liner, with its value"
      (doseq [[source value] shown
              :let [line (str source "\n;; => " (pr-str value))]]
        (is (shows? line) (str "README.md does not show:\n" line))))
    (testing "what the examples over a node resolve to"
      (doseq [text [balance-shown transfer-shown failure-shown]]
        (is (shows? text) (str "README.md does not show:\n" text))))
    (testing "the pointers it gives"
      (is (shows? "docs/viem-cljs.org"))
      (is (shows? "docs/viem-api-inventory.org"))
      (is (fs/existsSync "docs/viem-cljs.org"))
      (is (fs/existsSync "docs/viem-api-inventory.org")))))

(deftest the-readme-shows-what-the-quick-start-prints
  (async done
    (tu/async-test done
      (p/let [computed (examples/quick-start)]
        (let [readme (plain (slurp "README.md"))]
          (doseq [k examples/printed
                  :let [line (str k " " (pr-str (get computed k)))]]
            (is (str/includes? readme (plain line))
                (str "README.md does not show the line:\n" line))))))))
