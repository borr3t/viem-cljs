(ns viem-cljs.events-test
  (:require [cljs.core.async :as async]
            [cljs.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [viem-cljs.contract :as contract]
            [viem-cljs.events :as events]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

(defn- take!
  "Promise of the next value on `ch` (nil when closed), with a timeout."
  ([ch] (take! ch 2000))
  ([ch ms]
   (p/create (fn [resolve _]
               (let [t (js/setTimeout #(resolve ::timeout) ms)]
                 (async/take! ch (fn [v] (js/clearTimeout t) (resolve v))))))))

(deftest watch-block-number-emits-and-stops
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-block-number! c {:emit-on-begin? true :poll? true :polling-interval 5})]
        (p/let [e1 (take! ch)
                e2 (take! ch)
                _ (events/stop! ch)
                closed (take! ch)]
          (is (= :block-number (:event e1)))
          (is (= (utils/big 16) (:block-number e1)) "emit-on-begin: first poll")
          (is (nil? (:prev e1)))
          (is (= (utils/big 17) (:block-number e2)))
          (is (= (utils/big 16) (:prev e2)))
          (is (nil? closed) "stop! closes the channel")
          (is (not (events/watching? ch)))
          (let [n (count (tu/calls s))]
            (p/let [_ (tu/sleep 40)]
              (is (= n (count (tu/calls s))) "no polling after stop!"))))))))

(deftest closing-the-channel-unsubscribes
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-block-number! c {:emit-on-begin? true :poll? true :polling-interval 5})]
        (p/let [_ (take! ch)]
          (is (events/watching? ch))
          (async/close! ch)
          (p/let [_ (tu/sleep 40)
                  n (count (tu/calls s))
                  _ (tu/sleep 40)]
            (is (not (events/watching? ch)) "producer noticed the closed channel and unwatched")
            (is (= n (count (tu/calls s))) "polling stopped")))))))

(deftest watch-event-decodes-logs-one-per-put
  (async done
    (tu/async-test done
      (let [batch [(tu/transfer-log-json "0x10") (tu/transfer-log-json "0x11")]
            s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newFilter" "0x1"
                        "eth_getFilterChanges" batch
                        "eth_uninstallFilter" true})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-event! c {:address tu/weth-address
                                       :event "event Transfer(address indexed from, address indexed to, uint256 value)"
                                       :poll? true :polling-interval 5 :buf 8})]
        (p/let [e1 (take! ch)
                e2 (take! ch)]
          (is (= :log (:event e1)))
          (is (= "Transfer" (get-in e1 [:log :event-name])))
          (is (= (utils/big 5) (get-in e1 [:log :args :value])))
          (is (= tu/weth-address (get-in e1 [:log :args :from])))
          (is (= (utils/big 16) (get-in e1 [:log :block-number])))
          (is (= (utils/big 17) (get-in e2 [:log :block-number])) "batch split in order")
          (events/stop! ch)
          (is (= tu/weth-address (:address (first (tu/call-params s "eth_newFilter"))))))))))

(deftest watch-contract-event-via-bound-contract-and-sliding-buffer
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newFilter" "0x1"
                        "eth_getFilterChanges" [(tu/transfer-log-json "0x10")]
                        "eth_uninstallFilter" true})
            c (tu/public-client s {:polling-interval 5})
            weth (contract/bind {:client c :address tu/weth-address :abi tu/erc20-abi})
            ch (events/watch-contract-event! c {:contract weth :event-name "Transfer"
                                                :poll? true :polling-interval 5
                                                :buf (async/sliding-buffer 2)})]
        (p/let [e (take! ch)
                _ (tu/sleep 60)]
          (is (= :log (:event e)))
          (is (= "Transfer" (get-in e [:log :event-name])))
          (is (= (utils/big 5) (get-in e [:log :args :value])))
          (is (events/watching? ch) "sliding buffer never blocks the producer")
          (events/stop! ch)
          (is (= "Transfer" (get-in (contract/parse-abi tu/erc20-abi) [2 :name]))))))))

(deftest watch-pending-transactions-and-blocks
  (async done
    (tu/async-test done
      (let [h1 (str "0x" (apply str (repeat 64 "a")))
            h2 (str "0x" (apply str (repeat 64 "b")))
            s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newPendingTransactionFilter" "0x2"
                        "eth_getFilterChanges" [h1 h2]
                        "eth_uninstallFilter" true
                        "eth_getBlockByNumber" (fn [[n _]] (tu/block-json (if (= n "latest") "0x10" n)))})
            c (tu/public-client s {:polling-interval 5})
            pch (events/watch-pending-transactions! c {:poll? true :polling-interval 5})
            bch (events/watch-blocks! c {:poll? true :polling-interval 5 :emit-on-begin? true})]
        (p/let [p1 (take! pch)
                p2 (take! pch)
                b1 (take! bch)]
          (is (= {:event :pending-transaction :hash h1} p1))
          (is (= {:event :pending-transaction :hash h2} p2))
          (is (= :block (:event b1)))
          (is (utils/bigint? (get-in b1 [:block :number])))
          (is (= (utils/big 1000000000) (get-in b1 [:block :base-fee-per-gas])))
          (events/stop! pch)
          (events/stop! bch)
          (is (not (events/watching? pch)))
          (is (not (events/watching? bch))))))))

