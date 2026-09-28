(ns viem-cljs.core
  "The ONE viem interop namespace of viem-cljs. Everything that touches a
   viem export lives in this file — client construction, transports, the
   chain registry, account factories, keystore decryption and the generic
   dispatchers (`action!`, `watch!`, `invoke-util`) that `actions`,
   `contract`, `chains`, `utils` and `events` call. Those namespaces are
   plain ClojureScript.

   Interop style inside this file: `js-invoke` + `goog.object` (`util/jsget`)
   only; no `^js` hints, no dot-forms.

   Conventions:
   - options are kebab-case keyword maps; keys are camelled mechanically,
     `?`-suffixed booleans are stripped, keyword values become strings,
     quantity keys (`:value :gas :block-number …`) are coerced with
     `util/->bigint`, `:abi` accepts human-readable strings or kebab JSON ABI,
     `:chain` accepts a keyword / id / kebab map / chain object, `:account`
     accepts an account object / address / `{:address ..}` map.
   - results come back as kebab maps; BigInt stays BigInt, hex stays string.
   - every throw / rejection crossing the boundary is an ex-info from
     `util/viem-error`, `util/config-error` or `util/argument-error`.
   - `!` marks fns that hit the network or move chain state; client and
     account construction is local and carries no `!` (a websocket / ipc
     transport opens its socket lazily on the first request)."
  (:require ["viem" :as viem]
            ["viem/actions" :as va]
            ["viem/accounts" :as vacc]
            ["viem/chains" :as vchains]
            ["viem/chains/utils" :as vcu]
            ["viem/utils" :as vutils]
            ["viem/ens" :as vens]
            ["viem/siwe" :as vsiwe]
            ["viem/nonce" :as vnonce]
            ["viem/node" :as vnode]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [goog.object :as gobj]
            [promesa.core :as p]
            [viem-cljs.util :as util]))

;; ---------------------------------------------------------------------------
;; Small JS helpers (kept private; the rest of the file builds on them)
;; ---------------------------------------------------------------------------

(defn- thenable?
  "True when `x` looks like a promise (has a `then` function)."
  [x]
  (and (some? x) (not (string? x)) (js-fn? (util/jsget x "then"))))

(defn- rethrow
  "Throws `e` wrapped as a viem-cljs error, tagged with `action`."
  [e action]
  (throw (util/viem-error e action)))

(defn- guard
  "Calls (f) and wraps any synchronous throw as a viem-cljs error."
  [action f]
  (try (f) (catch :default e (rethrow e action))))

(defn- wrap-promise
  "Turns a viem promise into a promesa promise whose value is converted with
   `convert` and whose rejection is a wrapped viem-cljs error."
  [pr action convert]
  (-> pr
      (p/then convert)
      (p/catch (fn [e] (rethrow e action)))))

(defn- promised
  "Runs (f) inside a promise chain (synchronous throws — argument validation,
   viem's pre-request errors — become rejections), converts the value with
   `convert` and wraps rejections. Every network-facing fn goes through here
   so callers can rely on a single failure channel: the rejection."
  [action f convert]
  (-> (p/resolved nil)
      (p/then (fn [_] (f)))
      (p/then convert)
      (p/catch (fn [e] (rethrow e action)))))

(defn- export
  "Looks `camel-name` up in the given viem module objects (first hit wins)."
  [camel-name & modules]
  (some (fn [m] (let [v (gobj/get m camel-name)] (when (some? v) v))) modules))

;; ---------------------------------------------------------------------------
;; Chain registry (opaque objects, lazy indexes)
;; ---------------------------------------------------------------------------

(defonce ^:private chain-index
  ;; Built on first use: only NAMES and ids are read; no chain object is
  ;; converted. {:names [..camel..] :by-keyword {kebab-kw camel} :by-id {id camel}}
  (delay
    (let [names (vec (js-keys vchains))]
      {:names names
       :by-keyword (into {} (map (fn [n] [(util/camel->kebab n) n])) names)
       :by-id (reduce (fn [acc n]
                        (let [id (util/jsget vchains n "id")]
                          (if (or (nil? id) (contains? acc id)) acc (assoc acc id n))))
                      {}
                      names)})))

(defn chain-object?
  "True when `x` is a viem chain object (opaque JS object with `id` and
   `rpcUrls`)."
  [x]
  (and (object? x)
       (number? (util/jsget x "id"))
       (some? (util/jsget x "rpcUrls"))))

(defn chain-keywords
  "Every registry chain as a kebab keyword, in viem's export order
   (`:mainnet`, `:base-sepolia`, `:zk-sync` …). 737 entries in viem 2.56.9."
  []
  (mapv util/camel->kebab (:names @chain-index)))

(defn chain-keyword->name
  "The viem export name for a kebab keyword (`:base-sepolia` ->
   \"baseSepolia\"), nil when unknown."
  [kw]
  (get (:by-keyword @chain-index) kw))

(defn chain-id->keyword
  "The canonical kebab keyword for a chain id: the FIRST registry name with
   that id (`31337` -> `:anvil`, aliases `:foundry` / `:hardhat` come later),
   nil when unknown."
  [id]
  (some-> (get (:by-id @chain-index) id) util/camel->kebab))

