(ns viem-cljs.contract
  "Contracts as data (inventory 4): ABI parsing / formatting, every ABI codec,
   the standard ABIs, and a data-based bound contract — `bind` (alias
   `get-contract`) returns a plain map `{:client :address :abi :account}`
   consumed by `read!`, `simulate!`, `write!`, `estimate-gas!`, `events!`,
   `create-event-filter!` (viem's Proxy-based `getContract` is Tier 3 and not
   wrapped). `:function-name` may be a keyword (`:balance-of` -> \"balanceOf\")
   or a string; `:event-name` should be a string or a leading-capital keyword
   (`:Transfer`) because event names are capitalized. Pure ClojureScript:
   interop lives in `viem-cljs.core`."
  (:require [promesa.core :as p]
            [viem-cljs.core :as core]
            [viem-cljs.util :as util]
            [viem-cljs.utils :as utils]))

;; ---------------------------------------------------------------------------
;; ABI parsing / formatting
;; ---------------------------------------------------------------------------

(defn parse-abi
  "Human-readable signatures (vector of strings, structs as separate
   `\"struct P { uint256 x; }\"` entries) -> JSON ABI as kebab data. A JSON
   ABI (vector of maps) is returned unchanged."
  [abi]
  (core/parse-abi abi))

(defn parse-abi-item
  "One human-readable signature -> ABI item map (`\"event Transfer(address
   indexed from, address indexed to, uint256 value)\"`)."
  [signature]
  (core/invoke-util "parseAbiItem" signature))

(defn parse-abi-parameters
  "`\"address owner, uint256 amount\"` -> vector of parameter maps for
   `encode-abi-parameters` / `decode-abi-parameters`."
  [params]
  (core/invoke-util "parseAbiParameters" params))

(defn format-abi-item
  "ABI item (map or human-readable string) -> signature string
   (`\"balanceOf(address)\"`); `{:include-name? true}` keeps parameter names."
  ([item] (core/invoke-util "formatAbiItem" (core/abi-item->js item)))
  ([item opts] (core/invoke-util "formatAbiItem" (core/abi-item->js item) (core/opts->js opts))))

(defn format-abi
  "Whole ABI -> vector of signature strings (`(mapv format-abi-item abi)`;
   viem has no `formatAbi`, inventory 12.7)."
  [abi]
  (mapv format-abi-item (parse-abi abi)))

(defn get-abi-item
  "`{:abi :name :args?}` -> the matching ABI item (overloads resolved by
   `:args`), nil when absent."
  [opts]
  (core/invoke-util "getAbiItem" (core/opts->js opts)))

;; ---------------------------------------------------------------------------
;; Codecs
;; ---------------------------------------------------------------------------

(defn encode-function-data
  "`{:abi :function-name :args?}` -> calldata hex (`function-name` optional
   for a single-function ABI)."
  [opts]
  (core/invoke-util "encodeFunctionData" (core/opts->js opts)))

(defn decode-function-data
  "`{:abi :data}` -> `{:function-name :args}` (selector lookup)."
  [opts]
  (core/invoke-util "decodeFunctionData" (core/opts->js opts)))

(defn decode-function-result
  "`{:abi :function-name :data}` -> decoded value (single output bare,
   several -> vector, named struct -> kebab map)."
  [opts]
  (core/invoke-util "decodeFunctionResult" (core/opts->js opts)))

(defn encode-function-result
  "`{:abi :function-name :result}` -> return-data hex."
  [opts]
  (core/invoke-util "encodeFunctionResult" (core/opts->js opts)))

(defn decode-event-log
  "`{:abi :topics :data? :event-name? :strict?}` -> `{:event-name :args}`
   (named params -> kebab map, unnamed -> vector)."
  [opts]
  (core/invoke-util "decodeEventLog" (core/opts->js opts)))

(defn encode-event-topics
  "`{:abi :event-name? :args?}` -> vector of topic hexes (nil for unfiltered
   indexed params)."
  [opts]
  (core/invoke-util "encodeEventTopics" (core/opts->js opts)))

