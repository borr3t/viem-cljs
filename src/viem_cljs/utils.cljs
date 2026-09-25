(ns viem-cljs.utils
  "The pure viem utilities (inventory 7, Tier 1): units, address, hash,
   signature, encoding, data, transaction, typed data, authorization, ENS
   hashing, constants, BigInt-safe `stringify` — plus the BigInt arithmetic
   helpers the house rules require (`big`, `add`, `sub`, `mul`, `div`, `cmp`
   …: wei amounts are BigInt end to end and must never meet CLJS numbers in
   `+`/`inc`). No `!`: nothing here touches the network (the `recover-*` /
   `verify-*` fns return promises because viem's do, but they are local).
   Every viem throw is re-thrown as a viem-cljs ex-info. Pure ClojureScript:
   interop lives in `viem-cljs.core`."
  (:refer-clojure :exclude [max min mod concat zero?])
  (:require [viem-cljs.core :as core]
            [viem-cljs.util :as util]))

;; ---------------------------------------------------------------------------
;; BigInt helpers (inventory 12.1 — helpers-only arithmetic)
;; ---------------------------------------------------------------------------

(defn big
  "Coerces to BigInt: BigInt, safe integer, decimal string or 0x hex string
   (`util/->bigint`). nil -> nil; anything else -> :viem-cljs/argument-error."
  [x]
  (util/->bigint x))

(defn bigint?
  "True for a JS BigInt."
  [x]
  (util/bigint? x))

(defn bigint->number
  "BigInt -> number when it is a safe integer, else nil (never lossy)."
  [b]
  (util/bigint->number b))

(defn bigint->string
  "Decimal string of a BigInt."
  [b]
  (util/bigint->string b))

(defn add
  "BigInt sum of the (coercible) arguments."
  [& xs]
  (reduce (fn [a b] (+ a b)) (big 0) (map big xs)))

(defn sub
  "BigInt `a - b - …`."
  [a & xs]
  (reduce (fn [acc x] (- acc x)) (big a) (map big xs)))

(defn mul
  "BigInt product of the (coercible) arguments."
  [& xs]
  (reduce (fn [a b] (* a b)) (big 1) (map big xs)))

(defn div
  "BigInt integer division `a / b` (truncates toward zero)."
  [a b]
  (/ (big a) (big b)))

(defn mod
  "BigInt remainder `a % b` (sign follows the dividend, as in JS)."
  [a b]
  (js-mod (big a) (big b)))

(defn pow
  "BigInt `base ^ exp` (exp a non-negative integer) — square-and-multiply."
  [base exp]
  (let [two (big 2) zero (big 0) one (big 1)]
    (loop [b (big base) e (big exp) acc one]
      (cond
        (< e zero) (throw (util/argument-error "pow: negative exponent" {:exp exp}))
        (= e zero) acc
        :else (recur (* b b) (/ e two) (if (= one (js-mod e two)) (* acc b) acc))))))

(defn neg
  "BigInt negation."
  [x]
  (- (big x)))

(defn cmp
  "-1, 0 or 1 comparing two coercible values as BigInt."
  [a b]
  (let [a (big a) b (big b)]
    (cond (< a b) -1 (> a b) 1 :else 0)))

(defn lt?
  "a < b as BigInt."
  [a b] (< (big a) (big b)))

(defn lte?
  "a <= b as BigInt."
  [a b] (<= (big a) (big b)))

(defn gt?
  "a > b as BigInt."
  [a b] (> (big a) (big b)))

(defn gte?
  "a >= b as BigInt."
  [a b] (>= (big a) (big b)))

(defn zero?
  "True when the coercible value is 0."
  [x] (= (big 0) (big x)))

(defn max
  "The largest of the coercible values, as BigInt."
  [x & xs]
  (reduce (fn [a b] (if (> b a) b a)) (big x) (map big xs)))

(defn min
  "The smallest of the coercible values, as BigInt."
  [x & xs]
  (reduce (fn [a b] (if (< b a) b a)) (big x) (map big xs)))

;; ---------------------------------------------------------------------------
;; Units
;; ---------------------------------------------------------------------------

(defn format-units
  "BigInt (or coercible) -> decimal string with `decimals` places:
   (format-units 1234567 6) -> \"1.234567\"."
  [value decimals]
  (core/invoke-util "formatUnits" (big value) decimals))

(defn parse-units
  "Decimal string -> BigInt scaled by `decimals`: (parse-units \"1.5\" 6) ->
   1500000N. A CLJS number input is coerced to its string form first."
  [value decimals]
  (core/invoke-util "parseUnits" (str value) decimals))

(defn parse-ether
  "\"1\" -> 1000000000000000000N wei. Optional `unit` :wei | :gwei (default :wei)."
  ([value] (core/invoke-util "parseEther" (str value)))
  ([value unit] (core/invoke-util "parseEther" (str value) unit)))

(defn format-ether
  "Wei (BigInt or coercible) -> ether decimal string. Optional `unit`."
  ([wei] (core/invoke-util "formatEther" (big wei)))
  ([wei unit] (core/invoke-util "formatEther" (big wei) unit)))

(defn parse-gwei
  "\"1\" -> 1000000000N wei."
  ([value] (core/invoke-util "parseGwei" (str value)))
  ([value unit] (core/invoke-util "parseGwei" (str value) unit)))

(defn format-gwei
  "Wei -> gwei decimal string."
  ([wei] (core/invoke-util "formatGwei" (big wei)))
  ([wei unit] (core/invoke-util "formatGwei" (big wei) unit)))

;; ---------------------------------------------------------------------------
;; Address
;; ---------------------------------------------------------------------------

(defn get-address
  "EIP-55 checksummed address (throws a wrapped `InvalidAddressError` on bad
   input). Optional `chain-id` for EIP-1191 checksums."
  ([address] (core/invoke-util "getAddress" address))
  ([address chain-id] (core/invoke-util "getAddress" address chain-id)))