(defn define-chain
  "viem `defineChain` over a kebab map (`{:id 1 :name \"…\" :native-currency
   {:name :symbol :decimals} :rpc-urls {:default {:http [..]}} …}`). Returns an
   opaque chain object accepted everywhere a chain is."
  [m]
  (guard :define-chain #(js-invoke vcu "defineChain" (util/->js m))))

(defn chain-object
  "Resolves anything chain-like to a viem chain object: a kebab keyword
   (`:mainnet`, `:base-sepolia`), a viem export name string (\"baseSepolia\"),
   a chain id (`1`), a kebab map (via `define-chain`), a chain object (as is),
   nil -> nil. Unknown keyword / id -> :viem-cljs/config-error."
  [x]
  (cond
    (nil? x) nil
    (chain-object? x) x
    (keyword? x) (if-let [n (chain-keyword->name x)]
                   (gobj/get vchains n)
                   (throw (util/config-error (str "unknown chain " x)
                                             {:reason :unknown-chain :chain x})))
    (string? x) (chain-object (util/camel->kebab x))
    (integer? x) (if-let [n (get (:by-id @chain-index) x)]
                   (gobj/get vchains n)
                   (throw (util/config-error (str "unknown chain id " x)
                                             {:reason :unknown-chain :chain x})))
    (map? x) (define-chain x)
    :else (throw (util/argument-error (str "not a chain: " (pr-str x)) {:chain x}))))

(defn chain->map
  "Data projection of a chain object: every field as kebab data minus the
   function-valued ones (`extend`, `formatters`, `serializers`, `fees`), plus
   `:formatters?` / `:serializers?` flags. `x` is anything `chain-object`
   accepts. nil -> nil."
  [x]
  (when-let [c (chain-object x)]
    (-> (util/->clj c)
        (dissoc :extend :formatters :serializers :fees)
        (assoc :formatters? (some? (util/jsget c "formatters"))
               :serializers? (some? (util/jsget c "serializers"))))))

(defn chain-get
  "Reads a (possibly nested) field of a chain object without converting the
   whole object: (chain-get :mainnet \"rpcUrls\" \"default\" \"http\") -> JS
   array. Internal helper for `viem-cljs.chains`; returns the raw value
   converted with `->clj`."
  [x & path]
  (util/->clj (apply util/jsget (chain-object x) path)))

(defn extract-chain
  "viem `extractChain`: picks the chain with `id` from `chains` (a vector of
   anything `chain-object` accepts); nil when absent."
  [chains id]
  (guard :extract-chain
         #(js-invoke vcu "extractChain"
                     (util/->js {:chains (mapv chain-object chains) :id id}))))

(defn chain-contract-address
  "viem `getChainContractAddress`: the address of a chain contract
   (`:multicall3`, `:ens-registry`, `:ens-universal-resolver`, …), optionally
   at `block-number`. Throws a wrapped `ChainDoesNotSupportContract` when the
   chain lacks it."
  ([chain contract] (chain-contract-address chain contract nil))
  ([chain contract block-number]
   (guard :get-chain-contract-address
          #(js-invoke vcu "getChainContractAddress"
                      (util/->js {:chain (chain-object chain)
                                  :contract (util/kebab->camel contract)
                                  :block-number (util/->bigint block-number)})))))

;; ---------------------------------------------------------------------------
;; Accounts
;; ---------------------------------------------------------------------------

(defn account-object?
  "True when `x` is a viem account object (has `address` and `type`)."
  [x]
  (and (object? x)
       (string? (util/jsget x "address"))
       (string? (util/jsget x "type"))))

(defn account->map
  "Public projection of an account object: `{:address :type :source
   :public-key}` (`:public-key` only for local accounts). Never exposes key
   material or signer closures. nil -> nil."
  [a]
  (when (some? a)
    (cond-> {:address (util/jsget a "address")
             :type (util/jsget a "type")}
      (some? (util/jsget a "source")) (assoc :source (util/jsget a "source"))
      (string? (util/jsget a "publicKey")) (assoc :public-key (util/jsget a "publicKey")))))

(defn address-account
  "A json-rpc account for `address` (signing is delegated to the transport's
   node: `eth_sendTransaction`, `personal_sign` …) — viem `toAccount(address)`."
  [address]
  (guard :to-account #(js-invoke vacc "toAccount" address)))

(defn account-object
  "Resolves anything account-like to a viem account object: an account
   object (as is), an address string (json-rpc account), a kebab map with
   `:address` (json-rpc account), nil -> nil."
  [x]
  (cond
    (nil? x) nil
    (account-object? x) x
    (string? x) (address-account x)
    (and (map? x) (string? (:address x))) (address-account (:address x))
    :else (throw (util/argument-error (str "not an account: " (pr-str x)) {:account x}))))

(defn parse-account
  "viem `parseAccount`: address string -> json-rpc account object; account
   object -> itself."
  [x]
  (guard :parse-account #(js-invoke vacc "parseAccount" (if (map? x) (:address x) x))))

(defn generate-private-key
  "A fresh random 32-byte private key as a 0x hex string (CSPRNG). Never log
   or persist it."
  []
  (js-invoke vacc "generatePrivateKey"))

(def wordlist-names
  "The BIP-39 wordlists viem ships, as keywords."
  [:english :czech :french :italian :japanese :korean :portuguese
   :simplified-chinese :spanish :traditional-chinese])

(defn wordlist
  "The 2048 words of a BIP-39 wordlist (`:english` …) as a vector of strings."
  [name-kw]
  (let [wl (gobj/get vacc (util/kebab->camel name-kw))]
    (when (nil? wl)
      (throw (util/argument-error (str "unknown wordlist " name-kw) {:wordlist name-kw})))
    (vec wl)))

(defn generate-mnemonic
  "A fresh BIP-39 mnemonic (12 words at the default `strength` 128; 24 words
   at 256) from `wordlist` (keyword, default `:english`)."
  ([] (generate-mnemonic :english))
  ([wordlist-kw] (generate-mnemonic wordlist-kw nil))
  ([wordlist-kw strength]
   (let [wl (gobj/get vacc (util/kebab->camel wordlist-kw))]
     (when (nil? wl)
       (throw (util/argument-error (str "unknown wordlist " wordlist-kw) {:wordlist wordlist-kw})))
     (guard :generate-mnemonic
            #(if strength
               (js-invoke vacc "generateMnemonic" wl strength)
               (js-invoke vacc "generateMnemonic" wl))))))

(defn private-key->account
  "Local account from a 0x private key (viem `privateKeyToAccount`). Options:
   `:nonce-manager`. The key is not retained in any viem-cljs data structure
   (use `account->map` for a printable view)."
  ([private-key] (private-key->account private-key nil))
  ([private-key opts]
   (when-not (and (string? private-key) (re-matches #"^0x[0-9a-fA-F]{64}$" private-key))
     (throw (util/argument-error "private key must be a 0x-prefixed 32-byte hex string" {})))
   (guard :private-key-to-account
          #(js-invoke vacc "privateKeyToAccount" private-key (util/->js (or opts {}))))))

(defn mnemonic->account
  "HD account from a BIP-39 mnemonic (viem `mnemonicToAccount`). Options:
   `:account-index :address-index :change-index :path :passphrase
   :nonce-manager`; the default path is m/44'/60'/<account>'/<change>/<address>."
  ([mnemonic] (mnemonic->account mnemonic nil))
  ([mnemonic opts]
   (guard :mnemonic-to-account
          #(js-invoke vacc "mnemonicToAccount" mnemonic (util/->js (or opts {}))))))

(defn account-hd-key
  "The opaque HDKey of an HD account (`mnemonic->account` output), nil for
   other account kinds."
  [account]
  (when (js-fn? (util/jsget account "getHdKey"))
    (js-invoke account "getHdKey")))

(defn hd-key-from-seed
  "Opaque HDKey from a master seed (Uint8Array) — `HDKey.fromMasterSeed`."
  [seed]
  (guard :hd-key-from-seed #(js-invoke (gobj/get vacc "HDKey") "fromMasterSeed" seed)))

(defn hd-key-from-extended-key
  "Opaque HDKey from an xprv / xpub string — `HDKey.fromExtendedKey`."
  [xkey]
  (guard :hd-key-from-extended-key #(js-invoke (gobj/get vacc "HDKey") "fromExtendedKey" xkey)))

(defn hd-key->account
  "Local account from an opaque HDKey (viem `hdKeyToAccount`); same options
   as `mnemonic->account`."
  ([hd-key] (hd-key->account hd-key nil))
  ([hd-key opts]
   (guard :hd-key-to-account #(js-invoke vacc "hdKeyToAccount" hd-key (util/->js (or opts {}))))))

(defn private-key->address
  "The checksummed address of a 0x private key (pure)."
  [private-key]
  (guard :private-key-to-address #(js-invoke vacc "privateKeyToAddress" private-key)))

(defn public-key->address
  "The checksummed address of a 0x (uncompressed) public key (pure)."
  [public-key]
  (guard :public-key-to-address #(js-invoke vacc "publicKeyToAddress" public-key)))

(defn- signer-wrapper
  "Wraps a CLJS signer fn (kebab map -> hex | promise of hex) as the JS
   callback viem's `toAccount` expects; positional extra args are converted
   too."
  [f]
  (when f
    (fn [& args]
      (apply f (map util/->clj args)))))

(defn custom-account
  "Local account with caller-supplied signers (viem `toAccount`):
   `{:address .. :sign-message (fn [{:keys [message]}]) :sign-transaction
   (fn [tx-map serializer-opts]) :sign-typed-data (fn [typed-data-map])
   :sign (fn [{:keys [hash]}])? :sign-authorization (fn [auth-map])?
   :nonce-manager?}`. Each signer receives kebab maps and returns a 0x hex
   string or a promise of one."
  [{:keys [address sign-message sign-transaction sign-typed-data sign sign-authorization nonce-manager]}]
  (when-not (string? address)
    (throw (util/argument-error "custom-account needs an :address" {})))
  (guard :to-account
         #(js-invoke vacc "toAccount"
                     (util/->js (cond-> {:address address
                                         :sign-message (signer-wrapper sign-message)
                                         :sign-transaction (signer-wrapper sign-transaction)
                                         :sign-typed-data (signer-wrapper sign-typed-data)}
                                  sign (assoc :sign (signer-wrapper sign))
                                  sign-authorization (assoc :sign-authorization (signer-wrapper sign-authorization))
                                  nonce-manager (assoc :nonce-manager nonce-manager))))))

(defn nonce-manager
  "viem's shared default nonce manager (json-rpc source) — pass as
   `:nonce-manager` to the account factories for concurrent sends."
  []
  (gobj/get vacc "nonceManager"))

(defn create-nonce-manager
  "A fresh nonce manager with the json-rpc source (viem `createNonceManager`)."
  []
  (guard :create-nonce-manager
         #(js-invoke vacc "createNonceManager"
                     (util/->js {:source (js-invoke vnonce "jsonRpc")}))))

;; Local signing through an account object (pure: no network, no `!`).

(declare opts->js)

(defn- account-method!
  [account method opts action]
  (promised action
            (fn []
              (when-not (account-object? account)
                (throw (util/argument-error "not an account object" {:action action})))
              (when-not (js-fn? (util/jsget account method))
                (throw (util/argument-error (str "account cannot " method " (type " (util/jsget account "type") ")")
                                            {:action action :account-type (util/jsget account "type")})))
              (js-invoke account method (opts->js opts)))
            util/->clj))

(defn sign-message
  "EIP-191 personal-sign with a LOCAL account object:
   `{:message \"hi\"}` or `{:message {:raw \"0x…\"}}` -> promise of a 0x signature."
  [account opts]
  (account-method! account "signMessage" opts :sign-message))

