(ns viem-cljs.actions-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.actions :as actions]
            [viem-cljs.chains :as chains]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as core]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

(deftest public-reads-translate-params-and-results
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" "0x10"
                        "eth_chainId" "0x1"
                        "eth_getBalance" "0xde0b6b3a7640000"
                        "eth_gasPrice" "0x3b9aca00"
                        "eth_estimateGas" "0x5208"
                        "eth_getTransactionCount" "0x7"
                        "eth_getCode" "0x"
                        "eth_getStorageAt" tu/uint-5})
            c (tu/public-client s)]
        (p/let [n (actions/get-block-number! c)
                id (actions/get-chain-id! c)
                bal (actions/get-balance! c {:address tu/weth-address})
                bal5 (actions/get-balance! c {:address tu/weth-address :block-number 5})
                gp (actions/get-gas-price! c)
                gas (actions/estimate-gas! c {:to tu/weth-address :value "1000" :account tu/weth-address})
                cnt (actions/get-transaction-count! c {:address tu/weth-address :block-tag :pending})
                code (actions/get-code! c {:address tu/weth-address})
                slot (actions/get-storage-at! c {:address tu/weth-address :slot "0x0"})]
          (is (= (utils/big 16) n))
          (is (utils/bigint? n))
          (is (= 1 id) "chain id is a number")
          (is (= (utils/parse-ether "1") bal))
          (is (= [tu/weth-address "latest"] (tu/call-params s "eth_getBalance")))
          (is (= (utils/big "1000000000000000000") bal5))
          (is (some #{["eth_getBalance" [tu/weth-address "0x5"]]} @(:calls s)) ":block-number 5 -> hex quantity")
          (is (= (utils/big 1000000000) gp))
          (is (= (utils/big 21000) gas))
          (is (= "0x3e8" (:value (first (tu/call-params s "eth_estimateGas")))) "value coerced and hex-encoded")
          (is (= 7 cnt))
          (is (= [tu/weth-address "pending"] (tu/call-params s "eth_getTransactionCount")) "block tag keyword")
          (is (nil? code) "empty code -> nil")
          (is (= tu/uint-5 slot)))))))

(deftest blocks-receipts-and-fee-history
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_getBlockByNumber" (tu/block-json "0x10")
                        "eth_getBlockByHash" (tu/block-json "0x11")
                        "eth_getTransactionReceipt" (tu/receipt-json)
                        "eth_feeHistory" {:oldest-block "0x10"
                                          :base-fee-per-gas ["0x1" "0x2"]
                                          :gas-used-ratio [0.5]
                                          :reward [["0x3"]]}})
            c (tu/public-client s)]
        (p/let [b (actions/get-block! c)
                b2 (actions/get-block! c {:block-hash (str "0x" (apply str (repeat 64 "1"))) :include-transactions? true})
                r (actions/get-transaction-receipt! c {:hash tu/tx-hash})
                fh (actions/get-fee-history! c {:block-count 2 :reward-percentiles [50] :block-tag :latest})]
          (is (= (utils/big 16) (:number b)))
          (is (= (utils/big 1000000000) (:base-fee-per-gas b)))
          (is (= (utils/big 1) (:timestamp b)))
          (is (= [] (:transactions b)))
          (is (= ["latest" false] (tu/call-params s "eth_getBlockByNumber")))
          (is (= (utils/big 17) (:number b2)))
          (is (= true (second (tu/call-params s "eth_getBlockByHash"))) "include-transactions? stripped and passed")
          (is (= "success" (:status r)) "status string")
          (is (= (utils/big 21000) (:gas-used r)))
          (is (= tu/weth-address-lower (:from r)))
          (is (nil? (:contract-address r)))
          (is (= 1 (count (:logs r))))
          (is (= 0 (:log-index (first (:logs r)))) "index is a number")
          (is (= (utils/big 16) (:block-number (first (:logs r)))) "nested BigInt")
          (is (= [(utils/big 1) (utils/big 2)] (:base-fee-per-gas fh)))
          (is (= (utils/big 16) (:oldest-block fh)))
          (is (= [[(utils/big 3)]] (:reward fh)))
          (is (= ["0x2" "latest" [50]] (tu/call-params s "eth_feeHistory"))))))))