(defn checksum-address
  "Checksums without validating (viem `checksumAddress`)."
  ([address] (core/invoke-util "checksumAddress" address))
  ([address chain-id] (core/invoke-util "checksumAddress" address chain-id)))

(defn address?
  "True for a valid address; strict (default) also rejects a wrong mixed-case
   checksum. `(address? s {:strict? false})` accepts any case."
  ([s] (boolean (core/invoke-util "isAddress" s)))
  ([s opts] (boolean (core/invoke-util "isAddress" s (core/opts->js opts)))))

(defn address-equal?
  "Case-insensitive address equality (viem `isAddressEqual`)."
  [a b]
  (boolean (core/invoke-util "isAddressEqual" a b)))

(defn contract-address
  "Deterministic deployment address: `{:from :nonce}` (CREATE) or `{:from
   :salt :bytecode | :bytecode-hash :opcode \"CREATE2\"}`."
  [opts]
  (core/invoke-util "getContractAddress" (core/opts->js opts)))

(defn create-address
  "CREATE address for `{:from :nonce}`."
  [opts]
  (core/invoke-util "getCreateAddress" (core/opts->js opts)))

(defn create2-address
  "CREATE2 address for `{:from :salt :bytecode | :bytecode-hash}`."
  [opts]
  (core/invoke-util "getCreate2Address" (core/opts->js opts)))

;; ---------------------------------------------------------------------------
;; Hash
;; ---------------------------------------------------------------------------

(defn keccak256
  "keccak-256 of a hex string or bytes -> hex (or bytes with `to` :bytes)."
  ([v] (core/invoke-util "keccak256" v))
  ([v to] (core/invoke-util "keccak256" v to)))

(defn sha256
  "SHA-256 of hex or bytes -> hex (or bytes with `to` :bytes)."
  ([v] (core/invoke-util "sha256" v))
  ([v to] (core/invoke-util "sha256" v to)))

(defn ripemd160
  "RIPEMD-160 of hex or bytes -> hex."
  ([v] (core/invoke-util "ripemd160" v))
  ([v to] (core/invoke-util "ripemd160" v to)))

(defn hash-message
  "EIP-191 personal-message hash of a string or `{:raw hex-or-bytes}`."
  ([message] (core/invoke-util "hashMessage" (core/opts->js-value message)))
  ([message to] (core/invoke-util "hashMessage" (core/opts->js-value message) to)))

