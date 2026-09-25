(ns viem-cljs.util-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            ["viem" :as viem]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]))

(deftest kebab-camel-roundtrip
  (is (= "blockNumber" (util/kebab->camel :block-number)))
  (is (= "maxFeePerGas" (util/kebab->camel :max-fee-per-gas)))
  (is (= "getURI" (util/kebab->camel :get-u-r-i)) "single-letter segments")
  (is (= "l1Fee" (util/kebab->camel :l1-fee)))
  (is (= "alreadyCamel" (util/kebab->camel "alreadyCamel")) "strings untouched")
  (is (= :block-number (util/camel->kebab "blockNumber")))
  (is (= :sha3-uncles (util/camel->kebab :sha3Uncles)))
  (is (= :max-priority-fee-per-gas (util/camel->kebab "maxPriorityFeePerGas")))
  (is (= :y-parity (util/camel->kebab "yParity")))
  (is (= :_from (util/camel->kebab "_from")) "leading underscore kept")
  (is (= :transfer (util/camel->kebab "Transfer")) "leading capital lowered, no dash")
  (is (= :viem-cljs/x (util/camel->kebab :viem-cljs/x)) "namespaced keywords untouched")
  (testing "inverse for every camelCase identifier viem returns"
    (doseq [s ["blockNumber" "transactionHash" "logsBloom" "sha3Uncles" "l1GasUsed"
               "maxFeePerBlobGas" "yParity" "v" "r" "s" "eip1559" "chainId" "getURI" "_tokenId"]]
      (is (= s (util/kebab->camel (util/camel->kebab s))) s))))

