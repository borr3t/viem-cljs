(ns viem-cljs.examples
  "The examples of the README, as functions.

   `main` is the quick start: it encodes a call, decodes a result and a log,
   hashes and signs, and prints what it computed. It needs no node, no
   network and no funds:

     npx shadow-cljs compile examples && node target/examples.js

   The functions below the quick start take a client, so the same code runs
   against a JSON-RPC endpoint (`viem/http`) and against an in-memory node
   (`viem/custom`).

   The code between the README:begin / README:end marks is the code the
   README shows; test/viem_cljs/examples_test.cljs runs it and holds the two
   together."
  (:require [cljs.core.async :as async]
            [promesa.core :as p]
            [viem-cljs.actions :as actions]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as viem]
            [viem-cljs.errors :as errors]
            [viem-cljs.events :as events]
            [viem-cljs.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Quick start: no node, no network
;; ---------------------------------------------------------------------------

;; README:begin quick-start
(def abi
  ["function balanceOf(address owner) view returns (uint256)"
   "function transfer(address to, uint256 amount) returns (bool)"
   "event Transfer(address indexed from, address indexed to, uint256 value)"])

(def account
  ;; account #0 of the Hardhat / anvil development mnemonic. The key is
  ;; public: use it in examples and tests, never to hold funds.
  (viem/private-key->account
   "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"))

(def recipient
  "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")

(def transfer-log
  ;; a Transfer log as a node returns it: topic0 is the hash of the event
  ;; signature, the indexed parameters follow, the value is the data
  {:topics ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
            "0x000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266"
            "0x00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8"]
   :data "0x0000000000000000000000000000000000000000000000000000000001851960"})

(defn quick-start
  "Promise of everything the quick start computes, as a map."
  []
  (p/let [amount    (utils/parse-units "25.5" 6)
          calldata  (contract/encode-function-data {:abi abi
                                                    :function-name :transfer
                                                    :args [recipient amount]})
          call      (contract/decode-function-data {:abi abi :data calldata})
          balance   (contract/decode-function-result {:abi abi
                                                      :function-name :balance-of
                                                      :data (:data transfer-log)})
          log       (contract/decode-event-log (assoc transfer-log :abi abi))
          topic     (utils/event-selector "Transfer(address,address,uint256)")
          digest    (utils/keccak256 (utils/string->hex "hello"))
          signature (viem/sign-message account {:message "hello"})
          signer    (utils/recover-message-address {:message "hello"
                                                    :signature signature})
          valid?    (utils/verify-message {:address (:address (viem/account->map account))
                                           :message "hello"
                                           :signature signature})]
    {:amount amount
     :calldata calldata
     :call call
     :balance balance
     :formatted (utils/format-units balance 6)
     :log log
     :topic topic
     :digest digest
     :signature signature
     :signer signer
     :valid? valid?}))

(def printed
  "The keys of the quick start in the order `main` prints them."
  [:amount :calldata :call :balance :formatted :log :topic :digest :signature
   :signer :valid?])

(defn main [& _]
  (-> (quick-start)
      (p/then (fn [result]
                (doseq [k printed]
                  (println k (pr-str (get result k))))))
      (p/catch (fn [e]
                 (println (errors/summary e))
                 (js/process.exit 1)))))
;; README:end quick-start

;; ---------------------------------------------------------------------------
;; Read a chain
;; ---------------------------------------------------------------------------

;; README:begin read
(defn client
  "A read-only client of Ethereum mainnet over the JSON-RPC endpoint `url`."
  [url]
  (viem/public-client {:chain :mainnet
                       :transport (viem/http url)}))

(defn token-balance!
  "Promise of the balance `owner` holds of the ERC-20 `token`:
   `{:symbol :decimals :raw :amount :block-number}`."
  [client token owner]
  (let [erc20 (contract/bind {:client client
                              :address token
                              :abi contract/erc20-abi})]
    (p/let [block-number (actions/get-block-number! client)
            at           {:block-number block-number}
            symbol       (contract/read! erc20 (assoc at :function-name :symbol))
            decimals     (contract/read! erc20 (assoc at :function-name :decimals))
            raw          (contract/read! erc20 (assoc at
                                                      :function-name :balance-of
                                                      :args [owner]))]
      {:symbol symbol
       :decimals decimals
       :raw raw
       :amount (utils/format-units raw decimals)
       :block-number block-number})))
;; README:end read

;; ---------------------------------------------------------------------------
;; Send a transaction
;; ---------------------------------------------------------------------------

;; README:begin send
(defn wallet
  "A wallet client of `chain` over `url` that signs with the local `account`."
  [chain url account]
  (viem/wallet-client {:chain chain
                       :transport (viem/http url)
                       :account account}))

(defn transfer!
  "Transfers `amount` (a decimal string) of the ERC-20 `token` to `to`:
   simulates the call, sends what was simulated, waits for the receipt.
   Promise of `{:hash :status :block-number :transfers}`."
  [wallet token to amount decimals]
  (let [erc20 (contract/bind {:client wallet
                              :address token
                              :abi contract/erc20-abi})]
    (p/let [simulation (contract/simulate! erc20 {:function-name :transfer
                                                  :args [to (utils/parse-units amount decimals)]})
            hash       (contract/write! erc20 (:request simulation))
            receipt    (actions/wait-for-transaction-receipt! wallet {:hash hash})]
      {:hash hash
       :status (:status receipt)
       :block-number (:block-number receipt)
       :transfers (mapv :args (contract/parse-event-logs {:abi contract/erc20-abi
                                                          :event-name "Transfer"
                                                          :logs (:logs receipt)}))})))
;; README:end send

;; ---------------------------------------------------------------------------
;; Watch events
;; ---------------------------------------------------------------------------

;; README:begin watch
(defn watch-transfers!
  "Watches the Transfer events of the ERC-20 `token` and hands every decoded
   log to `handle`. Returns the channel: (events/stop! ch) ends the watch."
  [client token handle opts]
  (let [erc20 (contract/bind {:client client
                              :address token
                              :abi contract/erc20-abi})
        ch    (events/watch-contract-event! client (assoc opts
                                                          :contract erc20
                                                          :event-name "Transfer"))]
    (async/go-loop []
      (when-some [{:keys [event log error]} (async/<! ch)]
        (case event
          :log (handle log)
          :error (println (errors/summary error)))
        (recur)))
    ch))
;; README:end watch

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

;; README:begin errors
(defn explain
  "What went wrong, as data."
  [e]
  {:category (errors/category e)
   :name (errors/error-name e)
   :action (errors/action e)
   :revert (errors/revert-error-name e)
   :revert-args (errors/revert-args e)
   :reason (errors/revert-reason e)
   :retryable? (errors/retryable? e)
   :retry-hint (errors/retry-hint e)})

(defn try-transfer!
  "Promise of the result of `transfer!`, or of `{:failed (explain e)}`.
   Never rejects."
  [wallet token to amount decimals]
  (-> (transfer! wallet token to amount decimals)
      (p/catch (fn [e] {:failed (explain e)}))))
;; README:end errors
