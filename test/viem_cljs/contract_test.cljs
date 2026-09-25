(ns viem-cljs.contract-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as core]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

(def transfer-event "event Transfer(address indexed from, address indexed to, uint256 value)")

(deftest parse-and-format-abi
  (let [abi (contract/parse-abi tu/erc20-abi)]
    (is (= 3 (count abi)))
    (is (= {:name "balanceOf" :type "function" :state-mutability "view"
            :inputs [{:type "address" :name "owner"}] :outputs [{:type "uint256"}]}
           (first abi)) "kebab JSON ABI")
    (is (= abi (contract/parse-abi abi)) "JSON ABI passes through")
    (is (= "balanceOf(address)" (contract/format-abi-item (first abi))))
    (is (= "balanceOf(address owner)" (contract/format-abi-item (first abi) {:include-name? true})))
    (is (= "Transfer(address,address,uint256)" (contract/format-abi-item transfer-event)) "human-readable in")
    (is (= ["balanceOf(address)" "transfer(address,uint256)" "Transfer(address,address,uint256)"]
           (contract/format-abi tu/erc20-abi)))
    (is (= "Transfer" (:name (contract/parse-abi-item transfer-event))))
    (is (true? (get-in (contract/parse-abi-item transfer-event) [:inputs 0 :indexed])))
    (is (= [{:type "address" :name "owner"} {:type "uint256"}] (contract/parse-abi-parameters "address owner, uint256")))
    (is (= "transfer" (:name (contract/get-abi-item {:abi tu/erc20-abi :name "transfer"}))))
    (let [e (tu/thrown #(contract/parse-abi ["function bad("]))]
      (is (= :viem-cljs/viem-error (util/error-type e)))
      (is (= "UnknownSignatureError" (util/error-name e)) "abitype error (no walk) still wrapped"))))

(deftest function-codecs-round-trip
  (let [data (contract/encode-function-data {:abi tu/erc20-abi :function-name :transfer :args [tu/weth-address 5]})
        decoded (contract/decode-function-data {:abi tu/erc20-abi :data data})]
    (is (str/starts-with? data "0xa9059cbb") "transfer selector")
    (is (= 138 (count data)))
    (is (= "transfer" (:function-name decoded)))
    (is (= [tu/weth-address (utils/big 5)] (:args decoded)) "uint256 decodes as BigInt")
    (is (= (utils/big 5) (contract/decode-function-result {:abi tu/erc20-abi :function-name :balance-of :data tu/uint-5})))
    (is (= tu/uint-1 (contract/encode-function-result {:abi tu/erc20-abi :function-name :transfer :result true})))
    (is (= "0xa9059cbb" (contract/function-selector "transfer(address,uint256)")))
    (is (= tu/transfer-topic (contract/event-selector transfer-event)))
    (testing "struct outputs decode to kebab maps"
      (let [abi ["struct P { uint256 x; address owner; }" "function get() view returns (P)"]
            enc (contract/encode-function-result {:abi abi :function-name "get" :result {:x 7 :owner tu/weth-address}})]
        (is (= {:x (utils/big 7) :owner tu/weth-address}
               (contract/decode-function-result {:abi abi :function-name "get" :data enc})))))))

(deftest parameter-packed-error-and-deploy-codecs
  (let [enc (contract/encode-abi-parameters "address, uint256, bool" [tu/weth-address (utils/parse-ether "1") true])]
    (is (= (+ 2 (* 3 64)) (count enc)))
    (is (= [tu/weth-address (utils/parse-ether "1") true] (contract/decode-abi-parameters "address, uint256, bool" enc)))
    (is (= [(utils/big 5)] (contract/decode-abi-parameters [{:type "uint256"}] tu/uint-5)) "param maps")
    (is (= "0x0001" (contract/encode-packed ["uint8" "uint8"] [0 1])))
    (let [err-abi ["error Nope(uint256 code)"]
          data (contract/encode-error-result {:abi err-abi :error-name "Nope" :args [3]})
          dec (contract/decode-error-result {:abi err-abi :data data})]
      (is (= "Nope" (:error-name dec)))
      (is (= [(utils/big 3)] (:args dec)))
      (is (= "Nope" (get-in dec [:abi-item :name]))))
    (let [std (contract/decode-error-result {:abi [] :data (contract/encode-error-result {:abi ["error Error(string)"] :args ["boom"]})})]
      (is (= "Error" (:error-name std)) "Error(string) decodes without an ABI entry")
      (is (= ["boom"] (:args std))))
    (let [abi ["constructor(uint256 x)"]
          dd (contract/encode-deploy-data {:abi abi :bytecode "0x6001" :args [9]})]
      (is (str/starts-with? dd "0x6001"))
      (is (= [(utils/big 9)] (:args (contract/decode-deploy-data {:abi abi :bytecode "0x6001" :data dd})))))))

(deftest event-codecs
  (let [log (util/->clj (core/opts->js-value (tu/transfer-log-json)))
        dec (contract/decode-event-log {:abi tu/erc20-abi :topics (:topics log) :data (:data log)})]
    (is (= "Transfer" (:event-name dec)))
    (is (= {:from tu/weth-address :to tu/weth-address :value (utils/big 5)} (:args dec)))
    (is (= [tu/transfer-topic (str "0x000000000000000000000000" (subs tu/weth-address-lower 2)) nil]
           (contract/encode-event-topics {:abi tu/erc20-abi :event-name "Transfer" :args {:from tu/weth-address}}))
        "unfiltered indexed params are nil")
    (is (= [tu/transfer-topic nil (str "0x000000000000000000000000" (subs tu/weth-address-lower 2))]
           (contract/encode-event-topics {:abi tu/erc20-abi :event-name :Transfer :args {:to tu/weth-address}}))
        "leading-capital keyword event names")
    (let [parsed (contract/parse-event-logs {:abi tu/erc20-abi :logs [(assoc log :block-number (utils/big 16) :log-index 0)]})]
      (is (= 1 (count parsed)))
      (is (= "Transfer" (:event-name (first parsed))))
      (is (= (utils/big 5) (get-in (first parsed) [:args :value]))))
    (is (= [] (contract/parse-event-logs {:abi tu/erc20-abi :event-name "Approval" :logs [(assoc log :log-index 0)]})))
    (testing "unnamed params decode positionally"
      (let [abi ["event E(uint256 indexed, address)"]
            topics (contract/encode-event-topics {:abi abi :event-name "E" :args [4]})
            d (contract/decode-event-log {:abi abi :topics topics
                                          :data (contract/encode-abi-parameters "address" [tu/weth-address])})]
        (is (= [(utils/big 4) tu/weth-address] (:args d)))))))

(deftest standard-abis-are-data
  (is (vector? contract/erc20-abi))
  (is (some #(= "Transfer" (:name %)) contract/erc20-abi))
  (is (= "view" (:state-mutability (first (filter #(= "balanceOf" (:name %)) contract/erc20-abi)))))
  (is (some #(= "aggregate3" (:name %)) contract/multicall3-abi))
  (is (some #(= "safeTransferFrom" (:name %)) contract/erc721-abi))
  (is (some #(= "balanceOfBatch" (:name %)) contract/erc1155-abi))
  (is (some #(= "convertToAssets" (:name %)) contract/erc4626-abi))
  (is (some #(= "bytes32" (get-in % [:outputs 0 :type])) (filter #(= "name" (:name %)) contract/erc20-abi-bytes32))))

(deftest bind-produces-a-data-contract
  (let [s (tu/stub {})
        c (tu/public-client s)
        weth (contract/bind {:client c :address tu/weth-address :abi tu/erc20-abi})]
    (is (contract/contract? weth))
    (is (not (contract/contract? {:address tu/weth-address})))
    (is (= tu/weth-address (:address weth)))
    (is (= (contract/parse-abi tu/erc20-abi) (:abi weth)) "ABI stored as kebab data")
    (is (identical? c (:client weth)))
    (is (contract/contract? (contract/get-contract {:client c :address tu/weth-address :abi contract/erc20-abi})) "alias + JSON ABI")
    (is (= :missing-client (:reason (ex-data (tu/thrown #(contract/bind {:address tu/weth-address :abi tu/erc20-abi}))))))
    (is (= :missing-address (:reason (ex-data (tu/thrown #(contract/bind {:client c :abi tu/erc20-abi}))))))
    (is (= :missing-abi (:reason (ex-data (tu/thrown #(contract/bind {:client c :address tu/weth-address}))))))))

(deftest reads-through-a-bound-contract
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_call" tu/uint-5
                        "eth_estimateGas" "0x5208"
                        "eth_getLogs" [(tu/transfer-log-json)]
                        "eth_newFilter" "0x9"})
            c (tu/public-client s)
            weth (contract/bind {:client c :address tu/weth-address :abi tu/erc20-abi :account tu/weth-address})]
        (p/let [bal (contract/read! weth {:function-name :balance-of :args [tu/weth-address]})
                bal2 (contract/read! weth {:function-name "balanceOf" :args [tu/weth-address] :block-number 3})
                gas (contract/estimate-gas! weth {:function-name :transfer :args [tu/weth-address 1]})
                evs (contract/events! weth {:event-name "Transfer" :from-block 1})
                flt (contract/create-event-filter! weth {:event-name "Transfer"})
                bad (tu/rejection (contract/read! {:address tu/weth-address} {:function-name :balance-of}))]
          (is (= (utils/big 5) bal))
          (is (= (utils/big 5) bal2))
          (is (= "latest" (second (tu/call-params s "eth_call"))) "first read at latest")
          (is (some (fn [[m ps]] (and (= m "eth_call") (= "0x3" (second ps)))) @(:calls s)) ":block-number through the bound read")
          (is (= (utils/big 21000) gas))
          (is (= tu/weth-address (:from (first (tu/call-params s "eth_estimateGas")))) "bound :account used as sender")
          (is (= 1 (count evs)))
          (is (= (utils/big 5) (get-in (first evs) [:args :value])))
          (is (= "0x9" (:id flt)))
          (is (= "event" (:type flt)))
          (is (some? (:filter flt)) "opaque filter kept")
          (is (= :viem-cljs/argument-error (util/error-type bad))))))))

(deftest writes-and-multicall-through-bound-contracts
  (async done
    (tu/async-test done
      (let [local (core/private-key->account (core/generate-private-key))
            agg (contract/encode-function-result
                 {:abi contract/multicall3-abi :function-name "aggregate3"
                  :result [{:success true :return-data tu/uint-5}
                           {:success false :return-data "0x"}]})
            s (tu/stub (assoc (tu/sending-responses)
                              "eth_call" (fn [[tx _]]
                                           (if (= (str/lower-case tu/multicall3-address) (str/lower-case (:to tx)))
                                             agg
                                             tu/uint-1))))
            w (tu/wallet-client s local)
            weth (contract/bind {:client w :address tu/weth-address :abi tu/erc20-abi})]
        (p/let [sim (contract/simulate! weth {:function-name :transfer :args [tu/weth-address 5]})
                h1 (contract/write! weth (:request sim))
                h2 (contract/write! weth {:function-name :transfer :args [tu/weth-address 5]})
                h3 (contract/simulate-and-write! weth {:function-name :transfer :args [tu/weth-address 5]})
                h4 (contract/deploy! w {:abi ["constructor(uint256 x)"] :bytecode "0x6001" :args [1]})
                mc (contract/multicall! w {:contracts [{:contract weth :function-name :balance-of :args [tu/weth-address]}
                                                       {:address tu/weth-address :abi tu/erc20-abi :function-name :balance-of :args [tu/zero-address]}]})]
          (is (true? (:result sim)))
          (is (= h1 h2 h3 h4))
          (is (= (str "0x" (apply str (repeat 64 "a"))) h1))
          (is (= 2 (count mc)))
          (is (= {:status "success" :result (utils/big 5)} (first mc)))
          (is (= "failure" (:status (second mc))))
          (is (some? (:error (second mc)))))))))

(deftest multicall-without-failure-tolerance-returns-bare-results
  (async done
    (tu/async-test done
      (let [agg (contract/encode-function-result
                 {:abi contract/multicall3-abi :function-name "aggregate3"
                  :result [{:success true :return-data tu/uint-5} {:success true :return-data tu/uint-1}]})
            s (tu/stub {"eth_call" agg})
            c (tu/public-client s)
            weth (contract/bind {:client c :address tu/weth-address :abi tu/erc20-abi})]
        (p/let [r (contract/multicall! c {:contracts [{:contract weth :function-name :balance-of :args [tu/weth-address]}
                                                      {:contract weth :function-name :balance-of :args [tu/zero-address]}]
                                          :allow-failure? false
                                          :block-number 20000000})]
          (is (= [(utils/big 5) (utils/big 1)] r))
          (is (= "0x1312d00" (second (tu/call-params s "eth_call"))))
          (is (= (clojure.string/lower-case tu/multicall3-address)
                 (clojure.string/lower-case (:to (first (tu/call-params s "eth_call")))))
              "routed through the chain's multicall3"))))))