(defn parse-event-logs
  "`{:abi :logs :event-name? :args? :strict?}` -> the matching logs decoded
   (`:event-name`, `:args` added), e.g. from a receipt's `:logs`."
  [opts]
  (core/invoke-util "parseEventLogs" (core/opts->js opts)))

(defn decode-error-result
  "Revert data `{:abi :data}` -> `{:abi-item :error-name :args}` (custom
   errors, `Error(string)`, `Panic(uint256)`)."
  [opts]
  (core/invoke-util "decodeErrorResult" (core/opts->js opts)))

(defn encode-error-result
  "`{:abi :error-name? :args?}` -> revert data hex."
  [opts]
  (core/invoke-util "encodeErrorResult" (core/opts->js opts)))

(defn- params->js [params]
  (if (string? params)
    (core/invoke-util-raw "parseAbiParameters" params)
    (core/opts->js-value params)))

(defn encode-abi-parameters
  "Positional ABI encoding: `params` is a vector of `{:type ..}` maps or a
   human-readable string (\"address, uint256\"), `values` the vector of values."
  [params values]
  (core/invoke-util "encodeAbiParameters" (params->js params) (core/opts->js-value values)))

(defn decode-abi-parameters
  "Inverse of `encode-abi-parameters`: -> vector of values."
  [params data]
  (core/invoke-util "decodeAbiParameters" (params->js params) data))

(defn encode-packed
  "`abi.encodePacked`: `types` vector of type strings, `values` vector."
  [types values]
  (core/invoke-util "encodePacked" types (core/opts->js-value values)))

(defn encode-deploy-data
  "`{:abi :bytecode :args?}` -> deployment calldata hex."
  [opts]
  (core/invoke-util "encodeDeployData" (core/opts->js opts)))

(defn decode-deploy-data
  "`{:abi :bytecode :data}` -> `{:args :bytecode}`."
  [opts]
  (core/invoke-util "decodeDeployData" (core/opts->js opts)))

(defn function-selector
  "4-byte selector of a function signature or ABI item (see
   `utils/function-selector`)."
  [fn-or-item]
  (utils/function-selector fn-or-item))

(defn event-selector
  "topic0 of an event signature or ABI item (see `utils/event-selector`)."
  [event-or-item]
  (utils/event-selector event-or-item))

;; ---------------------------------------------------------------------------
;; Standard ABIs as data
;; ---------------------------------------------------------------------------

(def erc20-abi
  "ERC-20 JSON ABI (kebab maps)."
  (core/constant "erc20Abi"))

(def erc20-abi-bytes32
  "ERC-20 ABI variant with bytes32 `name` / `symbol` (MKR-style)."
  (core/constant "erc20Abi_bytes32"))

(def erc721-abi
  "ERC-721 JSON ABI."
  (core/constant "erc721Abi"))

(def erc1155-abi
  "ERC-1155 JSON ABI."
  (core/constant "erc1155Abi"))

(def erc4626-abi
  "ERC-4626 JSON ABI."
  (core/constant "erc4626Abi"))

(def multicall3-abi
  "Multicall3 JSON ABI."
  (core/constant "multicall3Abi"))

