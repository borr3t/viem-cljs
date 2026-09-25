(ns viem-cljs.smoke
  "Phase 5 LIVE smoke: a real anvil node (`~/.foundry/bin/anvil`, spawned
   here as a child process on a random 127.0.0.1 port) driven through the
   public API of every namespace — reads, local-account signing, a value
   transfer with receipt + BigInt balance math, a contract deploy /
   simulate / write / read, events (logs, decoding, a live watcher
   channel), anvil test actions, keystore decryption, a live block-number
   watcher, the error taxonomy on real node errors, and the process
   cleanup. The local node is the live part: nothing here touches the
   Internet. Not part of run-tests.sh.

     npx shadow-cljs compile smoke && node target/smoke.js

   Structure (house pattern): numbered sections that never abort early.
   Each prints `PASS <name> — <detail>` or `FAIL <name>: <reason>`, a tally
   comes last, anvil is killed in every case (also on a harness failure or
   the watchdog) and the exit code is 0 only when every section passed.

   Key material: anvil's well-known developer key #0 is public knowledge
   (anvil prints it at startup) but it is still held in a `util/secret`
   holder, revealed only at `private-key->account`, and every printed
   config goes through `util/redact`; the extra accounts are generated
   fresh in-process and discarded. No real funds exist anywhere here.

   Interop note: the only JavaScript interop outside viem-cljs.core / util
   in this repo is here (and in test-util), limited to spawning / killing
   the anvil child process (`child_process`), timers and `process.exit` —
   in the binding style (`js-invoke` + `util/jsget`, no `^js`, no dot-forms)."
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.actions :as actions]
            [viem-cljs.chains :as chains]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as core]
            [viem-cljs.errors :as errors]
            [viem-cljs.events :as events]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Bookkeeping (house pattern)
;; ---------------------------------------------------------------------------

(def results
  "[{:name .. :ok? .. :reason ..} ...] in section order."
  (atom []))

(def shared
  "Cross-section values: :port :url :child :anvil-exit (promise) :stderr
   (atom) :pub :wallet :test (clients) :account0 :address0 :address1
   :contract (bound map) :contract-address :deploy-block."
  (atom {}))

(defn say
  "println of the joined arguments."
  [& xs]
  (println (str/join " " (map str xs))))

(defn error-brief
  "One line describing a rejection: `errors/summary` for viem-cljs errors,
   the message otherwise."
  [e]
  (or (errors/summary e)
      (some-> (ex-data e) :type name (str ": " (ex-message e)))
      (ex-message e)
      (str e)))

(defn section!
  "Runs `f` (a fn returning a promise of a detail string, or throwing /
   rejecting) as section `name`. Prints PASS/FAIL, records the result and
   ALWAYS resolves so the next section runs."
  [name f]
  (-> (p/do (f))
      (p/then (fn [detail]
                (say (str "PASS " name (when detail (str " — " detail))))
                (swap! results conj {:name name :ok? true})))
      (p/catch (fn [e]
                 (let [reason (error-brief e)]
                   (say (str "FAIL " name ": " reason))
                   (swap! results conj {:name name :ok? false :reason reason}))))))

(defn fail!
  "Throws a plain smoke failure with `reason`."
  [reason & [data]]
  (throw (ex-info reason (merge {:type :smoke/fail} data))))

(defn expect!
  "fail! with `what` unless `ok?`."
  [ok? what]
  (when-not ok? (fail! what)))

(defn rejection
  "Promise of the rejection reason of `prom`; fails when it resolves."
  [prom]
  (-> prom
      (p/then (fn [v] (fail! (str "expected a rejection, resolved " (pr-str v)))))
      (p/catch (fn [e] (p/resolved e)))))

(defn take-within!
  "Promise of the first value on channel `ch`, failing after `ms`."
  [ch ms what]
  (p/create (fn [resolve reject]
              (let [t (js/setTimeout #(reject (ex-info (str "timed out after " ms " ms waiting for " what)
                                                       {:type :smoke/fail}))
                                     ms)]
                (async/take! ch (fn [v] (js/clearTimeout t) (resolve v)))))))

(defn take-until!
  "Promise of the first value on `ch` satisfying `pred` (earlier values are
   consumed and dropped), failing after `ms` in total."
  [ch ms what pred]
  (let [deadline (+ (js/Date.now) ms)]
    (p/loop [v (take-within! ch ms what)]
      (cond
        (pred v) v
        (nil? v) (fail! (str "channel closed while waiting for " what))
        :else (p/recur (take-within! ch (max 1 (- deadline (js/Date.now))) what))))))

(defn sleep
  "Promise resolving after `ms`."
  [ms]
  (p/delay ms nil))

;; ---------------------------------------------------------------------------
;; anvil child process (the only interop in this file)
;; ---------------------------------------------------------------------------

