(ns viem-cljs.errors-test
  "viem-cljs.errors: accessors, cause walking and the classification
   heuristics over (a) viem error instances constructed directly from the
   census classes, (b) plain / Node-style errors, (c) viem-cljs argument and
   config errors, and (d) one rejection produced end to end through the stub
   transport. Network-free."
  (:require ["viem" :as viem]
            [cljs.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [viem-cljs.actions :as actions]
            [viem-cljs.errors :as errors]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

(defn- node-error
  "A plain Error carrying an RPC `code`, as a node / stub would produce."
  [message code]
  (let [e (js/Error. message)]
    (set! (.-code e) code)
    e))

(def insufficient
  "EstimateGasExecutionError > InsufficientFundsError > Error(-32000)."
  (viem/EstimateGasExecutionError.
   (viem/InsufficientFundsError. #js {:cause (node-error "insufficient funds for gas * price + value" -32000)})
   #js {}))

(def rejected (viem/UserRejectedRequestError. (js/Error. "User denied")))

(def http-429 (viem/HttpRequestError. #js {:url "http://rpc.example" :status 429 :body #js {} :details "Too Many Requests"}))

(def http-down (viem/HttpRequestError. #js {:url "http://rpc.example" :details "fetch failed"}))

(def limit
  (viem/LimitExceededRpcError.
   (viem/RpcRequestError. #js {:body #js {} :url "http://rpc.example"
                               :error #js {:code -32005 :message "request exceeds defined limit"}})))

(def timeout (viem/TimeoutError. #js {:body #js {:method "eth_call"} :url "http://rpc.example"}))

(def receipt-timeout (viem/WaitForTransactionReceiptTimeoutError. #js {:hash tu/tx-hash}))

(def nonce-low (viem/NonceTooLowError. #js {:cause (node-error "nonce too low" -32000)}))

(def replacement (viem/TransactionRejectedRpcError. (node-error "replacement transaction underpriced" -32003)))

(def not-found (viem/TransactionReceiptNotFoundError. #js {:hash tu/tx-hash}))

(def internal (viem/InternalRpcError. (node-error "internal error" -32603)))

(def method-missing (viem/MethodNotFoundRpcError. (node-error "Method not found" -32601)))

(def revert-abi (viem/parseAbi #js ["error TooBig(uint256 value, uint256 limit)" "function store(uint256 v)"]))

(def reverted
  "ContractFunctionExecutionError > ContractFunctionRevertedError (custom error TooBig(7, 5))."
  (let [data (viem/encodeErrorResult #js {:abi revert-abi :errorName "TooBig" :args #js [(js/BigInt 7) (js/BigInt 5)]})
        inner (viem/ContractFunctionRevertedError. #js {:abi revert-abi :data data :functionName "store"})]
    (viem/ContractFunctionExecutionError. inner #js {:abi revert-abi :functionName "store" :args #js [(js/BigInt 7)]
                                                     :contractAddress tu/weth-address})))

(def reverted-reason
  "ContractFunctionRevertedError from Error(string) revert data."
  (let [data (viem/encodeErrorResult #js {:abi (viem/parseAbi #js ["error Error(string message)"])
                                          :errorName "Error" :args #js ["nope"]})]
    (viem/ContractFunctionRevertedError. #js {:abi revert-abi :data data :functionName "store"})))

(def plain-refused (js/Error. "connect ECONNREFUSED 127.0.0.1:8545"))

(def arg-error (util/argument-error "bad key" {:reason :bad-key}))
(def cfg-error (util/config-error "no transport" {:reason :missing-transport}))

(deftest accessors-on-wrapped-and-raw-errors
  (let [w (errors/wrap insufficient :estimate-gas)]
    (testing "wrapped ex-info"
      (is (errors/error? w))
      (is (errors/viem-error? w))
      (is (= :viem-cljs/viem-error (errors/error-type w)))
      (is (= "EstimateGasExecutionError" (errors/error-name w)))
      (is (= :estimate-gas (errors/action w)))
      (is (= ["EstimateGasExecutionError" "InsufficientFundsError" "Error"] (errors/causes w)))
      (is (= 3 (count (errors/cause-chain w))))
      (is (= "Error" (:name (errors/root w))))
      (is (= -32000 (errors/rpc-code w)) "code found on the root plain error")
      (is (nil? (errors/http-status w)))
      (is (string? (errors/short-message w)))
      (is (string? (errors/message w)))
      (is (string? (errors/version w)))
      (is (identical? w (errors/wrap w)) "wrap is idempotent"))
    (testing "raw viem error, wrapped on the fly"
      (is (errors/error? insufficient))
      (is (= "EstimateGasExecutionError" (errors/error-name insufficient)))
      (is (= (errors/causes w) (errors/causes insufficient)))
      (is (= -32000 (errors/rpc-code insufficient)))
      (is (errors/insufficient-funds? insufficient)))
    (testing "non-errors"
      (is (not (errors/error? nil)))
      (is (not (errors/error? "boom")))
      (is (not (errors/error? (ex-info "other" {:type :something/else}))))
      (is (nil? (errors/error-name nil)))
      (is (nil? (errors/message "boom")))
      (is (= [] (errors/cause-chain nil)))
      (is (= :unknown (errors/category nil)))
      (is (= :unknown (errors/category "boom")))
      (is (not (errors/retryable? nil)))
      (is (nil? (errors/summary nil))))))

(deftest cause-walking
  (let [w (errors/wrap limit)]
    (is (= ["LimitExceededRpcError" "RpcRequestError" "Object"] (errors/causes w)) "the root is the raw JSON-RPC error object")
    (is (= "LimitExceededRpcError" (:name (errors/walk-cause w #(= -32005 (:code %))))) "walk-cause by code, outermost first")
    (is (= "RpcRequestError" (:name (errors/walk-cause w #(and (= -32005 (:code %)) (string? (:url %)))))))
    (is (= "RpcRequestError" (:name (errors/walk-cause w #(string? (:url %))))))
    (is (nil? (errors/walk-cause w #(= "Nope" (:name %)))))
    (is (errors/cause? w "RpcRequestError"))
    (is (errors/cause? w #{"Nope" "LimitExceededRpcError"}) "set of names")
    (is (not (errors/cause? w "TimeoutError")))
    (is (= "LimitExceededRpcError" (:name (errors/cause-named w #{"LimitExceededRpcError" "RpcRequestError"}))) "outermost first")
    (is (= -32005 (errors/rpc-code w)))
    (is (= "http://rpc.example" (errors/url w)))))

(deftest revert-accessors
  (let [w (errors/wrap reverted :write-contract)]
    (is (= ["ContractFunctionExecutionError" "ContractFunctionRevertedError"] (errors/causes w)))
    (is (errors/reverted? w))
    (is (= "TooBig" (errors/revert-error-name w)))
    (is (= [(utils/big 7) (utils/big 5)] (errors/revert-args w)) "BigInt args survive the summary conversion")
    (is (= "TooBig" (get-in (errors/revert-data w) [:abi-item :name])))
    (is (nil? (errors/revert-reason w)) "custom error has no reason string")
    (is (= :reverted (errors/category w)))
    (is (not (errors/retryable? w)))
    (is (nil? (errors/retry-hint w)))
    (is (re-find #"revert=TooBig" (errors/summary w)))
    (is (re-find #"action=write-contract" (errors/summary w))))
  (let [w (errors/wrap reverted-reason)]
    (is (= "nope" (errors/revert-reason w)) "Error(string) reason")
    (is (= "Error" (errors/revert-error-name w)))
    (is (= :reverted (errors/category w))))
  (is (nil? (errors/revert-data (errors/wrap insufficient)))))

(deftest classification-and-retry-advice
  (testing "user rejection"
    (is (errors/user-rejected? rejected))
    (is (= 4001 (errors/rpc-code rejected)))
    (is (= :user-rejected (errors/category rejected)))
    (is (not (errors/retryable? rejected))))
  (testing "insufficient funds"
    (is (errors/insufficient-funds? insufficient))
    (is (= :insufficient-funds (errors/category insufficient)))
    (is (not (errors/retryable? insufficient))))
  (testing "rate limits"
    (is (errors/rate-limited? http-429))
    (is (= 429 (errors/http-status http-429)))
    (is (= :rate-limited (errors/category http-429)))
    (is (= :backoff (errors/retry-hint http-429)))
    (is (errors/retryable? http-429))
    (is (errors/rate-limited? limit))
    (is (= :rate-limited (errors/category limit)))
    (is (not (errors/connection-error? http-429)) "a 4xx is not a connection problem"))
  (testing "timeouts"
    (is (errors/timeout? timeout))
    (is (= :timeout (errors/category timeout)))
    (is (= :requery (errors/retry-hint timeout)))
    (is (errors/timeout? receipt-timeout))
    (is (= :timeout (errors/category receipt-timeout)))
    (is (errors/retryable? receipt-timeout)))
  (testing "nonce"
    (is (errors/nonce-too-low? nonce-low))
    (is (errors/nonce-error? nonce-low))
    (is (not (errors/nonce-too-high? nonce-low)))
    (is (= :nonce (errors/category nonce-low)))
    (is (= :refresh-nonce (errors/retry-hint nonce-low)))
    (is (errors/retryable? nonce-low)))
  (testing "replacement / fees"
    (is (errors/replacement-underpriced? replacement))
    (is (errors/fee-too-low? replacement))
    (is (= -32003 (errors/rpc-code replacement)))
    (is (= :fee (errors/category replacement)))
    (is (= :bump-fee (errors/retry-hint replacement)))
    (is (errors/retryable? replacement)))
  (testing "connection"
    (is (errors/connection-error? http-down) "HttpRequestError without status")
    (is (nil? (errors/http-status http-down)))
    (is (= :connection (errors/category http-down)))
    (is (errors/connection-error? plain-refused) "Node ECONNREFUSED text")
    (is (= "Error" (errors/error-name plain-refused)))
    (is (= :connection (errors/category plain-refused)))
    (is (= :backoff (errors/retry-hint plain-refused)))
    (is (errors/retryable? plain-refused)))
  (testing "transient rpc / not found / unsupported"
    (is (errors/transient-rpc-error? internal))
    (is (= :transient-rpc (errors/category internal)))
    (is (errors/retryable? internal))
    (is (errors/not-found? not-found))
    (is (= :not-found (errors/category not-found)))
    (is (not (errors/retryable? not-found)))
    (is (errors/unsupported-method? method-missing))
    (is (= -32601 (errors/rpc-code method-missing)))
    (is (= :unsupported-method (errors/category method-missing)))
    (is (not (errors/retryable? method-missing))))
  (testing "viem-cljs pre-flight errors"
    (is (errors/argument-error? arg-error))
    (is (= :argument (errors/category arg-error)))
    (is (= :bad-key (errors/reason arg-error)))
    (is (nil? (errors/error-name arg-error)))
    (is (= "viem-cljs: bad key" (errors/short-message arg-error)))
    (is (errors/config-error? cfg-error))
    (is (= :config (errors/category cfg-error)))
    (is (= :missing-transport (errors/reason cfg-error)))
    (is (not (errors/retryable? arg-error)))
    (is (not (errors/retryable? cfg-error)))
    (is (re-find #"argument-error \[argument\]" (errors/summary arg-error))))
  (testing "abi and other viem errors"
    (let [abi-e (tu/thrown #(viem/encodeAbiParameters #js [#js {:type "uint256"} #js {:type "uint256"}] #js [(js/BigInt 1)]))
          addr-e (tu/thrown #(viem/getAddress "0x123"))]
      (is (errors/abi-error? abi-e))
      (is (= :abi (errors/category abi-e)))
      (is (= :viem (errors/category addr-e)) "InvalidAddressError falls into the generic viem bucket")
      (is (= :chain (errors/category (js/Error. "client chain not configured. universalResolverAddress is required."))) "the ENS actions' plain chain error")
      (is (errors/chain-error? (viem/ChainMismatchError. #js {:chain #js {:id 1 :name "Ethereum"} :currentChainId 10})))
      (is (not (errors/retryable? addr-e)))
      (is (re-find #"InvalidAddressError \[viem\]" (errors/summary addr-e))))))

(deftest end-to-end-through-the-stub
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_call" "0x"
                        "eth_estimateGas" (fn [_] (throw (ex-info "insufficient funds for gas * price + value"
                                                                  {:code -32000 :message "insufficient funds for gas * price + value"})))})
            c (tu/public-client s)]
        (p/let [e1 (tu/rejection (actions/get-gas-price! c))
                e2 (tu/rejection (actions/read-contract! c {:abi tu/erc20-abi :address tu/weth-address
                                                            :function-name :balance-of :args [tu/weth-address]}))
                e3 (tu/rejection (actions/estimate-gas! c {:to tu/weth-address :value 1 :account tu/weth-address}))
                e4 (tu/rejection (actions/get-balance! c {:address tu/weth-address :block-number 1.5}))]
          (is (= :unsupported-method (errors/category e1)))
          (is (= -32601 (errors/rpc-code e1)))
          (is (= :get-gas-price (errors/action e1)))
          (is (= :zero-data (errors/category e2)))
          (is (errors/zero-data? e2))
          (is (not (errors/retryable? e2)))
          (is (= :insufficient-funds (errors/category e3)))
          (is (= -32000 (errors/rpc-code e3)))
          (is (= :argument (errors/category e4)))
          (is (= "MethodNotFoundRpcError [unsupported-method] code=-32601 action=get-gas-price: "
                 (subs (errors/summary e1) 0 (count "MethodNotFoundRpcError [unsupported-method] code=-32601 action=get-gas-price: ")))))))))

(deftest secrets-and-redaction
  (let [s (util/secret "0x" )]
    (is (util/secret? s))
    (is (= "0x" (util/reveal s)))
    (is (= "plain" (util/reveal "plain")))
    (is (identical? s (util/secret s)) "idempotent")
    (is (nil? (util/secret nil)))
    (is (= "#viem-cljs/secret <redacted>" (pr-str s)))
    (is (= "#viem-cljs/secret <redacted>" (str s)))
    (is (= {:private-key "<redacted>" :mnemonic "<redacted>" :password "<redacted>"
            :address tu/dev-address-0 :nested {:passphrase "<redacted>" :keep 1}
            :list [{:secret "<redacted>"} "<redacted>" 3] :none nil}
           (util/redact {:private-key "0xabc" :mnemonic "a b c" :password "pw"
                         :address tu/dev-address-0 :nested {:passphrase "x" :keep 1}
                         :list [{:secret 1} s 3] :none nil})))
    (is (= {:rpc-token "<redacted>"} (util/redact {:rpc-token "t"} #{:rpc-token})) "extra keys")
    (is (= "<redacted>" (util/redact s)))
    (is (= 42 (util/redact 42)))))
