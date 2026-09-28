(ns viem-cljs.util
  "Small shared helpers: kebab-case <-> camelCase keyword translation, the
   CLJS <-> JS boundary conversions every binding namespace uses, BigInt
   support (wei amounts, block numbers, gas are BigInt on the viem side) and
   error normalization (viem's BaseError family -> ex-info). No I/O.

   This is a BINDING-LAYER namespace: it is one of the few places in the
   library that touches JavaScript directly. Public namespaces (core,
   actions, accounts, chains, contract, utils, events) call through here
   and never use interop at their call sites."
  (:require [clojure.string :as str]
            [goog.object :as gobj]))

;; ---------------------------------------------------------------------------
;; kebab-case <-> camelCase (viem is one camelCase dialect)
;; ---------------------------------------------------------------------------

(defn kebab->camel
  "\"block-number\" -> \"blockNumber\". Keywords render as their name; strings
   pass through untouched (they are assumed to be exact viem names already).
   Single-letter segments upper-case cleanly (:get-u-r-i -> \"getURI\"), so the
   translation is the exact inverse of camel->kebab for every camelCase
   identifier that does not START with a capital letter."
  [k]
  (cond
    (keyword? k) (let [[h & t] (str/split (name k) #"-")]
                   (apply str h (map (fn [s]
                                       (if (seq s)
                                         (str (str/upper-case (subs s 0 1)) (subs s 1))
                                         s))
                                     t)))
    (string? k) k
    :else (str k)))

(defn camel->kebab
  "\"blockNumber\" -> :block-number, \"sha3Uncles\" -> :sha3-uncles,
   \"l1Fee\" -> :l1-fee. Works on strings and keywords, returns a keyword.
   Namespaced keywords are returned unchanged. A LEADING capital is lowered
   without a dash (\"Transfer\" -> :transfer), which is the one lossy case
   (Solidity struct fields are camelCase in practice)."
  [k]
  (if (and (keyword? k) (namespace k))
    k
    (let [s (name k)]
      (keyword (-> s
                   (str/replace #"([A-Z])" (fn [[_ c]] (str "-" (str/lower-case c))))
                   (str/replace #"^-" ""))))))

(defn transform-keys
  "Recursively applies `f` to every map key in `x` (maps inside vectors/lists
   included). Keys listed in `passthrough` (a set) keep their subtree
   untouched. Pure."
  ([f x] (transform-keys f #{} x))
  ([f passthrough x]
   (cond
     (map? x) (into (empty x)
                    (map (fn [[k v]]
                           (if (contains? passthrough k)
                             [(f k) v]
                             [(f k) (transform-keys f passthrough v)])))
                    x)
     (sequential? x) (into (empty x) (map #(transform-keys f passthrough %)) x)
     :else x)))

(defn enum-value
  "Keyword enum value -> viem string (:latest -> \"latest\", :eip1559 ->
   \"eip1559\", :non-payable -> \"nonPayable\"); non-keywords untouched."
  [v]
  (if (keyword? v) (kebab->camel v) v))

;; ---------------------------------------------------------------------------
;; BigInt (wei, gas, block numbers, timestamps are BigInt
;; on the viem side; the library keeps them BigInt — exact, arithmetic-safe)
;; ---------------------------------------------------------------------------

(defn bigint?
  "True for a JS BigInt primitive."
  [x]
  (= "bigint" (js* "typeof ~{}" x)))

(defn ->bigint
  "Coerces an integer-like value to BigInt: BigInt (unchanged), an integer
   number (must be a safe integer), a decimal or 0x-hex digit string; nil ->
   nil. Anything else throws an :viem-cljs/argument-error."
  [x]
  (cond
    (nil? x) nil
    (bigint? x) x
    (and (number? x) (js/Number.isSafeInteger x)) (js/BigInt x)
    (and (string? x) (re-matches #"^-?\d+$" x)) (js/BigInt x)
    (and (string? x) (re-matches #"^0x[0-9a-fA-F]+$" x)) (js/BigInt x)
    :else (throw (ex-info (str "viem-cljs: cannot coerce to BigInt: " (pr-str x))
                          {:type :viem-cljs/argument-error :value x}))))

(defn bigint->number
  "BigInt -> number when it is a safe integer, else nil (never silently
   loses precision)."
  [b]
  (let [n (js/Number b)]
    (when (js/Number.isSafeInteger n) n)))

(defn bigint->string
  "Decimal digit string of a BigInt (\"1000000000000000000\")."
  [b]
  (str b))

;; Make BigInt a first-class CLJS value: hashable (usable as a map key / in a
;; set — the default IHash would try to attach a uid to a primitive and
;; throw), and printed like Clojure's BigInt literal (1000N) so REPL output
;; distinguishes it from a double.
(extend-type bigint
  IHash
  (-hash [o] (hash (str o)))
  IPrintWithWriter
  (-pr-writer [o writer _] (-write writer (str o "N"))))

;; ---------------------------------------------------------------------------
;; CLJS <-> JS boundary (the only conversions binding namespaces need)
;; ---------------------------------------------------------------------------

(defn ->js
  "Kebab keyword map -> JS object with camelCase keys, recursively; drops
   nil values; keyword values render via enum-value; vectors/sets become
   arrays; BigInt, numbers, strings, booleans, functions, and any JS value
   (viem clients, accounts, chain objects, Uint8Array) pass through untouched.
   Subtrees under `passthrough` (set of kebab keys) are converted with the
   keys kept verbatim (clj->js) — for caller-owned blobs whose key spelling
   matters. nil in -> nil out."
  ([x] (->js x #{}))
  ([x passthrough]
   (cond
     (nil? x) nil
     (keyword? x) (enum-value x)
     (map? x) (let [o (js-obj)]
                (doseq [[k v] x
                        :when (some? v)]
                  (unchecked-set o (kebab->camel k)
                                 (if (contains? passthrough k)
                                   (clj->js v)
                                   (->js v passthrough))))
                o)
     (sequential? x) (let [a (array)]
                       (doseq [v x] (.push a (->js v passthrough)))
                       a)
     (set? x) (->js (vec x) passthrough)
     :else x)))

(defn ->clj
  "JS value -> CLJS data with kebab keyword keys, recursively (plain objects
   and arrays only; BigInt, functions, Uint8Array and class instances pass
   through untouched). Values are never re-cased (hex strings stay strings,
   \"latest\" stays \"latest\")."
  [x]
  (transform-keys camel->kebab (js->clj x :keywordize-keys true)))

(defn jsget
  "Property read without interop syntax at the call site: (jsget o \"name\")
   or (jsget o \"a\" \"b\") for a path. nil-safe."
  [o & path]
  (when (some? o) (apply gobj/getValueByKeys o path)))

;; ---------------------------------------------------------------------------
;; Errors (viem's BaseError family; every viem rejection is
;; re-thrown as ex-info with :type :viem-cljs/viem-error)
;; ---------------------------------------------------------------------------

(def error-types
  "Every :type a viem-cljs ex-info can carry."
  #{:viem-cljs/viem-error :viem-cljs/argument-error :viem-cljs/config-error
    :viem-cljs/transport-error})

(defn js-viem-error?
  "True when `e` is a raw viem BaseError (any subclass): has the `walk`
   method and a `shortMessage`."
  [e]
  (and (some? e)
       (fn? (jsget e "walk"))
       (string? (jsget e "shortMessage"))))

(defn- cause-seq
  "The `cause` chain of a JS error, outermost first (includes `e`)."
  [e]
  (loop [e e acc [] n 0]
    (if (and (some? e) (< n 32) (not (some #(identical? % e) acc)))
      (recur (jsget e "cause") (conj acc e) (inc n))
      acc)))

(defn- error-summary
  "Data view of one error in a cause chain: `:name :short-message :message
   :details :code` plus, when present, `:data` (converted — the decoded
   revert of a `ContractFunctionRevertedError`, the `data` of a provider
   error), `:reason` (revert reason string), `:status` / `:url` (HTTP)."
  [e]
  (let [name (jsget e "name")
        data (jsget e "data")]
    (cond-> {:name (or name (some-> (jsget e "constructor" "name")) "Error")}
      (string? (jsget e "shortMessage")) (assoc :short-message (jsget e "shortMessage"))
      (string? (jsget e "message")) (assoc :message (jsget e "message"))
      (string? (jsget e "details")) (assoc :details (jsget e "details"))
      (some? (jsget e "code")) (assoc :code (jsget e "code"))
      (some? data) (assoc :data (if (or (object? data) (array? data)) (->clj data) data))
      (string? (jsget e "reason")) (assoc :reason (jsget e "reason"))
      (number? (jsget e "status")) (assoc :status (jsget e "status"))
      (string? (jsget e "url")) (assoc :url (jsget e "url")))))

(defn viem-error
  "ex-info wrapping a viem BaseError (or any JS error thrown by viem).
   Data: {:type :viem-cljs/viem-error :name <class name> :short-message ..
   :details .. :meta-messages [..] :docs-path .. :version .. :code ..
   :causes [{:name ..} ...] (outermost first; each summary may also carry
   :data :reason :status :url) :root <deepest cause summary>
   :action <kebab keyword of the wrapped call> :cause <the raw JS error>}.
   Already-wrapped ex-info values are returned unchanged. `viem-cljs.errors`
   builds its accessors and classifiers on this data."
  ([e] (viem-error e nil))
  ([e action]
   (if (some? (ex-data e))
     e
     (let [chain (cause-seq e)
           summaries (mapv error-summary chain)
           root (last summaries)
           short (or (jsget e "shortMessage") (jsget e "message") (str e))]
       (ex-info (str "viem-cljs " (:name (first summaries))
                     (when action (str " (" (kebab->camel action) ")"))
                     ": " short)
                (cond-> {:type :viem-cljs/viem-error
                         :name (:name (first summaries))
                         :short-message short
                         :causes summaries
                         :root root
                         :cause e}
                  action (assoc :action action)
                  (string? (jsget e "details")) (assoc :details (jsget e "details"))
                  (some? (jsget e "metaMessages")) (assoc :meta-messages (vec (jsget e "metaMessages")))
                  (string? (jsget e "docsPath")) (assoc :docs-path (jsget e "docsPath"))
                  (string? (jsget e "version")) (assoc :version (jsget e "version"))
                  (some? (jsget e "code")) (assoc :code (jsget e "code"))))))))

(defn config-error
  "ex-info for a client/transport/account configuration problem.
   Data: {:type :viem-cljs/config-error :reason .. ..}. Never embed private
   key material in `data`."
  [message & [data]]
  (ex-info (str "viem-cljs: " message)
           (merge {:type :viem-cljs/config-error} data)))

(defn argument-error
  "ex-info for an invalid argument detected before viem is called."
  [message & [data]]
  (ex-info (str "viem-cljs: " message)
           (merge {:type :viem-cljs/argument-error} data)))

(defn error-data
  "Returns the structured data of a viem-cljs error, or nil when `e` is not
   one."
  [e]
  (when-let [d (ex-data e)]
    (when (contains? error-types (:type d))
      d)))

(defn error-type
  "One of the viem-cljs error :type keywords (see error-types) or nil."
  [e]
  (:type (error-data e)))

(defn error-name
  "The viem error class name (\"ContractFunctionExecutionError\") of a
   wrapped viem error, nil otherwise."
  [e]
  (when (= :viem-cljs/viem-error (error-type e))
    (:name (error-data e))))

(defn error-causes
  "The names of the wrapped error's cause chain, outermost first
   ([\"ContractFunctionExecutionError\" \"ContractFunctionRevertedError\"]),
   nil otherwise."
  [e]
  (some->> (error-data e) :causes (mapv :name)))

(defn error-cause?
  "True when `name` (a string) appears anywhere in the wrapped error's cause
   chain — the CLJS equivalent of viem's `err.walk(e => e instanceof X)`."
  [e name]
  (boolean (some #(= name %) (error-causes e))))

;; ---------------------------------------------------------------------------
;; Secrets (key material never reaches logs, data or maps)
;; ---------------------------------------------------------------------------

(deftype Secret [value]
  ;; Opaque holder for a private key / mnemonic / password: prints as
  ;; #viem-cljs/secret <redacted>, compares by identity, never exposes the
  ;; value through printing, `str`, or data conversion (`->js` passes the
  ;; holder through untouched — call `reveal` at the one place that needs it).
  IPrintWithWriter
  (-pr-writer [_ writer _] (-write writer "#viem-cljs/secret <redacted>"))
  Object
  (toString [_] "#viem-cljs/secret <redacted>"))

(defn secret
  "Wraps `value` (a private key, mnemonic, password …) in an opaque `Secret`
   holder that prints as `#viem-cljs/secret <redacted>`. Already-wrapped
   values are returned unchanged; nil -> nil."
  [value]
  (cond
    (nil? value) nil
    (instance? Secret value) value
    :else (Secret. value)))

(defn secret?
  "True for a `Secret` holder."
  [x]
  (instance? Secret x))

(defn reveal
  "The value inside a `Secret` (a non-secret passes through). Call it only
   at the boundary that must see the raw value (`private-key->account`,
   `account-from-keystore!`), never in anything that is printed or stored."
  [x]
  (if (instance? Secret x) (.-value x) x))

(def secret-keys
  "Kebab keys whose values are treated as secrets by `redact` wherever they
   appear in a map."
  #{:private-key :private-keys :mnemonic :password :passphrase :seed :secret
    :secrets :api-key :api-secret :auth :authorization :token :xprv :keystore-password})

(def redacted
  "The placeholder `redact` substitutes for a secret."
  "<redacted>")

(defn redact
  "Returns `x` with every secret replaced by `redacted`: values under
   `secret-keys` (or under extra keys given in `more-keys`, a set) at any
   depth, and every `Secret` holder. Vectors / sets are walked; everything
   else is returned as is. Use it on any config or report before printing."
  ([x] (redact x #{}))
  ([x more-keys]
   (let [keys* (into secret-keys more-keys)]
     (cond
       (instance? Secret x) redacted
       (map? x) (into (empty x)
                      (map (fn [[k v]]
                             (if (contains? keys* k)
                               [k (if (nil? v) v redacted)]
                               [k (redact v more-keys)])))
                      x)
       (sequential? x) (into (empty x) (map #(redact % more-keys)) x)
       (set? x) (into #{} (map #(redact % more-keys)) x)
       :else x))))
