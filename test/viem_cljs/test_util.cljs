(ns viem-cljs.test-util
  "Shared fixtures for the viem-cljs tests: well-known PUBLIC addresses and
   hashes, the public Hardhat/anvil developer mnemonic (zero-value accounts by
   design; never a private key of anyone's), canned JSON-RPC results, the
   in-memory request stub that stands in for a node (inventory 11.4), an
   async-test helper and a V3 keystore ENCRYPTOR (Node crypto + viem
   keccak256 — the mirror image of `core/account-from-keystore!`, kept in
   test code because keystore encryption is Tier 2). Not a test namespace
   itself (the name does not end in -test)."
  (:require ["crypto" :as crypto]
            ["viem" :as viem]
            [cljs.test :refer [is]]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.core :as core]))

(def zero-address "0x0000000000000000000000000000000000000000")

(def zero-hash (str "0x" (apply str (repeat 64 "0"))))

(def weth-address
  "Mainnet WETH9 (checksummed) — a public contract address."
  "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")

(def weth-address-lower "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")

(def multicall3-address "0xcA11bde05977b3631167028862bE2a173976CA11")

(def tx-hash
  "A syntactically valid 32-byte hash (invented)."
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(def dev-mnemonic
  "The public Hardhat / anvil developer mnemonic (accounts #0 =
   0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266, #1 = 0x7099…79C8)."
  "test test test test test test test test test test test junk")

(def dev-address-0 "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
(def dev-address-1 "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")

(def erc20-abi
  "Human-readable ABI fragment (viem parseAbi input)."
  ["function balanceOf(address owner) view returns (uint256)"
   "function transfer(address to, uint256 amount) returns (bool)"
   "event Transfer(address indexed from, address indexed to, uint256 value)"])

(def transfer-topic
  "keccak256(\"Transfer(address,address,uint256)\")."
  "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")

(def uint-5
  "ABI-encoded uint256 5."
  "0x0000000000000000000000000000000000000000000000000000000000000005")

(def uint-1
  "ABI-encoded uint256 1 (also `true`)."
  "0x0000000000000000000000000000000000000000000000000000000000000001")

(defn rejection
  "Promise of the rejection reason of `p` (fails the assertion if it resolves)."
  ;; NB: promesa treats a js/Error RETURNED from a catch handler as a
  ;; rejection (IPromiseFactory on js/Error), so the error must be wrapped in
  ;; an already-resolved promise to travel as a value.
  [promise]
  (-> promise
      (p/then (fn [v] (throw (ex-info "expected rejection" {:resolved v}))))
      (p/catch (fn [e] (p/resolved e)))))

(defn thrown
  "The value thrown by (f), nil when it returns normally."
  [f]
  (try (f) nil (catch :default e e)))

(defn async-test
  "Runs promise `pr` inside a cljs.test `async` block: an unexpected
   rejection fails one assertion with the error's message and data; `done`
   is always called."
  [done pr]
  (-> pr
      (p/catch (fn [e]
                 (is false (str "unexpected rejection: " (ex-message e) " " (pr-str (ex-data e))))))
      (p/finally (fn [_ _] (done)))))

(defn sleep
  "Promise resolving after `ms`."
  [ms]
  (p/delay ms nil))

;; ---------------------------------------------------------------------------
;; JSON-RPC stub transport (inventory 11.4)
;; ---------------------------------------------------------------------------

(defn stub
  "In-memory JSON-RPC responder: `responses` maps method name -> canned
   result | (fn [params] result-or-promise). Unknown methods throw a -32601
   error (viem classifies it as MethodNotFoundRpcError). Returns
   `{:request fn :calls atom}`; `:calls` records `[method params]` in order.
   Results may be kebab maps / vectors (converted to camelCase JSON objects
   by the custom transport) or plain values."
  [responses]
  (let [calls (atom [])]
    {:calls calls
     :request (fn [{:keys [method params]}]
               (swap! calls conj [method params])
               (if (contains? responses method)
                 (let [r (get responses method)]
                   (if (fn? r) (r params) r))
                 (throw (ex-info (str "Method not found: " method)
                                 {:code -32601 :message (str "Method not found: " method)}))))}))

(defn calls
  "The methods called on a stub, in order."
  [s]
  (mapv first @(:calls s)))

(defn call-params
  "The params of the first call of `method` on a stub (nil when never called)."
  [s method]
  (some (fn [[m ps]] (when (= m method) ps)) @(:calls s)))

(defn public-client
  "Public client on `:mainnet` over the stub (extra client opts merged)."
  ([s] (public-client s {}))
  ([s opts]
   (core/public-client (merge {:chain :mainnet :transport (core/custom (:request s))} opts))))

(defn wallet-client
  "Wallet client on `:mainnet` over the stub with `account`."
  ([s account] (wallet-client s account {}))
  ([s account opts]
   (core/wallet-client (merge {:chain :mainnet :account account :transport (core/custom (:request s))} opts))))

(defn test-client
  "anvil test client over the stub."
  [s]
  (core/test-client {:transport (core/custom (:request s))}))

(defn block-json
  "A minimal `eth_getBlockByNumber` result for block `n` (hex quantity),
   kebab keys (the custom transport camels them)."
  [n]
  (let [h32 (fn [c] (str "0x" (apply str (repeat 64 c))))]
    {:number n
     :hash (h32 "1")
     :parent-hash (h32 "0")
     :base-fee-per-gas "0x3b9aca00"
     :timestamp "0x1"
     :gas-limit "0x1c9c380"
     :gas-used "0x0"
     :miner zero-address
     :nonce "0x0000000000000000"
     :difficulty "0x0"
     :extra-data "0x"
     :logs-bloom (str "0x" (apply str (repeat 512 "0")))
     :mix-hash (h32 "0")
     :receipts-root (h32 "0")
     :sha3-uncles (h32 "0")
     :size "0x1"
     :state-root (h32 "0")
     :total-difficulty "0x0"
     :transactions []
     :transactions-root (h32 "0")
     :uncles []}))

(defn transfer-log-json
  "A raw `Transfer(WETH -> WETH, 5)` log as the node returns it."
  ([] (transfer-log-json "0x10"))
  ([block-number]
   {:address weth-address
    :block-hash (str "0x" (apply str (repeat 64 "2")))
    :block-number block-number
    :data uint-5
    :log-index "0x0"
    :removed false
    :topics [transfer-topic
             (str "0x000000000000000000000000" (subs weth-address-lower 2))
             (str "0x000000000000000000000000" (subs weth-address-lower 2))]
    :transaction-hash (str "0x" (apply str (repeat 64 "3")))
    :transaction-index "0x0"}))

(defn receipt-json
  "A minimal `eth_getTransactionReceipt` result with one Transfer log."
  []
  {:transaction-hash tx-hash
   :transaction-index "0x0"
   :block-hash (str "0x" (apply str (repeat 64 "2")))
   :block-number "0x10"
   :from weth-address-lower
   :to weth-address-lower
   :cumulative-gas-used "0x5208"
   :gas-used "0x5208"
   :effective-gas-price "0x3b9aca00"
   :contract-address nil
   :logs [(transfer-log-json)]
   :logs-bloom (str "0x" (apply str (repeat 512 "0")))
   :status "0x1"
   :type "0x2"})

(defn counter
  "A stub responder returning successive hex block numbers starting after
   `from` on every call."
  [from]
  (let [n (atom from)]
    (fn [_] (str "0x" (.toString (swap! n inc) 16)))))

(defn sending-responses
  "Canned responses letting a LOCAL account send an EIP-1559 tx through the
   stub (nonce, fees, gas, chain id, broadcast)."
  []
  {"eth_chainId" "0x1"
   "eth_blockNumber" "0x10"
   "eth_getTransactionCount" "0x0"
   "eth_getBlockByNumber" (block-json "0x10")
   "eth_maxPriorityFeePerGas" "0x3b9aca00"
   "eth_gasPrice" "0x3b9aca00"
   "eth_estimateGas" "0x5208"
   "eth_call" uint-1
   "eth_sendRawTransaction" (str "0x" (apply str (repeat 64 "a")))})

;; ---------------------------------------------------------------------------
;; V3 keystore encryption (test-only mirror of core/account-from-keystore!)
;; ---------------------------------------------------------------------------

(defn- hex->buffer [s] (js/Buffer.from (str/replace s #"^0x" "") "hex"))

(defn encrypt-keystore
  "Encrypts a 0x private key into a Web3 Secret Storage v3 map with
   `password`. `kdf` :scrypt (default, n = 4096 for test speed) or :pbkdf2
   (c = 2048). Returns a kebab keystore map (JSON-shaped: `:crypto`,
   `:kdfparams` …). Node crypto + viem keccak256, same primitives as the
   library's decryptor."
  ([private-key password] (encrypt-keystore private-key password :scrypt))
  ([private-key password kdf]
   (let [salt (crypto/randomBytes 32)
         iv (crypto/randomBytes 16)
         dklen 32
         [derived kdf-name kdfparams]
         (case kdf
           :scrypt [(crypto/scryptSync password salt dklen #js {:N 4096 :r 8 :p 1 :maxmem (* 64 1024 1024)})
                    "scrypt" {:dklen dklen :n 4096 :r 8 :p 1 :salt (.toString salt "hex")}]
           :pbkdf2 [(crypto/pbkdf2Sync password salt 2048 dklen "sha256")
                    "pbkdf2" {:dklen dklen :c 2048 :prf "hmac-sha256" :salt (.toString salt "hex")}])
         cipher (crypto/createCipheriv "aes-128-ctr" (.subarray derived 0 16) iv)
         ciphertext (js/Buffer.concat #js [(.update cipher (hex->buffer private-key)) (.final cipher)])
         mac (viem/keccak256 (js/Buffer.concat #js [(.subarray derived 16 32) ciphertext]))]
     {:version 3
      :id "00000000-0000-4000-8000-000000000000"
      :address (str/lower-case (subs (core/private-key->address private-key) 2))
      :crypto {:cipher "aes-128-ctr"
               :ciphertext (.toString ciphertext "hex")
               :cipherparams {:iv (.toString iv "hex")}
               :kdf kdf-name
               :kdfparams kdfparams
               :mac (subs mac 2)}})))