(deftest contract-reads-and-logs
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_call" tu/uint-5
                        "eth_getLogs" [(tu/transfer-log-json)]})
            c (tu/public-client s)
            abi (contract/parse-abi tu/erc20-abi)]
        (p/let [v (actions/read-contract! c {:abi tu/erc20-abi :address tu/weth-address
                                              :function-name :balance-of :args [tu/weth-address]})
                v2 (actions/read-contract! c {:abi abi :address tu/weth-address
                                               :function-name "balanceOf" :args [tu/weth-address]})
                logs (actions/get-logs! c {:address tu/weth-address
                                           :event "event Transfer(address indexed from, address indexed to, uint256 value)"
                                           :from-block 1 :to-block :latest})
                raw-logs (actions/get-logs! c {:address tu/weth-address})
                call (actions/call! c {:to tu/weth-address :data "0x18160ddd"})]
          (is (= (utils/big 5) v))
          (is (= (utils/big 5) v2) "kebab JSON ABI accepted")
          (is (str/starts-with? (:data (first (tu/call-params s "eth_call"))) "0x70a08231") "balanceOf selector")
          (is (= 1 (count logs)))
          (is (= "Transfer" (:event-name (first logs))))
          (is (= {:from tu/weth-address :to tu/weth-address :value (utils/big 5)} (:args (first logs))))
          (is (= "0x1" (:from-block (first (tu/call-params s "eth_getLogs")))))
          (is (= "latest" (:to-block (first (tu/call-params s "eth_getLogs")))))
          (is (nil? (:event-name (first raw-logs))) "undecoded without :event")
          (is (= tu/uint-5 (:data call))))))))

(deftest rejections-are-wrapped-with-cause-chains
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_call" "0x"
                        "eth_estimateGas" (fn [_] (throw (ex-info "insufficient funds for gas * price + value"
                                                                  {:code -32000 :message "insufficient funds for gas * price + value"})))})
            c (tu/public-client s)]
        (p/let [e1 (tu/rejection (actions/get-gas-price! c))
                e2 (tu/rejection (actions/read-contract! c {:abi tu/erc20-abi :address tu/weth-address
                                                            :function-name :balance-of :args [tu/weth-address]}))
                e3 (tu/rejection (actions/read-contract! c {:abi tu/erc20-abi :address "0x123"
                                                            :function-name :balance-of :args [tu/weth-address]}))
                e4 (tu/rejection (actions/estimate-gas! c {:to tu/weth-address :value 1 :account tu/weth-address}))
                e5 (tu/rejection (actions/get-balance! c {:address tu/weth-address :block-number 1.5}))
                e6 (tu/rejection (actions/get-balance! "not a client" {:address tu/weth-address}))]
          (is (= "MethodNotFoundRpcError" (util/error-name e1)))
          (is (= :get-gas-price (:action (util/error-data e1))))
          (is (= "ContractFunctionExecutionError" (util/error-name e2)))
          (is (util/error-cause? e2 "ContractFunctionZeroDataError") "walk finds the zero-data cause")
          (is (= ["ContractFunctionExecutionError" "ContractFunctionZeroDataError" "AbiDecodingZeroDataError"]
                 (util/error-causes e2)))
          (is (= "ContractFunctionExecutionError" (util/error-name e3)))
          (is (util/error-cause? e3 "InvalidAddressError") "pre-request validation, no RPC call")
          (is (= "EstimateGasExecutionError" (util/error-name e4)))
          (is (util/error-cause? e4 "InsufficientFundsError") "node message classified")
          (is (= -32000 (:code (get-in (util/error-data e4) [:root]))) )
          (is (= :viem-cljs/argument-error (util/error-type e5)) "->bigint coercion failure rejects")
          (is (= :viem-cljs/argument-error (util/error-type e6))))))))