(defn sign-typed-data
  "EIP-712 signature with a local account: `{:domain :types :primary-type
   :message}` -> promise of a 0x signature."
  [account opts]
  (account-method! account "signTypedData" opts :sign-typed-data))

(defn sign-transaction
  "Signs a transaction map (`{:to :value :gas :nonce :chain-id :type …}`)
   with a local account -> promise of the serialized 0x transaction."
  [account tx]
  (account-method! account "signTransaction" tx :sign-transaction))

(defn sign-authorization
  "EIP-7702 authorization signature with a local account (`{:contract-address
   :chain-id :nonce}`) -> promise of the signed authorization map."
  [account opts]
  (account-method! account "signAuthorization" opts :sign-authorization))

(defn sign-hash
  "Raw hash signature with a local account (`{:hash \"0x…\"}`) -> promise of a
   0x signature. No prefixing — use `sign-message` for EIP-191."
  [account opts]
  (account-method! account "sign" opts :sign))

;; ---------------------------------------------------------------------------
;; Keystore v3 decryption (Node crypto + viem keccak256)
;; ---------------------------------------------------------------------------

(defn- buffer-from-hex [s]
  (js-invoke js/Buffer "from" (str/replace s #"^0x" "") "hex"))

(defn- buffer->hex [b]
  (js-invoke b "toString" "hex"))

(defn- normalize-keystore
  "JSON string | kebab map | JS object -> kebab map with `:crypto`."
  [ks]
  (let [m (cond
            (string? ks) (util/->clj (js-invoke js/JSON "parse" ks))
            (map? ks) ks
            (object? ks) (util/->clj ks)
            :else (throw (util/argument-error "keystore must be a JSON string or a map" {})))]
    (cond-> m
      (and (nil? (:crypto m)) (some? (get m (keyword "Crypto")))) (assoc :crypto (get m (keyword "Crypto"))))))

(defn- derive-keystore-key!
  "Promise of the derived key Buffer for a keystore's `:crypto` section."
  [{:keys [kdf kdfparams]} password]
  (let [salt (buffer-from-hex (:salt kdfparams))
        dklen (or (:dklen kdfparams) 32)]
    (case kdf
      "scrypt"
      (let [n (:n kdfparams) r (:r kdfparams) pp (:p kdfparams)
            maxmem (max (* 32 1024 1024) (* 2 128 n r pp))]
        (p/create (fn [resolve reject]
                    (js-invoke crypto "scrypt" password salt dklen
                               (util/->js {:N n :r r :p pp :maxmem maxmem})
                               (fn [err key] (if err (reject err) (resolve key)))))))
      "pbkdf2"
      (do
        (when-not (= "hmac-sha256" (:prf kdfparams))
          (throw (util/argument-error (str "unsupported keystore prf " (:prf kdfparams)) {:prf (:prf kdfparams)})))
        (p/create (fn [resolve reject]
                    (js-invoke crypto "pbkdf2" password salt (:c kdfparams) dklen "sha256"
                               (fn [err key] (if err (reject err) (resolve key)))))))
      (throw (util/argument-error (str "unsupported keystore kdf " kdf) {:kdf kdf})))))

(defn account-from-keystore!
  "Decrypts a Web3 Secret Storage v3 keystore (JSON string or kebab map) with
   `password` and returns a promise of a local account. scrypt and pbkdf2
   (hmac-sha256) KDFs, aes-128-ctr cipher; MAC-checked. Uses Node's `crypto`
   and viem's `keccak256` / `privateKeyToAccount` only. Wrong password ->
   rejection with :viem-cljs/argument-error {:reason :mac-mismatch}. The
   decrypted key never leaves this fn (the account object keeps it in its
   own closure, as with `private-key->account`)."
  [keystore password]
  (promised
   :account-from-keystore
   (fn []
     (p/let [ks (normalize-keystore keystore)
             c (:crypto ks)
             _ (when (nil? c) (throw (util/argument-error "keystore has no crypto section" {})))
             _ (when (and (:version ks) (not= 3 (:version ks)))
                 (throw (util/argument-error (str "unsupported keystore version " (:version ks)) {:version (:version ks)})))
             _ (when-not (= "aes-128-ctr" (:cipher c))
                 (throw (util/argument-error (str "unsupported keystore cipher " (:cipher c)) {:cipher (:cipher c)})))
             derived (derive-keystore-key! c password)
             ciphertext (buffer-from-hex (:ciphertext c))
             mac (js-invoke viem "keccak256"
                            (js-invoke js/Buffer "concat" (array (js-invoke derived "subarray" 16 32) ciphertext)))]
       (when-not (= (str/replace mac #"^0x" "") (str/lower-case (str/replace (:mac c) #"^0x" "")))
         (throw (util/argument-error "keystore MAC mismatch (wrong password or corrupt file)" {:reason :mac-mismatch})))
       (let [decipher (js-invoke crypto "createDecipheriv" "aes-128-ctr"
                                 (js-invoke derived "subarray" 0 16)
                                 (buffer-from-hex (get-in c [:cipherparams :iv])))
             plain (js-invoke js/Buffer "concat" (array (js-invoke decipher "update" ciphertext)
                                                        (js-invoke decipher "final")))]
         (private-key->account (str "0x" (buffer->hex plain))))))
   identity))

;; ---------------------------------------------------------------------------
;; Option translation
;; ---------------------------------------------------------------------------

(def quantity-keys
  "Option keys coerced with `util/->bigint` (unless the value is a keyword
   block tag such as `:latest`)."
  #{:value :gas :gas-price :max-fee-per-gas :max-priority-fee-per-gas
    :max-fee-per-blob-gas :block-number :from-block :to-block :base-fee-per-gas
    :gas-limit :timestamp :balance :amount :gas-used})

(def ^:private opaque-keys
  "Subtrees left alone by the quantity walk (ABI-typed values, typed data)."
  #{:args :message :types :domain :primary-type :state :state-diff :code :data
    :bytecode :calldata :transport :nonce-manager :filter})

(defn- strip-? [k]
  (if (and (keyword? k) (str/ends-with? (name k) "?"))
    (keyword (namespace k) (subs (name k) 0 (dec (count (name k)))))
    k))

(defn parse-abi
  "Human-readable ABI (vector of signature strings) -> JSON ABI as kebab data
   (viem/abitype `parseAbi`). A vector of kebab maps is returned unchanged."
  [abi]
  (cond
    (and (sequential? abi) (every? string? abi))
    (util/->clj (guard :parse-abi #(js-invoke viem "parseAbi" (clj->js abi))))
    :else abi))

(defn abi->js
  "Any accepted ABI form -> JS ABI array: human-readable strings (parsed),
   kebab JSON ABI (re-cased), a JS array (as is). Cached per identical CLJS
   value would be the caller's job (`contract/bind` does it)."
  [abi]
  (cond
    (nil? abi) nil
    (array? abi) abi
    (and (sequential? abi) (every? string? abi))
    (guard :parse-abi #(js-invoke viem "parseAbi" (clj->js abi)))
    (sequential? abi) (util/->js abi)
    :else (throw (util/argument-error "unsupported :abi form" {:abi abi}))))

(defn abi-item->js
  "A single ABI item: human-readable string (`parseAbiItem`) or kebab map."
  [item]
  (cond
    (nil? item) nil
    (string? item) (guard :parse-abi-item #(js-invoke viem "parseAbiItem" item))
    (map? item) (util/->js item)
    :else item))

(defn- prepare-value [k v skip]
  (cond
    (nil? v) nil
    (contains? skip k) v
    (= :abi k) (abi->js v)
    (= :event k) (abi-item->js v)
    (= :events k) (if (and (sequential? v) (every? string? v)) (abi->js v) (util/->js v))
    (= :chain k) (chain-object v)
    (= :account k) (account-object v)
    (= :filter k) (if (map? v) (or (:filter v) v) v)
    (contains? opaque-keys k) v
    (and (contains? quantity-keys k) (not (keyword? v))) (util/->bigint v)
    :else v))

(defn- prepare-opts
  "Applies the translation rules that go beyond mechanical re-casing:
   `?`-stripping, quantity coercion, ABI / chain / account resolution —
   recursively through nested maps and vectors. Keys in `skip` are left
   untouched (per-action exceptions such as `setStorageAt`'s hex `value`)."
  ([x] (prepare-opts x #{}))
  ([x skip]
   (cond
     (map? x) (into {}
                    (keep (fn [[k v]]
                            (let [k (strip-? k)
                                  v (prepare-value k v skip)]
                              (when (some? v)
                                [k (if (or (contains? skip k)
                                           (contains? opaque-keys k)
                                           (contains? #{:abi :event :events :chain :account :filter} k))
                                     v
                                     (prepare-opts v skip))]))))
                    x)
     (sequential? x) (mapv #(prepare-opts % skip) x)
     :else x)))

(defn opts->js
  "Kebab option map -> viem parameter object (every rule of the option
   translation above). nil / empty -> empty object. `skip` (set of kebab
   keys) disables the special handling for those keys."
  ([opts] (opts->js opts #{}))
  ([opts skip] (util/->js (prepare-opts (or opts {}) skip))))

(defn opts->js-value
  "Like `opts->js` for any value (vector of maps, scalar, or map): applies
   the same translation rules; nil -> nil."
  [x]
  (util/->js (prepare-opts x)))

(def ^:private action-skip-keys
  "Per-action keys exempt from the generic translation: viem's `setStorageAt`
   takes `value` as a hex word (not a quantity)."
  {:set-storage-at #{:value}})

;; ---------------------------------------------------------------------------
;; Transports — data specs resolved at client construction
;; ---------------------------------------------------------------------------

(defn http
  "HTTP transport spec. `(http)` uses the chain's default public RPC (needs
   `:chain` on the client, else config-error); `(http url)`, `(http url
   {:batch true :timeout 10000 :retry-count 3 :retry-delay 150 :fetch-options
   {..} :key :name :methods {:include [..]} :raw …})`."
  ([] (http nil nil))
  ([url] (http url nil))
  ([url config] {:viem-cljs/transport :http :url url :config config}))

(defn web-socket
  "WebSocket transport spec: `(web-socket url {:keep-alive .. :reconnect ..
   :retry-count :timeout ..})`. The socket opens on the first request."
  ([] (web-socket nil nil))
  ([url] (web-socket url nil))
  ([url config] {:viem-cljs/transport :web-socket :url url :config config}))

(defn ipc
  "IPC transport spec (Node only, `viem/node`): `(ipc \"/tmp/geth.ipc\" config)`."
  ([path] (ipc path nil))
  ([path config] {:viem-cljs/transport :ipc :path path :config config}))

(defn custom
  "Custom (EIP-1193) transport spec over a request fn `(fn [{:keys [method
   params]}] result-or-promise)` — params arrive as CLJS data, the result may
   be CLJS data (converted) or a JS value; throw `(ex-info msg {:code -32601
   :message msg :data ..})` for a JSON-RPC error. Also accepts a
   `{:request fn}` map or a raw provider object. Config: `:key :name
   :retry-count :retry-delay :methods`."
  ([provider] (custom provider nil))
  ([provider config] {:viem-cljs/transport :custom :provider provider :config config}))

(defn fallback
  "Fallback transport spec over a vector of transport specs:
   `(fallback [(http a) (http b)] {:rank true :retry-count 3})`."
  ([transports] (fallback transports nil))
  ([transports config] {:viem-cljs/transport :fallback :transports transports :config config}))

(defn- shorthand->spec
  "`{:http url …cfg}` / `{:websocket url}` / `{:web-socket url}` / `{:ipc path}`
   / `{:custom f}` / `{:fallback [..]}` -> transport spec."
  [m]
  (let [cfg (fn [k] (not-empty (dissoc m k)))]
    (cond
      (contains? m :viem-cljs/transport) m
      (contains? m :http) (http (:http m) (cfg :http))
      (contains? m :websocket) (web-socket (:websocket m) (cfg :websocket))
      (contains? m :web-socket) (web-socket (:web-socket m) (cfg :web-socket))
      (contains? m :ipc) (ipc (:ipc m) (cfg :ipc))
      (contains? m :custom) (custom (:custom m) (cfg :custom))
      (contains? m :fallback) (fallback (:fallback m) (cfg :fallback))
      (contains? m :request) (custom m nil)
      :else (throw (util/config-error "unrecognized transport map (expected :http, :websocket, :ipc, :custom or :fallback)"
                                      {:reason :bad-transport :keys (vec (keys m))})))))

(defn- ->js-error
  "Makes a CLJS rejection from a custom request fn understandable to viem: an
   ex-info becomes a plain JS Error carrying its `:code` / `:data` (viem's
   RPC error classification reads `code`). A plain Error is needed because
   ExceptionInfo has a `cause` slot holding null, which viem's `walk`
   dereferences. Non-ex-info values pass through."
  [e]
  (if-let [d (ex-data e)]
    (let [err (js/Error. (or (:message d) (ex-message e)))]
      (when (some? (:code d)) (gobj/set err "code" (:code d)))
      (when (some? (:data d)) (gobj/set err "data" (util/->js (:data d))))
      err)
    e))

(defn- wrap-request-fn
  "CLJS request fn -> EIP-1193 provider object."
  [f]
  (util/->js
   {:request (fn [req]
               (-> (p/do (f {:method (util/jsget req "method")
                             :params (util/->clj (util/jsget req "params"))}))
                   (p/then (fn [r] (if (or (map? r) (sequential? r) (keyword? r)) (util/->js r) r)))
                   (p/catch (fn [e] (throw (->js-error e))))))}))

(defn transport-spec?
  "True for a transport spec produced by `http` / `web-socket` / `ipc` /
   `custom` / `fallback`."
  [x]
  (and (map? x) (contains? x :viem-cljs/transport)))

(defn build-transport
  "Transport spec (or shorthand map, or an already-built viem transport
   function) -> viem transport. `chain?` tells whether the client has a chain
   (an http / websocket transport without URL then falls back to the chain's
   default RPC; without a chain it is a :viem-cljs/config-error)."
  [spec chain?]
  (cond
    (js-fn? spec) spec
    (and (map? spec) (not (transport-spec? spec))) (build-transport (shorthand->spec spec) chain?)
    (not (transport-spec? spec))
    (throw (util/config-error "a :transport is required (http / web-socket / ipc / custom / fallback)"
                              {:reason :missing-transport}))
    :else
    (let [{:keys [config]} spec
          cfg (util/->js (or config {}))]
      (case (:viem-cljs/transport spec)
        :http (do (when (and (nil? (:url spec)) (not chain?))
                    (throw (util/config-error "http transport needs a URL or a client :chain (whose default RPC is then used)"
                                              {:reason :url-required :transport :http})))
                  (guard :http #(js-invoke viem "http" (:url spec) cfg)))
        :web-socket (do (when (and (nil? (:url spec)) (not chain?))
                          (throw (util/config-error "web-socket transport needs a URL or a client :chain"
                                                    {:reason :url-required :transport :web-socket})))
                        (guard :web-socket #(js-invoke viem "webSocket" (:url spec) cfg)))
        :ipc (do (when-not (string? (:path spec))
                   (throw (util/config-error "ipc transport needs a socket path" {:reason :path-required :transport :ipc})))
                 (guard :ipc #(js-invoke vnode "ipc" (:path spec) cfg)))
        :custom (let [provider (:provider spec)
                      provider (cond
                                 (fn? provider) (wrap-request-fn provider)
                                 (and (map? provider) (fn? (:request provider))) (wrap-request-fn (:request provider))
                                 (and (object? provider) (js-fn? (util/jsget provider "request"))) provider
                                 :else (throw (util/config-error "custom transport needs a request fn or {:request fn}"
                                                                 {:reason :bad-provider :transport :custom})))]
                  (guard :custom #(js-invoke viem "custom" provider cfg)))
        :fallback (let [ts (:transports spec)]
                    (when (empty? ts)
                      (throw (util/config-error "fallback transport needs at least one transport"
                                                {:reason :empty-fallback :transport :fallback})))
                    (guard :fallback #(js-invoke viem "fallback"
                                                 (to-array (map (fn [t] (build-transport t chain?)) ts))
                                                 cfg)))))))

;; ---------------------------------------------------------------------------
;; Clients
;; ---------------------------------------------------------------------------

(defn client?
  "True when `x` is a viem client (has `request` and `transport`)."
  [x]
  (and (object? x)
       (js-fn? (util/jsget x "request"))
       (some? (util/jsget x "transport"))))

(defn- client-config
  "Kebab client opts -> viem config object (transport / chain / account resolved)."
  [{:keys [transport chain account] :as opts}]
  (let [chain-obj (chain-object chain)
        cfg (-> (dissoc opts :transport :chain :account)
                (assoc :transport (build-transport transport (some? chain-obj))
                       :chain chain-obj
                       :account (account-object account)))]
    (opts->js cfg)))

(defn- make-client [ctor opts action]
  (guard action #(js-invoke viem ctor (client-config opts))))

(defn client
  "Bare client (viem `createClient`): `{:transport spec :chain :account :batch
   :cache-time :polling-interval :ccip-read :data-suffix :key :name}`. Actions
   from `viem-cljs.actions` work on any client kind."
  [opts]
  (make-client "createClient" opts :create-client))

(defn public-client
  "Public client (viem `createPublicClient`): `{:transport :chain :batch
   {:multicall true} :cache-time :polling-interval :ccip-read :key :name}`."
  [opts]
  (make-client "createPublicClient" opts :create-public-client))

(defn wallet-client
  "Wallet client (viem `createWalletClient`): `{:transport :chain :account
   (local account object | address | {:address ..}) :cache-time
   :polling-interval :ccip-read :key :name}`."
  [opts]
  (make-client "createWalletClient" opts :create-wallet-client))

(defn test-client
  "Test client for anvil / hardhat / ganache (viem `createTestClient`):
   `{:transport :mode (:anvil default | :hardhat | :ganache) :chain :account
   :cache-time :polling-interval :key :name}`."
  [opts]
  (make-client "createTestClient" (update opts :mode #(or % :anvil)) :create-test-client))

(defn client-chain
  "The client's chain object (opaque), nil when none."
  [client]
  (util/jsget client "chain"))

(defn client-account
  "The client's account object (opaque), nil when none."
  [client]
  (util/jsget client "account"))

(defn client-info
  "Data view of a client: `{:key :name :type :uid :mode? :chain-id :chain-name
   :chain (canonical keyword or nil) :account (account->map) :transport-type
   :transport-key :polling-interval :cache-time}`."
  [client]
  (let [c (client-chain client)
        id (some-> c (util/jsget "id"))]
    (cond-> {:key (util/jsget client "key")
             :name (util/jsget client "name")
             :type (util/jsget client "type")
             :uid (util/jsget client "uid")
             :chain-id id
             :chain-name (some-> c (util/jsget "name"))
             :chain (some-> id chain-id->keyword)
             :account (account->map (client-account client))
             :transport-type (util/jsget client "transport" "type")
             :transport-key (util/jsget client "transport" "key")
             :polling-interval (util/jsget client "pollingInterval")
             :cache-time (util/jsget client "cacheTime")}
      (some? (util/jsget client "mode")) (assoc :mode (util/jsget client "mode")))))

(defn request!
  "Raw JSON-RPC through the client's transport: `(request! client {:method
   \"eth_blockNumber\" :params []})` -> promise of the result as kebab data
   (hex quantities stay strings — this is the raw escape hatch)."
  [client {:keys [method params]}]
  (promised :request
            (fn []
              (when-not (client? client)
                (throw (util/argument-error "request! needs a client" {:action :request})))
              (js-invoke client "request" (util/->js {:method method :params (or params [])})))
            util/->clj))

;; ---------------------------------------------------------------------------
;; Action dispatch (tree-shakable shape, client first)
;; ---------------------------------------------------------------------------

(defn- action-fn [kw]
  (let [n (util/kebab->camel kw)]
    (or (export n va vsiwe vens)
        (throw (util/argument-error (str "unknown viem action " n) {:action kw})))))

(defn- reattach-raw
  "Puts the raw `account` / `chain` objects back into a converted result (and
   its `:request`) so simulate/prepare outputs feed the next action as
   opaque objects instead of re-cased maps."
  [raw m]
  (letfn [(fix [raw m]
            (cond-> m
              (and (map? m) (object? (util/jsget raw "account"))) (assoc :account (util/jsget raw "account"))
              (and (map? m) (object? (util/jsget raw "chain"))) (assoc :chain (util/jsget raw "chain"))))]
    (let [m (fix raw m)]
      (if (and (map? m) (contains? m :request) (object? (util/jsget raw "request")))
        (update m :request (fn [r] (fix (util/jsget raw "request") r)))
        m))))

(defn- filter-result
  "Filter objects hold the transport request fn: keep them opaque as
   `{:id .. :type .. :filter <raw>}`."
  [raw]
  {:id (util/jsget raw "id")
   :type (util/jsget raw "type")
   :filter raw})

(def ^:private result-fixups
  {:simulate-contract reattach-raw
   :prepare-transaction-request reattach-raw
   :fill-transaction reattach-raw
   :create-block-filter (fn [raw _] (filter-result raw))
   :create-event-filter (fn [raw _] (filter-result raw))
   :create-contract-event-filter (fn [raw _] (filter-result raw))
   :create-pending-transaction-filter (fn [raw _] (filter-result raw))})

(defn- convert-result [kw raw]
  (let [m (util/->clj raw)]
    (if-let [fix (get result-fixups kw)]
      (fix raw m)
      m)))

(defn action!
  "Invokes the viem action named by the kebab keyword `kw` (looked up in
   `viem/actions`, then `viem/siwe`, then `viem/ens`) with `client` and the
   translated `opts` -> promesa promise of the converted result. Rejections
   are wrapped viem-cljs errors tagged with `:action kw`."
  ([kw client] (action! kw client nil))
  ([kw client opts]
   (promised kw
             (fn []
               (when-not (client? client)
                 (throw (util/argument-error (str (util/kebab->camel kw) " needs a client as first argument") {:action kw})))
               ((action-fn kw) client (opts->js opts (get action-skip-keys kw #{}))))
             (partial convert-result kw))))

(defn action-scalar!
  "Like `action!` for the actions whose second argument is a positional
   scalar (`setAutomine`, `setLoggingEnabled`, `setRpcUrl`)."
  [kw client value]
  (promised kw
            (fn []
              (when-not (client? client)
                (throw (util/argument-error (str (util/kebab->camel kw) " needs a client as first argument") {:action kw})))
              ((action-fn kw) client value))
            (partial convert-result kw)))

(defn action-with-callbacks!
  "`action!` for promise-returning actions that also take callbacks (e.g.
   `waitForTransactionReceipt`'s `onReplaced`): `callbacks` is a map of
   kebab callback key -> CLJS fn; each receives its arguments converted with
   `->clj`."
  [kw client opts callbacks]
  (action! kw client
           (reduce-kv (fn [m k f]
                        (assoc m k (fn [& args] (apply f (map util/->clj args)))))
                      opts
                      callbacks)))

;; ---------------------------------------------------------------------------
;; Watchers: viem callbacks -> CLJS handlers; returns unwatch
;; ---------------------------------------------------------------------------

(defn watch!
  "Starts the viem watcher named by `kw` (`:watch-block-number`, `:watch-blocks`,
   `:watch-block-headers`, `:watch-event`, `:watch-contract-event`,
   `:watch-pending-transactions`) with translated `opts` and `handlers`, a
   map of kebab callback key (`:on-block-number`, `:on-logs`, `:on-error` …)
   to CLJS fn receiving `->clj`-converted arguments (errors arrive wrapped).
   Returns the `unwatch` thunk. `viem-cljs.events` turns this into channels."
  [kw client opts handlers]
  (when-not (client? client)
    (throw (util/argument-error (str (util/kebab->camel kw) " needs a client as first argument") {:action kw})))
  (let [f (action-fn kw)
        js-handlers (reduce-kv
                     (fn [m k h]
                       (assoc m k
                              (if (= :on-error k)
                                (fn [e] (h (util/viem-error e kw)))
                                (fn [& args] (apply h (map util/->clj args))))))
                     {}
                     handlers)]
    (guard kw #(f client (opts->js (merge opts js-handlers))))))

;; ---------------------------------------------------------------------------
;; Utility dispatch: pure viem fns by name
;; ---------------------------------------------------------------------------

(defn- util-fn [fname]
  (or (export fname vutils viem vens vacc vsiwe)
      (throw (util/argument-error (str "unknown viem utility " fname) {:utility fname}))))

(defn invoke-util
  "Calls the viem utility `fname` (camelCase string, looked up in
   `viem/utils`, `viem`, `viem/ens`, `viem/accounts`, `viem/siwe`) with `args`
   converted by `util/->js` (maps -> camel objects, keywords -> strings,
   vectors -> arrays; BigInt / hex / Uint8Array untouched). A synchronous
   result is converted with `->clj`; a promise result becomes a promesa
   promise of the converted value. Throws / rejects wrapped errors."
  [fname & args]
  (let [f (util-fn fname)
        action (util/camel->kebab fname)
        r (guard action #(apply f (map util/->js args)))]
    (if (thenable? r)
      (wrap-promise r action util/->clj)
      (util/->clj r))))

(defn invoke-util-raw
  "Like `invoke-util` but returns the raw JS value (for opaque results such
   as HDKey or Uint8Array-returning fns where `->clj` must not touch the
   value). Args are still converted."
  [fname & args]
  (let [f (util-fn fname)]
    (guard (util/camel->kebab fname) #(apply f (map util/->js args)))))

(defn constant
  "A viem top-level constant by camelCase name (`\"zeroAddress\"`,
   `\"maxUint256\"`, `\"erc20Abi\"` …) converted with `->clj`."
  [cname]
  (let [v (export cname viem vutils)]
    (when (nil? v)
      (throw (util/argument-error (str "unknown viem constant " cname) {:constant cname})))
    (util/->clj v)))