(def anvil-path
  "The foundry binary (inventory 12.18: v1.8.1 verified, no npm package)."
  (str (util/jsget js/process "env" "HOME") "/.foundry/bin/anvil"))

(def ^:private child-process (js/require "child_process"))

(defn spawn-anvil!
  "Starts anvil on `port` (quiet, automine, 10 funded dev accounts). Returns
   `{:child <process> :exit <promise of {:code :signal}> :stderr <atom>}`."
  [port]
  (let [stderr (atom "")
        child (js-invoke child-process "spawn" anvil-path
                         (util/->js ["--port" (str port) "--host" "127.0.0.1" "--quiet"])
                         (util/->js {:stdio ["ignore" "ignore" "pipe"]}))
        exit (p/create (fn [resolve _]
                         (js-invoke child "on" "exit"
                                    (fn [code signal] (resolve {:code code :signal signal})))
                         (js-invoke child "on" "error"
                                    (fn [e] (resolve {:code nil :signal nil :error (ex-message e)})))))]
    (js-invoke (util/jsget child "stderr") "on" "data" (fn [chunk] (swap! stderr str chunk)))
    {:child child :exit exit :stderr stderr}))

(defn kill-anvil!
  "SIGTERM to the child; promise of its exit record (SIGKILL after 5 s)."
  [{:keys [child exit]}]
  (when child
    (js-invoke child "kill" "SIGTERM")
    (js/setTimeout #(when-not (util/jsget child "exitCode")
                      (js-invoke child "kill" "SIGKILL"))
                   5000))
  (or exit (p/resolved nil)))