(deftest wallet-signing-actions
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_chainId" "0x1"})
            local (core/private-key->account (core/generate-private-key))
            address (:address (core/account->map local))
            w (tu/wallet-client s local)
            td {:domain {:name "T" :version "1" :chain-id 1}
                :types {:Mail [{:name "body" :type "string"}]}
                :primary-type "Mail"
                :message {:body "yo"}}]
        (p/let [sig (actions/sign-message! w {:message "hi"})
                ok (utils/verify-message {:address address :message "hi" :signature sig})
                tsig (actions/sign-typed-data! w td)
                signer (utils/recover-typed-data-address (assoc td :address address :signature tsig))
                stx (actions/sign-transaction! w {:to tu/weth-address :value 1 :gas 21000 :nonce 0
                                                  :max-fee-per-gas 10 :max-priority-fee-per-gas 1 :chain-id 1})
                no-acct (tu/rejection (actions/sign-message! (tu/public-client s) {:message "hi"}))]
          (is (= 132 (count sig)))
          (is (true? ok))
          (is (= address signer))
          (is (str/starts-with? stx "0x02"))
          (is (= ["eth_chainId"] (tu/calls s)) "only sign-transaction! asserts the chain id; message/typed-data signing never touches the transport")
          (is (= "AccountNotFoundError" (util/error-name no-acct))))))))