(defn hash-typed-data
  "EIP-712 hash of `{:domain :types :primary-type :message}`."
  [typed-data]
  (core/invoke-util "hashTypedData" (core/opts->js typed-data)))

(defn hash-domain
  "EIP-712 domain separator hash of `{:domain :types}`."
  [opts]
  (core/invoke-util "hashDomain" (core/opts->js opts)))

(defn hash-struct
  "EIP-712 struct hash of `{:data :primary-type :types}`."
  [opts]
  (core/invoke-util "hashStruct" (core/opts->js opts)))

(defn hash?
  "True for a 32-byte 0x hash string."
  [s]
  (boolean (core/invoke-util "isHash" s)))

(defn- sig-arg
  "Signature strings (\"transfer(address,uint256)\" or human-readable
   \"function transfer(address to, uint256 amount)\") pass verbatim; ABI item
   maps are re-cased."
  [x]
  (if (map? x) (core/opts->js-value x) x))

(defn function-selector
  "4-byte selector of a function signature string (\"transfer(address,uint256)\"
   or \"function transfer(address to, uint256 amount)\") or ABI item map."
  [fn-or-item]
  (core/invoke-util "toFunctionSelector" (sig-arg fn-or-item)))

(defn event-selector
  "32-byte topic0 of an event signature string or ABI item map."
  [event-or-item]
  (core/invoke-util "toEventSelector" (sig-arg event-or-item)))

(defn function-signature
  "Canonical signature string of a function ABI item / human-readable string."
  [fn-or-item]
  (core/invoke-util "toFunctionSignature" (sig-arg fn-or-item)))

(defn event-signature
  "Canonical signature string of an event ABI item / human-readable string."
  [event-or-item]
  (core/invoke-util "toEventSignature" (sig-arg event-or-item)))

(defn function-hash
  "keccak256 of the function signature (full 32 bytes)."
  [fn-or-item]
  (core/invoke-util "toFunctionHash" (sig-arg fn-or-item)))

(defn event-hash
  "keccak256 of the event signature (= topic0)."
  [event-or-item]
  (core/invoke-util "toEventHash" (sig-arg event-or-item)))

