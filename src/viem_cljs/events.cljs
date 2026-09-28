(ns viem-cljs.events
  "Event watchers as core.async channels and the server-side
   filter actions as promises.

   Channel contract: each `watch-*!` returns ONE channel; every put is a
   tagged map — `{:event :block-number :block-number 100N :prev 99N}`,
   `{:event :block :block {..} :prev {..}}`, `{:event :block-header :header {..}
   :prev {..}}`, `{:event :log :log {..}}` (viem's batch of n logs -> n puts, in
   order), `{:event :pending-transaction :hash \"0x…\"}` (batch -> n puts),
   `{:event :error :error <ex-info>}` (the channel stays open, like viem's
   watcher). Buffer: `(async/chan 1024)` by default, `:buf` (a number or any
   core.async buffer, e.g. `(async/sliding-buffer 64)`) overrides it.
   Unsubscribe with `(stop! ch)` — or simply close the channel: the producer
   unsubscribes on the first failed put. `:poll?`, `:polling-interval`,
   `:emit-on-begin?`, `:emit-missed?`, `:batch?`, `:from-block`, `:strict?`
   pass through to viem. Pure ClojureScript: interop lives in `viem-cljs.core`."
  (:require [cljs.core.async :as async]
            [viem-cljs.core :as core]))

(defonce ^:private watchers
  ;; channel -> viem unwatch thunk, for every live watcher
  (atom {}))

(defn- make-chan [{:keys [buf]}]
  (cond
    (nil? buf) (async/chan 1024)
    (number? buf) (async/chan buf)
    :else (async/chan buf)))

(defn watching?
  "True while the channel's viem watcher is still subscribed."
  [ch]
  (contains? @watchers ch))

(defn stop!
  "Unsubscribes the viem watcher behind `ch` and closes the channel.
   Idempotent; returns nil."
  [ch]
  (when-let [unwatch (get @watchers ch)]
    (swap! watchers dissoc ch)
    (unwatch))
  (async/close! ch)
  nil)

(defn- emit!
  "Puts `v` on `ch`; when the channel is already closed (consumer-side
   unsubscribe) stops the watcher."
  [ch v]
  (when-not (async/put! ch v)
    (stop! ch)))

(defn- start!
  "Starts viem watcher `kw`, wiring `handler-key` to `emit-fn` (fn [ch args])
   and `:on-error` to error events. Returns the channel."
  [kw client opts handler-key emit-fn]
  (let [ch (make-chan opts)
        unwatch (core/watch! kw client (dissoc opts :buf)
                             {handler-key (fn [& args] (emit-fn ch args))
                              :on-error (fn [e] (emit! ch {:event :error :error e}))})]
    (swap! watchers assoc ch unwatch)
    ch))

(defn watch-block-number!
  "Channel of `{:event :block-number :block-number <BigInt> :prev <BigInt|nil>}`
   (viem `watchBlockNumber`). Opts: `:emit-on-begin? :emit-missed? :poll?
   :polling-interval :buf`."
  ([client] (watch-block-number! client {}))
  ([client opts]
   (start! :watch-block-number client opts :on-block-number
           (fn [ch [n prev]] (emit! ch {:event :block-number :block-number n :prev prev})))))

(defn watch-blocks!
  "Channel of `{:event :block :block <kebab block> :prev <block|nil>}` (viem
   `watchBlocks`). Opts: `:block-tag :include-transactions? :emit-on-begin?
   :emit-missed? :poll? :polling-interval :buf`."
  ([client] (watch-blocks! client {}))
  ([client opts]
   (start! :watch-blocks client opts :on-block
           (fn [ch [b prev]] (emit! ch {:event :block :block b :prev prev})))))

(defn watch-block-headers!
  "Channel of `{:event :block-header :header <kebab header> :prev ..}` (viem
   `watchBlockHeaders`; webSocket / ipc subscriptions only)."
  ([client] (watch-block-headers! client {}))
  ([client opts]
   (start! :watch-block-headers client opts :on-block-header
           (fn [ch [h prev]] (emit! ch {:event :block-header :header h :prev prev})))))

(defn watch-event!
  "Channel of `{:event :log :log <kebab log>}`, one put per log (viem
   `watchEvent`). Opts: `:address :event (human-readable string or ABI item)
   :events :args :from-block :strict? :batch? :poll? :polling-interval :buf`.
   Logs are decoded (`:event-name`, `:args`) when `:event` / `:events` is given."
  ([client] (watch-event! client {}))
  ([client opts]
   (start! :watch-event client opts :on-logs
           (fn [ch [logs]] (doseq [l logs] (emit! ch {:event :log :log l}))))))

(defn watch-contract-event!
  "Channel of `{:event :log :log <decoded log>}` for a contract (viem
   `watchContractEvent`): opts `:abi :address :event-name :args :from-block
   :strict? :batch? :poll? :polling-interval :buf`; a bound contract map from
   `contract/bind` may be given as `:contract` instead of `:abi`/`:address`."
  ([client opts]
   (let [c (:contract opts)
         opts (cond-> (dissoc opts :contract)
                c (assoc :abi (:abi c) :address (:address c)))]
     (start! :watch-contract-event client opts :on-logs
             (fn [ch [logs]] (doseq [l logs] (emit! ch {:event :log :log l})))))))

(defn watch-pending-transactions!
  "Channel of `{:event :pending-transaction :hash \"0x…\"}`, one put per hash
   (viem `watchPendingTransactions`). Opts: `:batch? :poll? :polling-interval :buf`."
  ([client] (watch-pending-transactions! client {}))
  ([client opts]
   (start! :watch-pending-transactions client opts :on-transactions
           (fn [ch [hashes]] (doseq [h hashes] (emit! ch {:event :pending-transaction :hash h}))))))

;; ---------------------------------------------------------------------------
;; Filters (manual pull; server-side filter ids) — promises
;; ---------------------------------------------------------------------------

(defn create-event-filter!
  "`eth_newFilter` -> promise of an opaque filter map `{:id :type :filter}`.
   Opts: `:address :event :events :args :from-block :to-block :strict?`."
  ([client] (create-event-filter! client {}))
  ([client opts] (core/action! :create-event-filter client opts)))

(defn create-block-filter!
  "`eth_newBlockFilter` -> promise of an opaque filter map."
  [client]
  (core/action! :create-block-filter client {}))

(defn create-pending-transaction-filter!
  "`eth_newPendingTransactionFilter` -> promise of an opaque filter map."
  [client]
  (core/action! :create-pending-transaction-filter client {}))

(defn create-contract-event-filter!
  "Filter for a contract's events: `{:abi :address? :event-name? :args?
   :from-block? :to-block? :strict?}` -> promise of an opaque filter map."
  [client opts]
  (core/action! :create-contract-event-filter client opts))

(defn filter-changes!
  "`eth_getFilterChanges` for a filter map -> promise of new logs (event /
   contract filters), block hashes or tx hashes (by filter type)."
  [client flt]
  (core/action! :get-filter-changes client {:filter flt}))

(defn filter-logs!
  "`eth_getFilterLogs` for an event filter -> promise of all matching logs."
  [client flt]
  (core/action! :get-filter-logs client {:filter flt}))

(defn uninstall-filter!
  "`eth_uninstallFilter` -> promise of boolean."
  [client flt]
  (core/action! :uninstall-filter client {:filter flt}))