(deftest errors-flow-through-the-channel
  (async done
    (tu/async-test done
      (let [s (tu/stub {"eth_blockNumber" (fn [_] (throw (ex-info "boom" {:code -32603 :message "boom"})))})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-block-number! c {:poll? true :polling-interval 5 :emit-on-begin? true})]
        (p/let [e (take! ch)]
          (is (= :error (:event e)))
          (is (= :viem-cljs/viem-error (util/error-type (:error e))))
          (is (= "InternalRpcError" (util/error-name (:error e))))
          (is (= :watch-block-number (:action (util/error-data (:error e)))))
          (is (events/watching? ch) "channel stays open after an error")
          (events/stop! ch))))))

(deftest filters-are-opaque-promises
  (async done
    (tu/async-test done
      (let [h (str "0x" (apply str (repeat 64 "c")))
            s (tu/stub {"eth_newBlockFilter" "0x3"
                        "eth_newFilter" "0x4"
                        "eth_newPendingTransactionFilter" "0x5"
                        "eth_getFilterChanges" (fn [[id]] (if (= id "0x4") [(tu/transfer-log-json)] [h]))
                        "eth_getFilterLogs" [(tu/transfer-log-json)]
                        "eth_uninstallFilter" true})
            c (tu/public-client s)]
        (p/let [bf (events/create-block-filter! c)
                ef (events/create-event-filter! c {:address tu/weth-address
                                                   :event "event Transfer(address indexed from, address indexed to, uint256 value)"})
                pf (events/create-pending-transaction-filter! c)
                cf (events/create-contract-event-filter! c {:abi tu/erc20-abi :address tu/weth-address :event-name "Transfer"})
                bc (events/filter-changes! c bf)
                ec (events/filter-changes! c ef)
                el (events/filter-logs! c ef)
                un (events/uninstall-filter! c bf)]
          (is (= "0x3" (:id bf)))
          (is (= "block" (:type bf)))
          (is (some? (:filter bf)) "raw filter kept opaque")
          (is (= "event" (:type ef)))
          (is (= "transaction" (:type pf)))
          (is (= "event" (:type cf)))
          (is (= [h] bc))
          (is (= "Transfer" (:event-name (first ec))) "event filters decode")
          (is (= (utils/big 5) (get-in (first el) [:args :value])))
          (is (true? un))
          (is (= ["0x3"] (tu/call-params s "eth_uninstallFilter"))))))))

(deftest burst-of-two-thousand-logs-drains-on-the-default-buffer
  (async done
    (tu/async-test done
      (let [batch (mapv #(tu/transfer-log-json (utils/to-hex %)) (range 2000))
            polls (atom 0)
            s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newFilter" "0x1"
                        "eth_getFilterChanges" (fn [_] (if (= 1 (swap! polls inc)) batch []))
                        "eth_uninstallFilter" true})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-event! c {:address tu/weth-address :poll? true :polling-interval 5})]
        (p/let [_ (tu/sleep 30)
                vs (p/loop [acc []]
                     (if (= 2000 (count acc))
                       acc
                       (p/let [v (take! ch)] (p/recur (conj acc v)))))]
          (is (= 2000 (count vs)) "1024 buffered + 976 pending puts, all delivered")
          (is (= (map utils/big (range 2000)) (map #(get-in % [:log :block-number]) vs)) "in order")
          (is (events/watching? ch))
          (events/stop! ch))))))

(deftest sliding-buffer-keeps-the-newest-events
  (async done
    (tu/async-test done
      (let [batch (mapv #(tu/transfer-log-json (utils/to-hex %)) (range 10))
            s (tu/stub {"eth_blockNumber" (tu/counter 15)
                        "eth_newFilter" "0x1"
                        "eth_getFilterChanges" batch
                        "eth_uninstallFilter" true})
            c (tu/public-client s {:polling-interval 5})
            ch (events/watch-event! c {:address tu/weth-address :poll? true :polling-interval 5
                                       :buf (async/sliding-buffer 3)})]
        (p/let [_ (tu/sleep 40)
                _ (events/stop! ch)
                a (take! ch) b (take! ch) c' (take! ch) d (take! ch)]
          (is (= [(utils/big 7) (utils/big 8) (utils/big 9)] (map #(get-in % [:log :block-number]) [a b c'])) "oldest dropped")
          (is (nil? d) "closed after the buffered values"))))))