(defn wait-for-rpc!
  "Polls `eth_chainId` through `client` every 100 ms until it answers
   (promise of the chain id) or `tries` are exhausted."
  [client tries]
  (-> (core/request! client {:method "eth_chainId"})
      (p/catch (fn [e]
                 (if (pos? tries)
                   (p/then (sleep 100) (fn [_] (wait-for-rpc! client (dec tries))))
                   (throw e))))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def dev-key-0
  "anvil / hardhat developer account #0 private key — PUBLIC test material
   (printed by anvil itself at startup; derived from the public mnemonic
   `test test … junk`). Held in a Secret holder anyway: never printed."
  (util/secret "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"))

(def store-abi
  "Human-readable ABI of the Store contract below."
  ["function store(uint256 v)"
   "function retrieve() view returns (uint256)"
   "function fail(string reason) pure"
   "event Stored(address indexed by, uint256 value)"
   "error TooBig(uint256 value, uint256 limit)"])

(def store-bytecode
  "Creation bytecode of a minimal Store contract, compiled ONCE with
   solc 0.8.37 (`--optimize --optimize-runs 200`, default EVM version,
   uses PUSH0) and embedded here — no Solidity toolchain is a dependency.

     contract Store {
       uint256 private value;
       event Stored(address indexed by, uint256 value);
       error TooBig(uint256 value, uint256 limit);
       function store(uint256 v) external {
         if (v > 1000) revert TooBig(v, 1000);
         value = v; emit Stored(msg.sender, v);
       }
       function retrieve() external view returns (uint256) { return value; }
       function fail(string calldata reason) external pure { revert(reason); }
     }"
  (str "0x"
       "6080604052348015600e575f5ffd5b506101f38061001c5f395ff3fe608060405234801561000f575f5ffd5b506004361061003f575f3560e01c80632e64cec1146100435780636057361d1461005757806378122f3a1461006c575b5f5ffd5b5f5460405190815260200160405180910390f35b61006a61006536600461010a565b61007f565b005b61006a61007a366004610121565b6100ee565b6103e88111156100b257604051631ab6737f60e21b8152600481018290526103e860248201526044015b60405180910390fd5b5f81905560405181815233907febfcf7c0a1b09f6499e519a8d8bb85ce33cd539ec6cbd964e116cd74943ead1a9060200160405180910390a250565b818160405162461bcd60e51b81526004016100a992919061018f565b5f6020828403121561011a575f5ffd5b5035919050565b5f5f60208385031215610132575f5ffd5b823567ffffffffffffffff811115610148575f5ffd5b8301601f81018513610158575f5ffd5b803567ffffffffffffffff81111561016e575f5ffd5b85602082840101111561017f575f5ffd5b6020919091019590945092505050565b60208152816020820152818360408301375f818301604090810191909152601f909201601f1916010191905056fea26469706673582212209f3808cbe1e1a3f84f1ca713ea18927fe8beb8c81ce36dbfc2349b5eecf7b65364736f6c63430008250033"))

(def one-ether (utils/parse-ether "1"))

;; ---------------------------------------------------------------------------
;; Sections
;; ---------------------------------------------------------------------------

(defn s0-spawn! []
  (let [port (+ 18545 (rand-int 2000))
        url (str "http://127.0.0.1:" port)
        {:keys [child exit stderr]} (spawn-anvil! port)
        config {:anvil anvil-path :port port :url url :chain :anvil
                :polling-interval 100 :dev-key dev-key-0
                :mnemonic tu/dev-mnemonic}
        pub (core/public-client {:chain :anvil :transport (core/http url) :polling-interval 100})]
    (swap! shared assoc :port port :url url :child child :anvil-exit exit :stderr stderr :pub pub)
    (say "  config:" (pr-str (util/redact config)))
    (p/let [hex-id (wait-for-rpc! pub 100)
            id (actions/get-chain-id! pub)
            info (core/client-info pub)
            account0 (core/private-key->account (util/reveal dev-key-0))
            address0 (:address (core/account->map account0))
            wallet (core/wallet-client {:chain :anvil :transport {:http url} :account account0 :polling-interval 100})
            test-c (core/test-client {:chain :anvil :transport (core/http url) :mode :anvil :polling-interval 100})]
      (swap! shared assoc :account0 account0 :address0 address0 :wallet wallet :test test-c)
      (expect! (= "0x7a69" hex-id) (str "raw eth_chainId " hex-id))
      (expect! (= 31337 id) (str "chain id " id))
      (expect! (= :anvil (:chain info)) (str "client-info chain " (:chain info)))
      (expect! (= "http" (:transport-type info)) (str "transport " (:transport-type info)))
      (expect! (= [:anvil :foundry :hardhat] (chains/aliases :anvil)) "registry aliases of 31337")
      (expect! (nil? (re-find #"ac0974" (pr-str (util/redact config)))) "redacted config must not contain the key")
      (str "anvil pid=" (util/jsget child "pid") " on " url " chain-id=" id
           " (" (chains/chain-name :anvil) ", aliases " (pr-str (chains/aliases :anvil)) ")"
           " client-info=" (pr-str (select-keys info [:key :type :chain :transport-type :polling-interval]))
           " account0=" address0))))

(defn s1-reads! []
  (let [{:keys [pub address0]} @shared]
    (p/let [n (actions/get-block-number! pub)
            id (actions/get-chain-id! pub)
            bal (actions/get-balance! pub {:address address0})
            block (actions/get-block! pub {:block-tag :latest})
            gas-price (actions/get-gas-price! pub)
            fees (actions/estimate-fees-per-gas! pub)
            count0 (actions/get-transaction-count! pub {:address address0})
            code (actions/get-code! pub {:address address0})
            raw (core/request! pub {:method "eth_getBalance" :params [address0 "latest"]})]
      (expect! (utils/bigint? n) "block number is BigInt")
      (expect! (= (utils/big 0) n) (str "fresh anvil at block " n))
      (expect! (= 31337 id) "chain id")
      (expect! (= "10000" (utils/format-ether bal)) (str "dev balance " (utils/format-ether bal) " ETH"))
      (expect! (= (utils/parse-ether "10000") bal) "10000 ETH in wei as BigInt")
      (expect! (= (utils/big 0) (:number block)) "latest block number")
      (expect! (utils/bigint? (:timestamp block)) "block timestamp BigInt")
      (expect! (utils/bigint? gas-price) "gas price BigInt")
      (expect! (utils/bigint? (:max-fee-per-gas fees)) "eip1559 fees BigInt")
      (expect! (= 0 count0) "nonce 0 (number)")
      (expect! (nil? code) "EOA has no code -> nil")
      (expect! (= (utils/hex->bigint raw) bal) "raw request! hex equals the action's BigInt")
      (str "block=" n " chain-id=" id " balance(account0)=" (utils/format-ether bal) " ETH (" bal ")"
           " gas-price=" gas-price " max-fee=" (:max-fee-per-gas fees)
           " nonce=" count0 " code=nil raw eth_getBalance=" raw))))

(defn s2-account! []
  (let [{:keys [pub account0 address0]} @shared
        hd (core/mnemonic->account tu/dev-mnemonic {:address-index 0})
        hd1 (core/mnemonic->account tu/dev-mnemonic {:address-index 1})
        address1 (:address (core/account->map hd1))
        message "viem-cljs smoke: hello anvil"]
    (swap! shared assoc :address1 address1)
    (p/let [sig (core/sign-message account0 {:message message})
            ok (utils/verify-message {:address address0 :message message :signature sig})
            bad (utils/verify-message {:address address1 :message message :signature sig})
            ok-client (actions/verify-message! pub {:address address0 :message message :signature sig})
            recovered (utils/recover-message-address {:message message :signature sig})
            parsed (utils/parse-signature sig)]
      (expect! (= tu/dev-address-0 address0) (str "private-key->account address " address0))
      (expect! (= address0 (:address (core/account->map hd))) "mnemonic derivation agrees with the private key")
      (expect! (= tu/dev-address-1 address1) (str "account #1 " address1))
      (expect! (= address0 (core/private-key->address (util/reveal dev-key-0))) "private-key->address")
      (expect! (= #{:address :type :source :public-key} (set (keys (core/account->map account0)))) "account->map exposes no key material")
      (expect! (= "local" (:type (core/account->map account0))) "local account")
      (expect! (= 132 (count sig)) "65-byte signature")
      (expect! (true? ok) "pure verify-message")
      (expect! (false? bad) "wrong signer rejected")
      (expect! (true? ok-client) "client verify-message! (eth_call path)")
      (expect! (= address0 recovered) "recover-message-address")
      (expect! (contains? #{27 28} (utils/bigint->number (:v parsed))) "parsed v")
      (str "address0=" address0 " (private key + mnemonic agree) address1=" address1
           " sign-message -> " (subs sig 0 12) "… verify pure=" ok " client=" ok-client
           " wrong-signer=" bad " recovered=" recovered))))

(defn s3-transfer! []
  (let [{:keys [pub wallet address0 address1]} @shared]
    (p/let [before0 (actions/get-balance! pub {:address address0})
            before1 (actions/get-balance! pub {:address address1})
            hash (actions/send-transaction! wallet {:to address1 :value one-ether})
            receipt (actions/wait-for-transaction-receipt! wallet {:hash hash :timeout 10000})
            tx (actions/get-transaction! pub {:hash hash})
            after0 (actions/get-balance! pub {:address address0})
            after1 (actions/get-balance! pub {:address address1})
            gas-cost (utils/mul (:gas-used receipt) (:effective-gas-price receipt))
            spent (utils/sub before0 after0)
            received (utils/sub after1 before1)]
      (expect! (utils/hash? hash) (str "tx hash " hash))
      (expect! (= "success" (:status receipt)) (str "receipt status " (:status receipt)))
      (expect! (= (utils/big 1) (:block-number receipt)) (str "mined in block " (:block-number receipt)))
      (expect! (= address1 (utils/get-address (:to receipt))) "receipt :to")
      (expect! (= one-ether received) (str "receiver delta " received))
      (expect! (= (utils/add one-ether gas-cost) spent) (str "sender delta " spent " = value + gas " gas-cost))
      (expect! (= one-ether (:value tx)) "get-transaction! value")
      (expect! (= "eip1559" (:type tx)) (str "tx type " (:type tx)))
      (expect! (= 0 (:nonce tx)) "nonce number")
      (str "sent 1 ETH " address0 " -> " address1 " hash=" (subs hash 0 12) "… block=" (:block-number receipt)
           " status=" (:status receipt) " gas-used=" (:gas-used receipt) " effective-gas-price=" (:effective-gas-price receipt)
           " | receiver +" (utils/format-ether received) " ETH, sender -" (utils/format-ether spent)
           " ETH (= 1 + gas " (utils/format-ether gas-cost) ")"))))

(defn s4-contract! []
  (let [{:keys [pub wallet address0]} @shared]
    (p/let [hash (contract/deploy! wallet {:abi store-abi :bytecode store-bytecode})
            receipt (actions/wait-for-transaction-receipt! wallet {:hash hash :timeout 10000})
            address (:contract-address receipt)
            c (contract/bind {:client wallet :address address :abi store-abi})
            code (actions/get-code! pub {:address address})
            initial (contract/read! c {:function-name :retrieve})
            gas (contract/estimate-gas! c {:function-name :store :args [42]})
            sim (contract/simulate! c {:function-name :store :args [42]})
            write-hash (contract/write! c (:request sim))
            write-receipt (actions/wait-for-transaction-receipt! wallet {:hash write-hash :timeout 10000})
            value (contract/read! c {:function-name :retrieve})
            value-pub (actions/read-contract! pub {:abi store-abi :address address :function-name "retrieve"})
            hash2 (contract/simulate-and-write! c {:function-name :store :args [(utils/big 43)]})
            _ (actions/wait-for-transaction-receipt! wallet {:hash hash2 :timeout 10000})
            value2 (contract/read! c {:function-name :retrieve})
            calldata (contract/encode-function-data {:abi store-abi :function-name :store :args [42]})
            decoded (contract/decode-function-data {:abi store-abi :data calldata})]
      (swap! shared assoc :contract c :contract-address address :deploy-block (:block-number receipt))
      (expect! (utils/address? address) (str "contract address " address))
      (expect! (= "success" (:status receipt)) "deploy receipt")
      (expect! (= address0 (utils/get-address (:from receipt))) "deployer")
      (expect! (and (string? code) (> (count code) 100)) "code at the address")
      (expect! (= (utils/big 0) initial) "initial retrieve = 0")
      (expect! (utils/gt? gas 21000) (str "estimate-gas! " gas))
      (expect! (nil? (:result sim)) "store returns nothing")
      (expect! (= "store" (get-in sim [:request :function-name])) "simulate! request")
      (expect! (= "success" (:status write-receipt)) "write receipt")
      (expect! (= 1 (count (:logs write-receipt))) "one log emitted")
      (expect! (= (utils/big 42) value) (str "retrieve after store(42): " value))
      (expect! (= (utils/big 42) value-pub) "read-contract! via the public client")
      (expect! (= (utils/big 43) value2) "simulate-and-write! store(43)")
      (expect! (= (subs calldata 0 10) (utils/function-selector "store(uint256)")) "selector")
      (expect! (= {:function-name "store" :args [(utils/big 42)]} decoded) "decode-function-data")
      (str "deployed Store at " address " (block " (:block-number receipt) ", code " (count code) " chars);"
           " simulate!->write! store(42) status=" (:status write-receipt) " gas-est=" gas
           "; read! retrieve=" value " ; simulate-and-write! store(43) -> " value2))))

(defn s5-events! []
  (let [{:keys [pub wallet contract contract-address address0 deploy-block]} @shared
        stored-event "event Stored(address indexed by, uint256 value)"]
    (p/let [logs (actions/get-logs! pub {:address contract-address :event stored-event
                                         :from-block deploy-block :to-block :latest})
            raw-logs (actions/get-logs! pub {:address contract-address :from-block deploy-block})
            decoded (contract/decode-event-log {:abi store-abi
                                                :topics (:topics (first raw-logs))
                                                :data (:data (first raw-logs))})
            parsed (contract/parse-event-logs {:abi store-abi :logs raw-logs})
            cevents (contract/events! contract {:event-name "Stored" :from-block deploy-block})
            filtered (contract/events! contract {:event-name "Stored" :args {:by address0} :from-block deploy-block})
            ch (events/watch-event! pub {:address contract-address :event stored-event
                                         :poll? true :polling-interval 100})
            ch2 (events/watch-contract-event! pub {:contract contract :event-name "Stored"
                                                   :poll? true :polling-interval 100})
            _ (sleep 300)
            hash (contract/write! contract {:function-name :store :args [7]})
            _ (actions/wait-for-transaction-receipt! wallet {:hash hash :timeout 10000})
            ev (take-within! ch 8000 "watch-event! log")
            ev2 (take-within! ch2 8000 "watch-contract-event! log")
            _ (events/stop! ch)
            _ (events/stop! ch2)
            closed (take-within! ch 1000 "closed channel")]
      (expect! (= 2 (count logs)) (str "get-logs! decoded count " (count logs)))
      (expect! (= "Stored" (:event-name (first logs))) "event name decoded")
      (expect! (= {:by address0 :value (utils/big 42)} (:args (first logs))) (str "first log args " (pr-str (:args (first logs)))))
      (expect! (= (utils/big 43) (get-in (second logs) [:args :value])) "second log")
      (expect! (= "Stored" (:event-name decoded)) "decode-event-log")
      (expect! (= {:by address0 :value (utils/big 42)} (:args decoded)) "decode-event-log args")
      (expect! (= 2 (count parsed)) "parse-event-logs")
      (expect! (= 2 (count cevents)) "contract/events!")
      (expect! (= 2 (count filtered)) "indexed arg filter")
      (expect! (= :log (:event ev)) (str "watcher event " (pr-str (:event ev))))
      (expect! (= (utils/big 7) (get-in ev [:log :args :value])) (str "live watch-event! decoded value " (pr-str (get-in ev [:log :args]))))
      (expect! (= (utils/big 7) (get-in ev2 [:log :args :value])) "live watch-contract-event! value")
      (expect! (nil? closed) "stop! closed the channel")
      (expect! (not (events/watching? ch)) "watcher unsubscribed")
      (str "get-logs! " (count logs) " Stored logs decoded (args " (pr-str (:args (first logs))) "); decode-event-log/parse-event-logs/events! agree;"
           " live watch-event! + watch-contract-event! each received store(7) within the bound; stop! closed"))))

(defn s6-test-actions! []
  (let [{:keys [pub test address1 url]} @shared
        fresh (core/private-key->address (core/generate-private-key))
        ghost (core/wallet-client {:chain :anvil :transport (core/http url) :account fresh :polling-interval 100})]
    (p/let [n0 (actions/get-block-number! pub {:cache-time 0})
            _ (actions/mine! test {:blocks 3})
            n1 (actions/get-block-number! pub {:cache-time 0})
            _ (actions/set-balance! test {:address fresh :value (utils/parse-ether "5")})
            bal (actions/get-balance! pub {:address fresh})
            automine (actions/get-automine! test)
            _ (actions/impersonate-account! test {:address fresh})
            hash (actions/send-transaction! ghost {:to address1 :value one-ether})
            receipt (actions/wait-for-transaction-receipt! pub {:hash hash :timeout 10000})
            _ (actions/stop-impersonating-account! test {:address fresh})
            after (actions/get-balance! pub {:address fresh})
            snap (actions/snapshot! test)
            block-before (actions/get-block! pub {:block-tag :latest})
            _ (actions/increase-time! test {:seconds 3600})
            _ (actions/mine! test {:blocks 1})
            block-after (actions/get-block! pub {:block-tag :latest})
            _ (actions/revert! test {:id snap})
            n2 (actions/get-block-number! pub {:cache-time 0})
            nonce (actions/get-transaction-count! pub {:address fresh})]
      (expect! (= (utils/add n0 3) n1) (str "mine! 3: " n0 " -> " n1))
      (expect! (= (utils/parse-ether "5") bal) "set-balance! 5 ETH")
      (expect! (true? automine) "automine on")
      (expect! (= "success" (:status receipt)) "impersonated eth_sendTransaction mined")
      (expect! (utils/lt? after (utils/parse-ether "4")) (str "impersonated sender paid: " (utils/format-ether after)))
      (expect! (string? snap) (str "snapshot id " snap))
      (expect! (utils/gte? (utils/sub (:timestamp block-after) (:timestamp block-before)) 3600)
               (str "increase-time! " (:timestamp block-before) " -> " (:timestamp block-after)))
      (expect! (= (:number block-before) n2) (str "revert! restored block " n2))
      (expect! (= 1 nonce) "impersonated tx nonce survived (snapshot taken after it)")
      (str "mine!(3) " n0 "->" n1 "; set-balance! fresh=" (utils/format-ether bal) " ETH; impersonate-account! + json-rpc account send 1 ETH -> "
           (:status receipt) " (balance now " (utils/format-ether after) "); snapshot " snap
           " / increase-time! +3600 (" (utils/sub (:timestamp block-after) (:timestamp block-before)) " s) / revert! -> block " n2))))

(defn s7-keystore! []
  (let [pk (util/secret (core/generate-private-key))
        address (core/private-key->address (util/reveal pk))
        ks-scrypt (tu/encrypt-keystore (util/reveal pk) "correct horse battery staple" :scrypt)
        ks-pbkdf2 (tu/encrypt-keystore (util/reveal pk) "correct horse battery staple" :pbkdf2)
        json (utils/stringify ks-scrypt)
        {:keys [pub url address1]} @shared]
    (p/let [a1 (core/account-from-keystore! ks-scrypt "correct horse battery staple")
            a2 (core/account-from-keystore! json "correct horse battery staple")
            a3 (core/account-from-keystore! ks-pbkdf2 "correct horse battery staple")
            wrong (rejection (core/account-from-keystore! ks-scrypt "wrong"))
            sig (core/sign-message a1 {:message "keystore"})
            ok (utils/verify-message {:address address :message "keystore" :signature sig})
            ;; fund it and spend from it, proving the decrypted account signs real txs
            _ (actions/set-balance! (:test @shared) {:address address :value (utils/parse-ether "2")})
            w (core/wallet-client {:chain :anvil :transport (core/http url) :account a1 :polling-interval 100})
            hash (actions/send-transaction! w {:to address1 :value one-ether})
            receipt (actions/wait-for-transaction-receipt! pub {:hash hash :timeout 10000})]
      (expect! (= address (:address (core/account->map a1))) "scrypt keystore -> same address")
      (expect! (= address (:address (core/account->map a2))) "JSON string keystore")
      (expect! (= address (:address (core/account->map a3))) "pbkdf2 keystore")
      (expect! (= :mac-mismatch (errors/reason wrong)) (str "wrong password: " (error-brief wrong)))
      (expect! (errors/argument-error? wrong) "wrong password is an argument-error")
      (expect! (true? ok) "decrypted account signs")
      (expect! (= "success" (:status receipt)) "decrypted account sent a real tx")
      (expect! (nil? (re-find #"[0-9a-f]{64}" (pr-str (util/redact {:private-key pk :keystore (dissoc ks-scrypt :crypto)})))) "printed report has no key")
      (str "fresh key (never printed) -> V3 keystore scrypt/pbkdf2/JSON all decrypt to " address
           "; wrong password -> " (errors/reason wrong) "; signed + sent 1 ETH status=" (:status receipt)))))

(defn s8-watch-block-number! []
  (let [{:keys [pub test]} @shared
        ch (events/watch-block-number! pub {:emit-on-begin? true :poll? true :polling-interval 100})
        blocks (events/watch-blocks! pub {:poll? true :polling-interval 100})]
    (p/let [first-ev (take-within! ch 5000 "emit-on-begin block number")
            _ (sleep 200)
            _ (actions/mine! test {:blocks 1})
            next-ev (take-within! ch 5000 "next block number after mine!")
            ;; viem's watchBlocks (polling) emits the current head on its first
            ;; poll even without emit-on-begin, so skip until the mined block
            block-ev (take-until! blocks 5000 "watch-blocks! block"
                                  #(utils/gte? (get-in % [:block :number]) (:block-number next-ev)))
            _ (events/stop! ch)
            _ (events/stop! blocks)]
      (expect! (= :block-number (:event first-ev)) "event tag")
      (expect! (utils/bigint? (:block-number first-ev)) "BigInt block number")
      (expect! (= (utils/add (:block-number first-ev) 1) (:block-number next-ev))
               (str "next = prev + 1: " (:block-number first-ev) " -> " (:block-number next-ev)))
      (expect! (= (:block-number first-ev) (:prev next-ev)) ":prev carried")
      (expect! (= :block (:event block-ev)) "watch-blocks! event")
      (expect! (= (get-in block-ev [:block :number]) (:block-number next-ev))
               (str "watch-blocks! saw block " (get-in block-ev [:block :number]) ", expected " (:block-number next-ev)))
      (str "watch-block-number! emit-on-begin=" (:block-number first-ev) " then after mine! -> " (:block-number next-ev)
           " (prev " (:prev next-ev) "); watch-blocks! block #" (get-in block-ev [:block :number])
           " with " (count (get-in block-ev [:block :transactions])) " txs; both stopped"))))

(defn s9-error-taxonomy! []
  (let [{:keys [pub wallet contract address1 url]} @shared
        nobody (core/private-key->address (core/generate-private-key))
        broke (core/wallet-client {:chain :anvil :transport (core/http url)
                                   :account (core/private-key->account (core/generate-private-key))
                                   :polling-interval 100})]
    (p/let [plain-call (actions/call! pub {:to nobody :data (utils/function-selector "retrieve()")})
            zero (rejection (actions/read-contract! pub {:abi store-abi :address nobody :function-name :retrieve}))
            too-big (rejection (contract/simulate! contract {:function-name :store :args [5000]}))
            reason (rejection (contract/read! contract {:function-name :fail :args ["boom"]}))
            funds (rejection (actions/send-transaction! broke {:to address1 :value one-ether}))
            bad-method (rejection (core/request! pub {:method "eth_noSuchMethod" :params []}))
            bad-address (rejection (actions/read-contract! pub {:abi store-abi :address "0x123" :function-name :retrieve}))
            bad-param (rejection (actions/get-balance! pub {:address "0x123"}))
            bad-quantity (rejection (actions/get-balance! pub {:address address1 :block-number 1.5}))
            no-chain (rejection (actions/get-ens-address! (core/public-client {:transport (core/http url)}) {:name "vitalik.eth"}))]
      (expect! (nil? (:data plain-call)) (str "call! to a no-code address -> " (pr-str plain-call)))
      (expect! (= "ContractFunctionExecutionError" (errors/error-name zero)) (str "zero-data outer " (errors/error-name zero)))
      (expect! (errors/zero-data? zero) (str "zero-data chain " (pr-str (errors/causes zero))))
      (expect! (= :zero-data (errors/category zero)) "category")
      (expect! (= "ContractFunctionExecutionError" (errors/error-name too-big)) "revert outer")
      (expect! (= "TooBig" (errors/revert-error-name too-big)) (str "custom error decoded: " (pr-str (errors/revert-data too-big))))
      (expect! (= [(utils/big 5000) (utils/big 1000)] (errors/revert-args too-big)) "revert args BigInt")
      (expect! (= :reverted (errors/category too-big)) "reverted category")
      (expect! (not (errors/retryable? too-big)) "revert not retryable")
      (expect! (= "boom" (errors/revert-reason reason)) (str "Error(string) reason: " (pr-str (errors/revert-reason reason))))
      (expect! (errors/insufficient-funds? funds) (str "insufficient funds chain " (pr-str (errors/causes funds))))
      (expect! (= :insufficient-funds (errors/category funds)) "category insufficient-funds")
      (expect! (errors/unsupported-method? bad-method) (str "unknown method " (pr-str (errors/causes bad-method))))
      (expect! (= -32601 (errors/rpc-code bad-method)) (str "rpc code " (errors/rpc-code bad-method)))
      (expect! (errors/cause? bad-address "InvalidAddressError") (str "invalid address caught before any RPC: " (pr-str (errors/causes bad-address))))
      (expect! (and (errors/viem-error? bad-param) (integer? (errors/rpc-code bad-param)))
               (str "node-side invalid param: " (error-brief bad-param)))
      (expect! (errors/argument-error? bad-quantity) "argument-error before viem")
      (expect! (errors/chain-error? no-chain) (str "ENS without chain: " (error-brief no-chain)))
      (str "call!(no code)=" (pr-str plain-call)
           " | read-contract!(no code): " (str/join " > " (errors/causes zero)) " [" (name (errors/category zero)) "]"
           " | store(5000): " (str/join " > " (errors/causes too-big)) " revert=" (errors/revert-error-name too-big) (pr-str (errors/revert-args too-big))
           " | fail(\"boom\"): reason=" (pr-str (errors/revert-reason reason))
           " | empty sender: " (str/join " > " (errors/causes funds)) " [" (name (errors/category funds)) "]"
           " | eth_noSuchMethod: " (errors/error-name bad-method) " code=" (errors/rpc-code bad-method)
           " | read-contract! 0x123: " (str/join " > " (errors/causes bad-address))
           " | eth_getBalance 0x123 (node-side): " (errors/error-name bad-param) " code=" (errors/rpc-code bad-param)
           " | block-number 1.5: " (name (errors/category bad-quantity))
           " | ens w/o chain: " (errors/error-name no-chain)))))

(defn s10-cleanup! []
  (let [{:keys [pub child anvil-exit stderr port]} @shared]
    (p/let [exit (p/race [(kill-anvil! {:child child :exit anvil-exit})
                          (p/then (sleep 8000) (fn [_] {:code nil :signal :timeout}))])
            _ (sleep 200)
            gone (rejection (actions/get-chain-id! pub))]
      (swap! shared assoc :killed? true)
      (expect! (not= :timeout (:signal exit)) "anvil did not exit within 8 s")
      (expect! (or (= "SIGTERM" (:signal exit)) (= 0 (:code exit))) (str "exit record " (pr-str exit)))
      (expect! (errors/connection-error? gone) (str "port still answering after kill: " (error-brief gone)))
      (expect! (= :connection (errors/category gone)) "category connection")
      (str "anvil pid=" (util/jsget child "pid") " exited " (pr-str exit) "; port " port " refuses connections ("
           (errors/error-name gone) " [" (name (errors/category gone)) "])"
           (when (seq @stderr) (str "; anvil stderr: " (str/trim @stderr)))))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn- final-cleanup!
  "Kills anvil if a section skipped the cleanup (harness failure / watchdog)."
  []
  (let [{:keys [child anvil-exit killed?]} @shared]
    (if (and child (not killed?))
      (-> (p/race [(kill-anvil! {:child child :exit anvil-exit})
                   (p/then (sleep 8000) (fn [_] {:signal :timeout}))])
          (p/then (fn [exit] (say "final cleanup: anvil" (pr-str exit))))
          (p/catch (fn [e] (say "final cleanup failed:" (error-brief e)))))
      (p/resolved nil))))

(defn- finish! []
  (-> (final-cleanup!)
      (p/finally (fn [_ _]
                   (let [rs @results
                         failed (remove :ok? rs)]
                     (say "")
                     (say (str "TALLY: " (count (filter :ok? rs)) "/" (count rs) " sections passed"))
                     (doseq [f failed] (say (str "  FAILED " (:name f) ": " (:reason f))))
                     (js-invoke js/process "exit" (if (and (= 11 (count rs)) (empty? failed)) 0 1)))))))

(defn main [& _]
  (say "viem-cljs LIVE smoke —" (js/Date.) "— local anvil child process on 127.0.0.1 (no Internet, no real funds)")
  (js/setTimeout (fn []
                   (say "FAIL harness: watchdog fired after 180 s")
                   (swap! results conj {:name "watchdog" :ok? false :reason "180 s elapsed"})
                   (finish!))
                 180000)
  (-> (p/do
        (section! "0 spawn anvil + http client on localhost (chain id 31337)" s0-spawn!)
        (section! "1 reads: block-number! chain-id! get-balance! (10000 ETH) get-block! fees, raw request!" s1-reads!)
        (section! "2 account: private-key->account deterministic address, sign-message + verify round trip" s2-account!)
        (section! "3 send-transaction! 1 ETH -> wait-for-transaction-receipt! success, BigInt balance deltas" s3-transfer!)
        (section! "4 contract: deploy! -> bind -> simulate! -> write! store(42) -> read! retrieve 42" s4-contract!)
        (section! "5 events: get-logs! + decode-event-log + events!, live watch-event! channel" s5-events!)
        (section! "6 test actions: mine! set-balance! impersonate round-trip, snapshot/increase-time/revert" s6-test-actions!)
        (section! "7 keystore: encrypt V3 in-test -> account-from-keystore! -> same address, signs + sends" s7-keystore!)
        (section! "8 watch-block-number! live: next block after mine! (bounded wait)" s8-watch-block-number!)
        (section! "9 error taxonomy live: zero-data, custom revert, Error(string), insufficient funds, rpc codes" s9-error-taxonomy!)
        (section! "10 cleanup: kill anvil, verify exit + port freed" s10-cleanup!))
      (p/catch (fn [e] (say "FAIL harness:" (error-brief e))))
      (p/then finish!)))