;; ---------------------------------------------------------------------------
;; Signatures (pure EOA versions; promises because viem's are async)
;; ---------------------------------------------------------------------------

(defn recover-address
  "Promise of the signer address of `{:hash :signature}`."
  [opts]
  (core/invoke-util "recoverAddress" (core/opts->js opts)))

(defn recover-message-address
  "Promise of the signer address of an EIP-191 `{:message :signature}`."
  [opts]
  (core/invoke-util "recoverMessageAddress" (core/opts->js opts)))

(defn recover-typed-data-address
  "Promise of the signer address of EIP-712 `{:domain :types :primary-type
   :message :signature}`."
  [opts]
  (core/invoke-util "recoverTypedDataAddress" (core/opts->js opts)))

(defn recover-public-key
  "Promise of the uncompressed public key for `{:hash :signature}`."
  [opts]
  (core/invoke-util "recoverPublicKey" (core/opts->js opts)))

(defn recover-transaction-address
  "Promise of the sender of a serialized signed transaction
   `{:serialized-transaction hex}`."
  [opts]
  (core/invoke-util "recoverTransactionAddress" (core/opts->js opts)))

(defn verify-message
  "Promise of true when `{:address :message :signature}` is a valid EOA
   EIP-191 signature (pure; for ERC-1271/6492 use `actions/verify-message!`)."
  [opts]
  (core/invoke-util "verifyMessage" (core/opts->js opts)))

(defn verify-typed-data
  "Promise of true when `{:address :domain :types :primary-type :message
   :signature}` is a valid EOA EIP-712 signature (pure)."
  [opts]
  (core/invoke-util "verifyTypedData" (core/opts->js opts)))

(defn verify-hash
  "Promise of true when `{:address :hash :signature}` verifies (pure EOA)."
  [opts]
  (core/invoke-util "verifyHash" (core/opts->js opts)))

(defn parse-signature
  "65-byte hex signature -> `{:r :s :v :y-parity}` (BigInt `v`). Bad input
   throws a wrapped plain `Error`."
  [hex]
  (core/invoke-util "parseSignature" hex))

(defn serialize-signature
  "`{:r :s :v | :y-parity}` -> 65-byte hex (or bytes with `to` :bytes)."
  ([sig] (core/invoke-util "serializeSignature" (core/opts->js sig)))
  ([sig to] (core/invoke-util "serializeSignature" (core/opts->js sig) to)))

(defn parse-compact-signature
  "EIP-2098 64-byte hex -> `{:r :y-parity-and-s}`."
  [hex]
  (core/invoke-util "parseCompactSignature" hex))

(defn serialize-compact-signature
  "`{:r :y-parity-and-s}` -> EIP-2098 hex."
  [sig]
  (core/invoke-util "serializeCompactSignature" (core/opts->js sig)))

(defn compact-signature->signature
  "EIP-2098 compact map -> full `{:r :s :y-parity}`."
  [sig]
  (core/invoke-util "compactSignatureToSignature" (core/opts->js sig)))

(defn signature->compact-signature
  "Full signature map -> EIP-2098 compact map."
  [sig]
  (core/invoke-util "signatureToCompactSignature" (core/opts->js sig)))

(defn erc6492-signature?
  "True when the hex signature carries the ERC-6492 suffix."
  [hex]
  (boolean (core/invoke-util "isErc6492Signature" hex)))

(defn parse-erc6492-signature
  "ERC-6492 hex -> `{:address :data :signature}` (or `{:signature}` when plain)."
  [hex]
  (core/invoke-util "parseErc6492Signature" hex))

(defn serialize-erc6492-signature
  "`{:address :data :signature}` -> ERC-6492 wrapped hex."
  [opts]
  (core/invoke-util "serializeErc6492Signature" (core/opts->js opts)))

(defn erc8010-signature?
  "True when the hex signature carries the ERC-8010 suffix."
  [hex]
  (boolean (core/invoke-util "isErc8010Signature" hex)))

(defn parse-erc8010-signature
  "ERC-8010 hex -> its parts as a kebab map."
  [hex]
  (core/invoke-util "parseErc8010Signature" hex))

(defn serialize-erc8010-signature
  "Kebab map -> ERC-8010 wrapped hex."
  [opts]
  (core/invoke-util "serializeErc8010Signature" (core/opts->js opts)))

;; ---------------------------------------------------------------------------
;; Encoding (hex / bytes / rlp)
;; ---------------------------------------------------------------------------

(defn to-hex
  "string | number | BigInt | boolean | bytes -> hex; opts `{:size n}` pads."
  ([v] (core/invoke-util "toHex" v))
  ([v opts] (core/invoke-util "toHex" v (core/opts->js opts))))

(defn number->hex
  "number | BigInt -> hex; opts `{:size n :signed? true}`."
  ([v] (core/invoke-util "numberToHex" v))
  ([v opts] (core/invoke-util "numberToHex" v (core/opts->js opts))))

(defn string->hex
  "UTF-8 string -> hex; opts `{:size n}`."
  ([s] (core/invoke-util "stringToHex" s))
  ([s opts] (core/invoke-util "stringToHex" s (core/opts->js opts))))

(defn bytes->hex
  "Uint8Array -> hex; opts `{:size n}`."
  ([b] (core/invoke-util "bytesToHex" b))
  ([b opts] (core/invoke-util "bytesToHex" b (core/opts->js opts))))

(defn bool->hex
  "boolean -> \"0x1\" / \"0x0\"; opts `{:size n}`."
  ([b] (core/invoke-util "boolToHex" b))
  ([b opts] (core/invoke-util "boolToHex" b (core/opts->js opts))))

(defn from-hex
  "hex -> value of kind `to` (:string :bigint :number :bytes :boolean) or an
   opts map `{:to :number :size n}`."
  [hex to]
  (core/invoke-util "fromHex" hex (if (map? to) (core/opts->js to) to)))

(defn hex->bigint
  "hex -> BigInt; opts `{:size n :signed? true}`."
  ([hex] (core/invoke-util "hexToBigInt" hex))
  ([hex opts] (core/invoke-util "hexToBigInt" hex (core/opts->js opts))))

(defn hex->number
  "hex -> number (throws a wrapped error beyond the safe range)."
  ([hex] (core/invoke-util "hexToNumber" hex))
  ([hex opts] (core/invoke-util "hexToNumber" hex (core/opts->js opts))))

(defn hex->string
  "hex -> UTF-8 string."
  ([hex] (core/invoke-util "hexToString" hex))
  ([hex opts] (core/invoke-util "hexToString" hex (core/opts->js opts))))

(defn hex->bytes
  "hex -> Uint8Array."
  ([hex] (core/invoke-util-raw "hexToBytes" hex))
  ([hex opts] (core/invoke-util-raw "hexToBytes" hex (core/opts->js opts))))

(defn hex->bool
  "\"0x1\" -> true, \"0x0\" -> false."
  ([hex] (core/invoke-util "hexToBool" hex))
  ([hex opts] (core/invoke-util "hexToBool" hex (core/opts->js opts))))

(defn to-bytes
  "string | number | BigInt | boolean | hex -> Uint8Array."
  ([v] (core/invoke-util-raw "toBytes" v))
  ([v opts] (core/invoke-util-raw "toBytes" v (core/opts->js opts))))

(defn from-bytes
  "Uint8Array -> value of kind `to` (:string :bigint :number :hex :boolean)
   or an opts map."
  [b to]
  (core/invoke-util "fromBytes" b (if (map? to) (core/opts->js to) to)))

(defn number->bytes
  "number | BigInt -> Uint8Array."
  ([v] (core/invoke-util-raw "numberToBytes" v))
  ([v opts] (core/invoke-util-raw "numberToBytes" v (core/opts->js opts))))

(defn string->bytes
  "UTF-8 string -> Uint8Array."
  ([s] (core/invoke-util-raw "stringToBytes" s))
  ([s opts] (core/invoke-util-raw "stringToBytes" s (core/opts->js opts))))

(defn bool->bytes
  "boolean -> Uint8Array."
  ([b] (core/invoke-util-raw "boolToBytes" b))
  ([b opts] (core/invoke-util-raw "boolToBytes" b (core/opts->js opts))))

(defn bytes->bigint
  "Uint8Array -> BigInt."
  ([b] (core/invoke-util "bytesToBigInt" b))
  ([b opts] (core/invoke-util "bytesToBigInt" b (core/opts->js opts))))

(defn bytes->number
  "Uint8Array -> number."
  ([b] (core/invoke-util "bytesToNumber" b))
  ([b opts] (core/invoke-util "bytesToNumber" b (core/opts->js opts))))

(defn bytes->string
  "Uint8Array -> UTF-8 string."
  ([b] (core/invoke-util "bytesToString" b))
  ([b opts] (core/invoke-util "bytesToString" b (core/opts->js opts))))

(defn bytes->bool
  "Uint8Array -> boolean."
  ([b] (core/invoke-util "bytesToBool" b))
  ([b opts] (core/invoke-util "bytesToBool" b (core/opts->js opts))))

(defn to-rlp
  "RLP-encode a hex string, bytes, or nested vector of them -> hex (or bytes
   with `to` :bytes)."
  ([v] (core/invoke-util "toRlp" v))
  ([v to] (core/invoke-util "toRlp" v to)))

(defn from-rlp
  "RLP-decode hex or bytes -> nested vectors of `to` (:hex default | :bytes)."
  ([v] (core/invoke-util "fromRlp" v))
  ([v to] (core/invoke-util "fromRlp" v to)))

(defn hex->rlp
  "RLP-encode hex -> hex."
  ([v] (core/invoke-util "hexToRlp" v))
  ([v to] (core/invoke-util "hexToRlp" v to)))

(defn bytes->rlp
  "RLP-encode bytes -> hex (or bytes with `to`)."
  ([v] (core/invoke-util "bytesToRlp" v))
  ([v to] (core/invoke-util "bytesToRlp" v to)))

;; ---------------------------------------------------------------------------
;; Data (hex / bytes manipulation)
;; ---------------------------------------------------------------------------

(defn concat
  "Concatenates hex strings (or byte arrays) — vector in."
  [values]
  (core/invoke-util "concat" values))

(defn concat-hex
  "Concatenates hex strings."
  [values]
  (core/invoke-util "concatHex" values))

(defn concat-bytes
  "Concatenates Uint8Arrays -> Uint8Array."
  [values]
  (core/invoke-util-raw "concatBytes" values))

(defn pad
  "Pads hex or bytes to `{:size 32 :dir :left | :right}` (default left, 32)."
  ([v] (core/invoke-util "pad" v))
  ([v opts] (core/invoke-util "pad" v (core/opts->js opts))))

(defn pad-hex
  "Pads a hex string."
  ([v] (core/invoke-util "padHex" v))
  ([v opts] (core/invoke-util "padHex" v (core/opts->js opts))))

(defn pad-bytes
  "Pads a Uint8Array -> Uint8Array."
  ([v] (core/invoke-util-raw "padBytes" v))
  ([v opts] (core/invoke-util-raw "padBytes" v (core/opts->js opts))))

(defn slice
  "Byte slice `[start, end)` of hex or bytes; opts `{:strict? true}`."
  ([v start] (core/invoke-util "slice" v start))
  ([v start end] (core/invoke-util "slice" v start end))
  ([v start end opts] (core/invoke-util "slice" v start end (core/opts->js opts))))

(defn slice-hex
  "Byte slice of a hex string."
  ([v start] (core/invoke-util "sliceHex" v start))
  ([v start end] (core/invoke-util "sliceHex" v start end)))

(defn slice-bytes
  "Slice of a Uint8Array -> Uint8Array."
  ([v start] (core/invoke-util-raw "sliceBytes" v start))
  ([v start end] (core/invoke-util-raw "sliceBytes" v start end)))

(defn size
  "Byte length of hex or bytes."
  [v]
  (core/invoke-util "size" v))

(defn trim
  "Strips leading (default) or trailing zero bytes: opts `{:dir :right}`."
  ([v] (core/invoke-util "trim" v))
  ([v opts] (core/invoke-util "trim" v (core/opts->js opts))))

(defn hex?
  "True for a 0x hex string; `{:strict? false}` skips the digit check."
  ([v] (boolean (core/invoke-util "isHex" v)))
  ([v opts] (boolean (core/invoke-util "isHex" v (core/opts->js opts)))))

(defn bytes?
  "True for a Uint8Array."
  [v]
  (boolean (core/invoke-util "isBytes" v)))

;; ---------------------------------------------------------------------------
;; Transactions
;; ---------------------------------------------------------------------------

(defn serialize-transaction
  "Kebab transaction map (`{:chain-id :to :value :gas :nonce :type :eip1559
   :max-fee-per-gas …}`, quantities coerced) + optional `{:r :s :y-parity}`
   signature -> serialized hex."
  ([tx] (core/invoke-util "serializeTransaction" (core/opts->js tx)))
  ([tx signature] (core/invoke-util "serializeTransaction" (core/opts->js tx) (core/opts->js signature))))

(defn parse-transaction
  "Serialized hex -> kebab transaction map (BigInt quantities, `:type`
   string)."
  [hex]
  (core/invoke-util "parseTransaction" hex))

(defn transaction-type
  "\"legacy\" | \"eip2930\" | \"eip1559\" | \"eip4844\" | \"eip7702\" inferred
   from a kebab transaction map's fields."
  [tx]
  (core/invoke-util "getTransactionType" (core/opts->js tx)))

(defn serialized-transaction-type
  "The type string of a serialized transaction hex."
  [hex]
  (core/invoke-util "getSerializedTransactionType" hex))

(defn serialize-access-list
  "Access list vector `[{:address :storage-keys [..]}]` -> RLP-ready tuples."
  [access-list]
  (core/invoke-util "serializeAccessList" (core/opts->js-value access-list)))

(defn serialize-authorization-list
  "EIP-7702 authorization list (kebab maps) -> RLP-ready tuples."
  [authorization-list]
  (core/invoke-util "serializeAuthorizationList" (core/opts->js-value authorization-list)))

(defn assert-transaction
  "Validates a kebab transaction map for its `type` (:eip1559 | :eip2930 |
   :legacy); returns nil or throws a wrapped viem error."
  [type tx]
  (case type
    :eip1559 (core/invoke-util "assertTransactionEIP1559" (core/opts->js tx))
    :eip2930 (core/invoke-util "assertTransactionEIP2930" (core/opts->js tx))
    :legacy (core/invoke-util "assertTransactionLegacy" (core/opts->js tx))
    (throw (util/argument-error (str "unknown transaction type " type) {:transaction-type type})))
  nil)

;; ---------------------------------------------------------------------------
;; Typed data (EIP-712) and authorizations (EIP-7702)
;; ---------------------------------------------------------------------------

(defn validate-typed-data
  "Throws a wrapped viem error when `{:domain :types :primary-type :message}`
   is inconsistent; returns nil otherwise."
  [typed-data]
  (core/invoke-util "validateTypedData" (core/opts->js typed-data))
  nil)

(defn serialize-typed-data
  "Typed data map -> the JSON string wallets accept (`eth_signTypedData_v4`)."
  [typed-data]
  (core/invoke-util "serializeTypedData" (core/opts->js typed-data)))

(defn types-for-eip712-domain
  "The `EIP712Domain` type fields implied by a `{:domain ..}` map."
  [opts]
  (core/invoke-util "getTypesForEIP712Domain" (core/opts->js opts)))

(defn domain-separator
  "EIP-712 domain separator hash of `{:domain ..}`."
  [opts]
  (core/invoke-util "domainSeparator" (core/opts->js opts)))

(defn hash-authorization
  "Hash of an EIP-7702 authorization `{:contract-address :chain-id :nonce}`."
  [auth]
  (core/invoke-util "hashAuthorization" (core/opts->js auth)))

(defn verify-authorization
  "Promise of true when `{:address :authorization :signature?}` verifies."
  [opts]
  (core/invoke-util "verifyAuthorization" (core/opts->js opts)))

(defn recover-authorization-address
  "Promise of the signer of a signed authorization `{:authorization :signature?}`."
  [opts]
  (core/invoke-util "recoverAuthorizationAddress" (core/opts->js opts)))

;; ---------------------------------------------------------------------------
;; ENS hashing
;; ---------------------------------------------------------------------------

(defn namehash
  "ENS namehash of a (normalized) name."
  [name]
  (core/invoke-util "namehash" name))

(defn labelhash
  "keccak256 of a single ENS label."
  [label]
  (core/invoke-util "labelhash" label))

(defn normalize
  "UTS-46 normalization of an ENS name (required before every ENS action)."
  [name]
  (core/invoke-util "normalize" name))

;; ---------------------------------------------------------------------------
;; Misc + constants
;; ---------------------------------------------------------------------------

(defn stringify
  "BigInt-safe `JSON.stringify` of CLJS data (kebab keys are camelled; BigInt
   -> decimal string)."
  ([x] (core/invoke-util "stringify" (core/opts->js-value x)))
  ([x indent] (core/invoke-util "stringify" (core/opts->js-value x) nil indent)))

(def zero-address
  "0x0000000000000000000000000000000000000000"
  (core/constant "zeroAddress"))

(def zero-hash
  "The 32-byte zero hash."
  (core/constant "zeroHash"))

(def eth-address
  "The 0xEeee…EEeE placeholder for native ETH."
  (core/constant "ethAddress"))

(def max-uint256
  "2^256 - 1 as BigInt."
  (core/constant "maxUint256"))

(def max-int256
  "2^255 - 1 as BigInt."
  (core/constant "maxInt256"))

(def min-int256
  "-2^255 as BigInt."
  (core/constant "minInt256"))

(def presign-message-prefix
  "\"\\x19Ethereum Signed Message:\\n\"."
  (core/constant "presignMessagePrefix"))

(defn max-uint
  "`maxUintN` for N in 8..256 step 8 (BigInt)."
  [bits]
  (core/constant (str "maxUint" bits)))

(defn max-int
  "`maxIntN` for N in 8..256 step 8 (BigInt)."
  [bits]
  (core/constant (str "maxInt" bits)))

(defn min-int
  "`minIntN` for N in 8..256 step 8 (BigInt)."
  [bits]
  (core/constant (str "minInt" bits)))

(def ether-units
  "`{:gwei 9 :wei 18}` — decimals relative to ether."
  (core/constant "etherUnits"))

(def gwei-units
  "`{:ether -9 :wei 9}`."
  (core/constant "gweiUnits"))