(deftest send-and-prepare-flows
  (async done
    (tu/async-test done
      (let [s (tu/stub (tu/sending-responses))
            local (core/private-key->account (core/generate-private-key))
            w (tu/wallet-client s local)]
        (p/let [h (actions/send-transaction! w {:to tu/weth-address :value "1"})
                req (actions/prepare-transaction-request! w {:to tu/weth-address :value 2})
                h2 (actions/send-transaction! w req)
                sim (actions/simulate-contract! w {:abi tu/erc20-abi :address tu/weth-address
                                                   :function-name :transfer :args [tu/weth-address 5]})
                h3 (actions/write-contract! w (:request sim))
                h4 (actions/write-contract! w {:abi tu/erc20-abi :address tu/weth-address
                                               :function-name :transfer :args [tu/weth-address 5]})]
          (is (= (str "0x" (apply str (repeat 64 "a"))) h))
          (is (str/starts-with? (first (tu/call-params s "eth_sendRawTransaction")) "0x02") "local account broadcasts a signed eip1559 tx")
          (is (= "eip1559" (:type req)))
          (is (= (utils/big 21000) (:gas req)))
          (is (utils/bigint? (:max-fee-per-gas req)))
          (is (= 0 (:nonce req)) "nonce is a number")
          (is (core/account-object? (:account req)) "opaque account re-attached")
          (is (= h h2) "prepared request feeds send-transaction!")
          (is (= true (:result sim)) "decoded bool result")
          (is (= "transfer" (get-in sim [:request :function-name])))
          (is (core/account-object? (get-in sim [:request :account])))
          (is (= h h3) "simulate -> write pair")
          (is (= h h4))
          (is (= 4 (count (filter #{"eth_sendRawTransaction"} (tu/calls s))))))))))

(deftest wait-for-receipt-and-replacement-callback
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_getTransactionReceipt" (tu/receipt-json)
                        "eth_getBlockByNumber" (tu/block-json "0x11")})
            c (tu/public-client s {:polling-interval 5})
            replaced (atom nil)]
        (p/let [r (actions/wait-for-transaction-receipt! c {:hash tu/tx-hash :polling-interval 5
                                                            :on-replaced (fn [x] (reset! replaced x))})]
          (is (= "success" (:status r)))
          (is (= (utils/big 16) (:block-number r)))
          (is (nil? @replaced)))))))

(deftest test-actions-translate-to-anvil-rpc
  (async done
    (tu/async-test done
      (let [s (tu/stub {"anvil_mine" nil "anvil_setBalance" nil "evm_setAutomine" nil "anvil_setRpcUrl" nil
                        "anvil_setLoggingEnabled" nil "evm_increaseTime" "0x5" "evm_setNextBlockTimestamp" nil
                        "evm_snapshot" "0x1" "evm_revert" true "anvil_impersonateAccount" nil
                        "anvil_stopImpersonatingAccount" nil "anvil_setCode" nil "anvil_setNonce" nil
                        "anvil_setStorageAt" nil "anvil_getAutomine" true "anvil_reset" nil
                        "anvil_dumpState" "0xabcd" "anvil_setNextBlockBaseFeePerGas" nil})
            t (tu/test-client s)]
        (p/let [_ (actions/mine! t {:blocks 2})
                _ (actions/set-balance! t {:address tu/weth-address :value (utils/parse-ether "1")})
                _ (actions/set-automine! t true)
                _ (actions/set-rpc-url! t "http://x")
                _ (actions/set-logging-enabled! t false)
                inc-r (actions/increase-time! t {:seconds 5})
                _ (actions/set-next-block-timestamp! t {:timestamp 100})
                snap (actions/snapshot! t)
                _ (actions/revert! t {:id snap})
                _ (actions/impersonate-account! t {:address tu/weth-address})
                _ (actions/stop-impersonating-account! t {:address tu/weth-address})
                _ (actions/set-code! t {:address tu/weth-address :bytecode "0x60006000"})
                _ (actions/set-nonce! t {:address tu/weth-address :nonce 3})
                _ (actions/set-storage-at! t {:address tu/weth-address :index 1 :value tu/uint-5})
                auto (actions/get-automine! t)
                _ (actions/reset! t {:block-number 100 :json-rpc-url "http://y"})
                st (actions/dump-state! t)
                _ (actions/set-next-block-base-fee-per-gas! t {:base-fee-per-gas "7"})]
          (is (= ["0x2" "0x0"] (tu/call-params s "anvil_mine")))
          (is (= [tu/weth-address "0xde0b6b3a7640000"] (tu/call-params s "anvil_setBalance")))
          (is (= [true] (tu/call-params s "evm_setAutomine")))
          (is (= ["http://x"] (tu/call-params s "anvil_setRpcUrl")))
          (is (= [false] (tu/call-params s "anvil_setLoggingEnabled")))
          (is (= ["0x5"] (tu/call-params s "evm_increaseTime")))
          (is (= "0x5" inc-r))
          (is (= ["0x64"] (tu/call-params s "evm_setNextBlockTimestamp")))
          (is (= "0x1" snap))
          (is (= ["0x1"] (tu/call-params s "evm_revert")))
          (is (= [tu/weth-address] (tu/call-params s "anvil_impersonateAccount")))
          (is (= [tu/weth-address "0x60006000"] (tu/call-params s "anvil_setCode")))
          (is (= [tu/weth-address "0x3"] (tu/call-params s "anvil_setNonce")) "viem hex-encodes the nonce")
          (is (= [tu/weth-address "0x1" tu/uint-5] (tu/call-params s "anvil_setStorageAt")))
          (is (true? auto))
          (is (= [{:forking {:block-number 100 :json-rpc-url "http://y"}}] (tu/call-params s "anvil_reset")))
          (is (= "0xabcd" st))
          (is (= ["0x7"] (tu/call-params s "anvil_setNextBlockBaseFeePerGas"))))))))

(deftest ens-requires-a-supporting-chain
  (async done
    (tu/async-test done
      (let [s (tu/stub {})
            c (core/public-client {:chain :base :transport (core/custom (:request s))})]
        (p/let [e (tu/rejection (actions/get-ens-address! c {:name (utils/normalize "vitalik.eth")}))]
          (is (= "ChainDoesNotSupportContract" (util/error-name e)))
          (is (= :get-ens-address (:action (util/error-data e))))
          (is (= [] (tu/calls s)) "rejected before any request")
          (is (some? (chains/contracts :mainnet))))))))

(deftest transactions-counts-and-fee-estimates
  (async done
    (tu/async-test done
      (let [tx {:hash tu/tx-hash :nonce "0x5" :block-hash (str "0x" (apply str (repeat 64 "2"))) :block-number "0x10"
                :transaction-index "0x0" :from tu/weth-address-lower :to tu/weth-address-lower :value "0x1"
                :gas "0x5208" :gas-price "0x3b9aca00" :max-fee-per-gas "0x3b9aca00" :max-priority-fee-per-gas "0x1"
                :input "0x" :type "0x2" :chain-id "0x1" :access-list [] :v "0x1" :r "0x1" :s "0x1" :y-parity "0x1"}
            s (tu/stub {"eth_getTransactionByHash" tx
                        "eth_getBlockTransactionCountByNumber" "0x3"
                        "eth_getBlockByNumber" (tu/block-json "0x10")
                        "eth_maxPriorityFeePerGas" "0x1"
                        "eth_gasPrice" "0x3b9aca00"
                        "eth_blockNumber" "0x14"
                        "eth_createAccessList" {:access-list [{:address tu/weth-address-lower :storage-keys [tu/zero-hash]}] :gas-used "0x5208"}})
            c (tu/public-client s)]
        (p/let [t (actions/get-transaction! c {:hash tu/tx-hash})
                cnt (actions/get-block-transaction-count! c {:block-tag :latest})
                fees (actions/estimate-fees-per-gas! c)
                legacy (actions/estimate-fees-per-gas! c {:type :legacy})
                conf (actions/get-transaction-confirmations! c {:hash tu/tx-hash})
                al (actions/create-access-list! c {:to tu/weth-address :data "0x" :account tu/weth-address})]
          (is (= "eip1559" (:type t)))
          (is (= 5 (:nonce t)) "nonce is a number")
          (is (= (utils/big 1) (:value t)))
          (is (= (utils/big 16) (:block-number t)))
          (is (= 1 (:chain-id t)))
          (is (= (utils/big 1) (:v t)) "v stays BigInt")
          (is (= 1 (:y-parity t)))
          (is (= 3 cnt))
          (is (utils/bigint? (:max-fee-per-gas fees)))
          (is (= (utils/big 1) (:max-priority-fee-per-gas fees)))
          (is (= {:gas-price (utils/big 1200000000)} (select-keys legacy [:gas-price])) "viem applies the 1.2 base fee multiplier")
          (is (= (utils/big 5) conf) "20 - 16 + 1")
          (is (= (utils/big 21000) (:gas-used al)))
          (is (= [tu/zero-hash] (get-in al [:access-list 0 :storage-keys]))))))))

(deftest receipt-timeout-is-a-wrapped-viem-error
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_getTransactionReceipt" nil})
            c (tu/public-client s {:polling-interval 5})]
        (p/let [e (tu/rejection (actions/wait-for-transaction-receipt! c {:hash tu/tx-hash :polling-interval 5 :timeout 40}))]
          (is (= "WaitForTransactionReceiptTimeoutError" (util/error-name e)))
          (is (= :wait-for-transaction-receipt (:action (util/error-data e))))
          (is (str/includes? (:short-message (util/error-data e)) tu/tx-hash)))))))

(deftest eip7702-authorization-round-trip
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_getTransactionCount" "0x2" "eth_chainId" "0x1"})
            local (core/private-key->account (core/generate-private-key))
            address (:address (core/account->map local))
            w (tu/wallet-client s local)]
        (p/let [auth (actions/sign-authorization! w {:contract-address tu/weth-address})
                ok (utils/verify-authorization {:address address :authorization auth})
                signer (utils/recover-authorization-address {:authorization auth})
                auth2 (core/sign-authorization local {:contract-address tu/weth-address :chain-id 1 :nonce 2})]
          (is (= tu/weth-address (:address auth)))
          (is (= 1 (:chain-id auth)))
          (is (= 2 (:nonce auth)) "nonce fetched from the chain")
          (is (every? #(contains? auth %) [:r :s :y-parity]))
          (is (true? ok))
          (is (= address signer))
          (is (= (select-keys auth [:r :s :y-parity]) (select-keys auth2 [:r :s :y-parity])) "wallet action and local signer agree"))))))