;; ---------------------------------------------------------------------------
;; Bound contract (data-based replacement for viem's Proxy getContract)
;; ---------------------------------------------------------------------------

(defn bind
  "Binds `{:client :address :abi :account?}` into a contract map:
   `{:client :address :abi <kebab JSON ABI> :account}` (the ABI is parsed
   once; a pre-converted copy is cached under a private key). `:abi` may be
   human-readable strings or kebab JSON ABI; `:client` any client (a wallet
   client for `write!`); `:account` an optional default account for
   `simulate!` / `write!` / `estimate-gas!`."
  [{:keys [client address abi account]}]
  (when-not (core/client? client)
    (throw (util/argument-error "bind needs a :client" {:reason :missing-client})))
  (when-not (string? address)
    (throw (util/argument-error "bind needs an :address string" {:reason :missing-address})))
  (when (nil? abi)
    (throw (util/argument-error "bind needs an :abi" {:reason :missing-abi})))
  (let [abi-js (core/abi->js abi)]
    (cond-> {:client client
             :address address
             :abi (util/->clj abi-js)
             ::abi-js abi-js}
      account (assoc :account account))))

(defn get-contract
  "Alias of `bind` (the inventory's name, 4.4)."
  [opts]
  (bind opts))

(defn contract?
  "True for a map produced by `bind`."
  [x]
  (and (map? x) (contains? x ::abi-js)))

(defn- with-contract
  "Merges the bound contract's address / ABI / account into action opts."
  [c opts]
  (cond-> (assoc opts :address (:address c) :abi (::abi-js c))
    (and (:account c) (nil? (:account opts))) (assoc :account (:account c))))

(defn- bound-action!
  "`core/action!` through a bound contract; a non-contract first argument
   rejects with :viem-cljs/argument-error (never throws)."
  [kw c opts]
  (if (contract? c)
    (core/action! kw (:client c) (with-contract c opts))
    (p/rejected (util/argument-error "expected a bound contract map (see bind)"
                                     {:reason :not-a-contract :action kw}))))

(defn read!
  "`readContract` through a bound contract: `(read! c {:function-name
   :balance-of :args [addr] :block-tag :latest})` -> promise of the decoded
   value."
  [c opts]
  (bound-action! :read-contract c opts))

(defn simulate!
  "`simulateContract` through a bound contract -> promise of `{:result
   :request}`; `:request` (with the opaque account / chain re-attached) feeds
   `write!` / `actions/write-contract!` unchanged."
  [c opts]
  (bound-action! :simulate-contract c opts))

(defn write!
  "`writeContract` through a bound contract (its client must be a wallet
   client or carry an account): `(write! c {:function-name :transfer :args
   [to amount] :value ..})` or `(write! c (:request simulation))` -> promise
   of the tx hash."
  [c opts]
  (bound-action! :write-contract c opts))

(defn simulate-and-write!
  "The idiomatic pair in one call: `simulate!` then `write!` of the
   returned request -> promise of the tx hash."
  [c opts]
  (p/then (simulate! c opts)
          (fn [{:keys [request]}] (write! c request))))

(defn estimate-gas!
  "`estimateContractGas` through a bound contract -> promise of BigInt gas."
  [c opts]
  (bound-action! :estimate-contract-gas c opts))

(defn events!
  "`getContractEvents` through a bound contract: `{:event-name \"Transfer\"
   :args {:from addr} :from-block :to-block | :block-hash :strict?}` ->
   promise of decoded logs."
  ([c] (events! c {}))
  ([c opts] (bound-action! :get-contract-events c opts)))

(defn create-event-filter!
  "`createContractEventFilter` through a bound contract -> promise of an
   opaque filter map `{:id :type :filter}` for `events/filter-changes!`."
  ([c] (create-event-filter! c {}))
  ([c opts] (bound-action! :create-contract-event-filter c opts)))

(defn deploy!
  "`deployContract` with a wallet client: `{:abi :bytecode :args? :account?
   :value? …}` -> promise of the tx hash (the address is `:contract-address`
   on the receipt from `actions/wait-for-transaction-receipt!`)."
  [client opts]
  (core/action! :deploy-contract client opts))

(defn- multicall-item [x]
  (cond
    (contract? x) (throw (util/argument-error "multicall! items need :function-name — pass {:contract c :function-name .. :args ..}" {}))
    (and (map? x) (contract? (:contract x)))
    (-> (dissoc x :contract)
        (assoc :address (:address (:contract x)) :abi (::abi-js (:contract x))))
    :else x))

(defn multicall!
  "Multicall3 aggregate of read calls: `{:contracts [{:address :abi
   :function-name :args} | {:contract bound :function-name :args} …]
   :allow-failure? (true) :batch-size? :multicall-address? :deployless?
   :block-number?}` -> promise of `[{:status \"success\" :result ..} |
   {:status \"failure\" :error ..}]` (or bare results with `:allow-failure?
   false`). Needs the chain's `multicall3` contract (or `:multicall-address`)."
  [client opts]
  (core/action! :multicall client (update opts :contracts #(mapv multicall-item %))))