(deftest transform-keys-honours-passthrough
  (is (= {:a-b {:c-d 1} :schema {:xY 2}}
         (util/transform-keys util/camel->kebab #{:schema} {:aB {:cD 1} :schema {:xY 2}})))
  (is (= [{:a-b 1}] (util/transform-keys util/camel->kebab [{:aB 1}]))))

(deftest bigint-helpers
  (is (util/bigint? (js/BigInt 1)))
  (is (not (util/bigint? 1)))
  (is (= (js/BigInt 42) (util/->bigint 42)))
  (is (= (js/BigInt "1000000000000000000") (util/->bigint "1000000000000000000")))
  (is (= (js/BigInt 255) (util/->bigint "0xff")))
  (is (= (js/BigInt -5) (util/->bigint "-5")))
  (is (nil? (util/->bigint nil)))
  (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(util/->bigint 1.5)))))
  (is (= :viem-cljs/argument-error (util/error-type (tu/thrown #(util/->bigint "1.5")))))
  (is (= 42 (util/bigint->number (js/BigInt 42))))
  (is (nil? (util/bigint->number (js/BigInt "1000000000000000000"))) "unsafe -> nil, never lossy")
  (is (= "1000000000000000000" (util/bigint->string (js/BigInt "1000000000000000000"))))
  (testing "BigInt is a first-class CLJS value"
    (is (= (js/BigInt 7) (js/BigInt 7)))
    (is (= {:wei (js/BigInt 7)} {:wei (js/BigInt 7)}))
    (is (= (hash (js/BigInt 7)) (hash (js/BigInt 7))))
    (is (= 2 (count (hash-set (js/BigInt 1) (js/BigInt 2) (js/BigInt 1)))) "hashable in sets")
    (is (= "x" (get {(js/BigInt 5) "x"} (js/BigInt 5))) "usable as a map key")
    (is (= "1000N" (pr-str (js/BigInt 1000))))
    (is (= "{:wei 1000N}" (pr-str {:wei (js/BigInt 1000)})))
    (is (= (js/BigInt 3) (+ (js/BigInt 1) (js/BigInt 2))) "arithmetic works between BigInts")
    (is (< (js/BigInt 1) (js/BigInt 2)))))

(deftest ->js-renders-keys-enums-and-passthrough
  (let [o (util/->js (array-map :block-tag :latest
                                :max-fee-per-gas (js/BigInt 10)
                                :nil-key nil
                                :account "0xabc"
                                :abi [{:type :function :name "balanceOf" :state-mutability :view
                                       :inputs [{:type "address" :name "owner"}] :outputs [{:type "uint256"}]}]
                                :args ["0xdef" 5]
                                :custom {:keep_As-Is 1}
                                :tags #{:a})
                     #{:custom})]
    (is (= ["blockTag" "maxFeePerGas" "account" "abi" "args" "custom" "tags"] (vec (js/Object.keys o))) "order kept, nil dropped")
    (is (= "latest" (util/jsget o "blockTag")))
    (is (= (js/BigInt 10) (util/jsget o "maxFeePerGas")) "BigInt untouched")
    (is (= "view" (util/jsget o "abi" 0 "stateMutability")))
    (is (= "function" (util/jsget o "abi" 0 "type")))
    (is (= "owner" (util/jsget o "abi" 0 "inputs" 0 "name")))
    (is (= ["0xdef" 5] (vec (util/jsget o "args"))))
    (is (= 1 (util/jsget o "custom" "keep_As-Is")) "passthrough subtree keys verbatim")
    (is (= ["a"] (vec (util/jsget o "tags"))))
    (is (nil? (util/->js nil)))
    (let [f (fn [] 1)]
      (is (identical? f (util/jsget (util/->js {:transport f}) "transport")) "functions pass through"))))

(deftest ->clj-keeps-values
  (let [r (util/->clj #js {:blockNumber (js/BigInt 100)
                           :transactionHash "0xAbC"
                           :logs #js [#js {:logIndex 0 :args #js {:from "0x1" :tokenId (js/BigInt 9)}}]
                           :bytes (js/Uint8Array. 2)
                           :status "success"})]
    (is (= (js/BigInt 100) (:block-number r)))
    (is (= "0xAbC" (:transaction-hash r)) "values never re-cased")
    (is (= "success" (:status r)))
    (is (= (js/BigInt 9) (get-in r [:logs 0 :args :token-id])))
    (is (= 0 (get-in r [:logs 0 :log-index])))
    (is (instance? js/Uint8Array (:bytes r)) "typed arrays untouched")))

(deftest viem-errors-are-wrapped
  (let [raw (tu/thrown #(viem/getAddress "0x123"))
        e (util/viem-error raw :get-address)]
    (is (util/js-viem-error? raw))
    (is (not (util/js-viem-error? (js/Error. "plain"))))
    (is (= :viem-cljs/viem-error (util/error-type e)))
    (is (= "InvalidAddressError" (util/error-name e)))
    (is (= "Address \"0x123\" is invalid." (:short-message (util/error-data e))))
    (is (= "viem-cljs InvalidAddressError (getAddress): Address \"0x123\" is invalid." (ex-message e)))
    (is (= 2 (count (:meta-messages (util/error-data e)))))
    (is (str/starts-with? (:version (util/error-data e)) "2."))
    (is (= ["InvalidAddressError"] (util/error-causes e)))
    (is (util/error-cause? e "InvalidAddressError"))
    (is (not (util/error-cause? e "ContractFunctionRevertedError")))
    (is (= :get-address (:action (util/error-data e))))
    (is (identical? raw (:cause (util/error-data e))))
    (is (identical? e (util/viem-error e)) "idempotent on ex-info"))
  (testing "nested cause chain (viem's walk pattern)"
    (let [inner (viem/BaseError. "inner boom" #js {:name "InnerError"})
          outer (viem/BaseError. "outer" #js {:cause inner :name "OuterError"})
          e (util/viem-error outer)]
      (is (= ["OuterError" "InnerError"] (util/error-causes e)))
      (is (= "InnerError" (get-in (util/error-data e) [:root :name])))
      (is (util/error-cause? e "InnerError"))))
  (testing "plain JS errors still wrap"
    (let [e (util/viem-error (js/Error. "ECONNREFUSED"))]
      (is (= :viem-cljs/viem-error (util/error-type e)))
      (is (= "Error" (util/error-name e)))
      (is (= "ECONNREFUSED" (:short-message (util/error-data e)))))))

(deftest error-constructors-and-detection
  (let [cfg (util/config-error "no chain" {:reason :missing-chain})
        arg (util/argument-error "bad" {})]
    (is (= :viem-cljs/config-error (util/error-type cfg)))
    (is (= :missing-chain (:reason (ex-data cfg))))
    (is (= "viem-cljs: no chain" (ex-message cfg)))
    (is (= :viem-cljs/argument-error (util/error-type arg)))
    (is (nil? (util/error-name arg)))
    (is (nil? (util/error-data (ex-info "other" {:type :something/else}))))
    (is (nil? (util/error-data (js/Error. "plain"))))))
