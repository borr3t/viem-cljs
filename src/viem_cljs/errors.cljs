(ns viem-cljs.errors
  "Accessors and classifiers over the errors viem-cljs raises. Every
   rejection / throw crossing the boundary is an ex-info whose data
   (`util/error-data`) carries the viem class name, the short message,
   the `:causes` chain (outermost first, each a summary map with `:name
   :short-message :message :details :code :data :reason :status :url` when
   present) and the raw JS error under `:cause`. The fns here read that data
   — none of them touches JavaScript. Every accessor also accepts a RAW JS
   error (a viem BaseError, ox / abitype error or plain `Error` caught outside
   the wrapper): it is wrapped on the fly with `wrap` (= `util/viem-error`).
   Non-error values (nil, strings, other ex-info) give nil / false.

   Three layers:
   - accessors: `error-name`, `short-message`, `details`, `rpc-code`,
     `http-status`, `causes`, `cause-chain`, `root`, `revert-data` …
   - cause walking: `walk-cause` (first cause summary satisfying a
     predicate, the CLJS `err.walk(fn)`), `cause?` / `cause-named`.
   - classification: `user-rejected?`, `insufficient-funds?`, `nonce-too-low?`,
     `rate-limited?`, `timeout?`, `connection-error?`, `reverted?` … each a
     heuristic over class names, JSON-RPC codes and node message text
     (viem itself classifies node errors from message text),
     summarized by `category` and the retry advice `retryable?` / `retry-hint`."
  (:require [clojure.string :as str]
            [viem-cljs.util :as util]))

;; ---------------------------------------------------------------------------
;; Wrapping and detection
;; ---------------------------------------------------------------------------

(defn wrap
  "`util/viem-error`: wraps a raw JS error (viem BaseError or not) as a
   :viem-cljs/viem-error ex-info tagged with the optional `action` keyword.
   Wrapped errors pass through unchanged. This is the one entry point for
   errors caught outside viem-cljs (a custom transport, a signer callback)."
  ([e] (util/viem-error e))
  ([e action] (util/viem-error e action)))

(defn- js-error?
  "True for any JS Error instance (viem's, ox's, Node's)."
  [e]
  (instance? js/Error e))

(defn data
  "The viem-cljs error data of `e`: a wrapped ex-info gives its
   `util/error-data`; a raw JS error is wrapped first; anything else -> nil."
  [e]
  (cond
    (nil? e) nil
    (some? (ex-data e)) (util/error-data e)
    (js-error? e) (util/error-data (wrap e))
    :else nil))

(defn error?
  "True when `e` is any viem-cljs error (viem / argument / config /
   transport) or a raw JS error."
  [e]
  (some? (data e)))

(defn error-type
  "`:viem-cljs/viem-error`, `:viem-cljs/argument-error`,
   `:viem-cljs/config-error`, `:viem-cljs/transport-error` or nil."
  [e]
  (:type (data e)))

(defn viem-error?
  "True when `e` wraps an error thrown by viem (or any raw JS error)."
  [e]
  (= :viem-cljs/viem-error (error-type e)))

(defn argument-error?
  "True for a :viem-cljs/argument-error (bad input caught before viem)."
  [e]
  (= :viem-cljs/argument-error (error-type e)))

(defn config-error?
  "True for a :viem-cljs/config-error (client / transport / chain setup)."
  [e]
  (= :viem-cljs/config-error (error-type e)))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn error-name
  "The viem error class name of the OUTERMOST error
   (\"ContractFunctionExecutionError\", \"HttpRequestError\", \"Error\" for a
   plain JS error), nil for argument / config errors and non-errors."
  [e]
  (let [d (data e)]
    (when (= :viem-cljs/viem-error (:type d)) (:name d))))

(defn message
  "The full message: `ex-message` of a wrapped error, `message` of a raw
   JS error (viem's multi-line render), nil otherwise."
  [e]
  (cond
    (nil? e) nil
    (some? (ex-data e)) (ex-message e)
    (js-error? e) (ex-message (wrap e))
    :else nil))

(defn short-message
  "viem's one-line `shortMessage` (falls back to the plain message for
   non-BaseError throws); the message of argument / config errors."
  [e]
  (let [d (data e)]
    (or (:short-message d)
        (when (and d (not= :viem-cljs/viem-error (:type d))) (message e)))))

(defn details
  "viem's `details` string (the node / transport detail), nil when absent."
  [e]
  (:details (data e)))

(defn meta-messages
  "viem's `metaMessages` vector (context lines), nil when absent."
  [e]
  (:meta-messages (data e)))

(defn docs-path
  "viem's `docsPath` (relative viem.sh path), nil when absent."
  [e]
  (:docs-path (data e)))

(defn version
  "The `Version: viem@x.y.z` viem attached, nil when absent."
  [e]
  (:version (data e)))

(defn action
  "The kebab keyword of the viem-cljs call that raised `e` (`:get-balance`,
   `:read-contract` …), nil when unknown."
  [e]
  (:action (data e)))

(defn reason
  "The `:reason` keyword of an argument / config error (`:mac-mismatch`,
   `:unknown-chain`, `:missing-transport` …), nil otherwise."
  [e]
  (let [d (data e)]
    (when (not= :viem-cljs/viem-error (:type d)) (:reason d))))

(defn cause-chain
  "The cause summaries of a wrapped error, outermost first: each a map
   `{:name :short-message :message :details :code :data :reason :status
   :url}` (keys present only when viem set them). Empty vector for
   argument / config errors and non-errors."
  [e]
  (or (:causes (data e)) []))

(defn causes
  "The class names of the cause chain, outermost first
   ([\"ContractFunctionExecutionError\" \"ContractFunctionRevertedError\" …])."
  [e]
  (mapv :name (cause-chain e)))

(defn root
  "The deepest cause summary (viem's `err.walk()`), nil when none."
  [e]
  (:root (data e)))

(defn walk-cause
  "The first cause summary (outermost first) for which `pred` is truthy —
   the CLJS `err.walk(fn)`. `pred` receives a summary map; nil when no
   cause matches."
  [e pred]
  (some (fn [c] (when (pred c) c)) (cause-chain e)))

(defn cause-named
  "The first cause summary whose `:name` is `name` (a string) or is in
   `name` (a set of strings), nil when absent."
  [e name]
  (let [names (if (set? name) name #{name})]
    (walk-cause e #(contains? names (:name %)))))

(defn cause?
  "True when `name` (a string or a set of strings) appears anywhere in the
   cause chain — viem's `err.walk(e => e instanceof X)` by class name."
  [e name]
  (some? (cause-named e name)))

(defn rpc-code
  "The first JSON-RPC / EIP-1193 `code` found walking the cause chain
   outermost first (-32000, -32601, 4001 …), nil when none."
  [e]
  (:code (walk-cause e #(some? (:code %)))))

(defn http-status
  "The HTTP status carried by an `HttpRequestError` in the chain (429, 503
   …), nil when none."
  [e]
  (:status (walk-cause e #(number? (:status %)))))

(defn url
  "The request URL carried by an HTTP / RPC request error in the chain,
   nil when none."
  [e]
  (:url (walk-cause e #(string? (:url %)))))

(defn revert-data
  "The decoded revert of a `ContractFunctionRevertedError` in the chain:
   `{:error-name \"TooBig\" :args [..] :abi-item {..}}` for a custom error /
   `Error(string)` / `Panic(uint256)` matched against the ABI; nil when the
   error is not a decoded revert."
  [e]
  (let [c (cause-named e "ContractFunctionRevertedError")]
    (when (map? (:data c)) (:data c))))

(defn revert-error-name
  "The custom error / `Error` / `Panic` name of a decoded revert, nil otherwise."
  [e]
  (:error-name (revert-data e)))

(defn revert-args
  "The decoded arguments of a revert (vector, or kebab map for named
   params), nil when none."
  [e]
  (:args (revert-data e)))

(defn revert-reason
  "The revert reason string: viem's `reason` on a `ContractFunctionRevertedError`
   (`require(cond, \"reason\")` / `revert(\"reason\")`), else the details of an
   `ExecutionRevertedError`, nil when none."
  [e]
  (or (:reason (cause-named e "ContractFunctionRevertedError"))
      (:details (cause-named e "ExecutionRevertedError"))))

;; ---------------------------------------------------------------------------
;; Classification heuristics (viem error class families + node message text)
;; ---------------------------------------------------------------------------

(defn- text
  "Every message-like string of the chain joined, lower-cased, for the
   text heuristics (viem classifies node errors the same way)."
  [e]
  (str/lower-case
   (str/join " " (mapcat (fn [c] (keep c [:short-message :message :details :reason]))
                         (cause-chain e)))))

(defn- text?
  "True when `re` matches the chain's message text."
  [e re]
  (boolean (re-find re (text e))))

(defn user-rejected?
  "True for an EIP-1193 user rejection (`UserRejectedRequestError`, code
   4001) — the wallet declined; never retry automatically."
  [e]
  (or (cause? e "UserRejectedRequestError")
      (= 4001 (rpc-code e))))

(defn insufficient-funds?
  "True when the sender cannot pay `gas * price + value`
   (`InsufficientFundsError`, or the node's \"insufficient funds\" text)."
  [e]
  (or (cause? e "InsufficientFundsError")
      (text? e #"insufficient funds|insufficient balance")))

(defn nonce-too-low?
  "True for `NonceTooLowError` / the node's \"nonce too low\" text: the
   nonce was already used — refresh it (`get-transaction-count!` with
   `:block-tag :pending`) and resend."
  [e]
  (or (cause? e "NonceTooLowError")
      (text? e #"nonce too low")))

(defn nonce-too-high?
  "True for `NonceTooHighError` / \"nonce too high\": a gap before this
   nonce — send the missing transactions first."
  [e]
  (or (cause? e "NonceTooHighError")
      (text? e #"nonce too high")))

(defn nonce-error?
  "True for any nonce problem (`NonceTooLowError`, `NonceTooHighError`,
   `NonceMaxValueError`, `FeePayerNonceMismatchError`)."
  [e]
  (or (nonce-too-low? e)
      (nonce-too-high? e)
      (cause? e #{"NonceMaxValueError" "FeePayerNonceMismatchError"})))

(defn replacement-underpriced?
  "True when a replacement of a pending transaction was rejected because its
   fee bump is too small (\"replacement transaction underpriced\" /
   \"replacement fee too low\"; viem has no class for it) — resend with a
   higher `max-fee-per-gas` / `max-priority-fee-per-gas`."
  [e]
  (text? e #"replacement transaction underpriced|replacement fee too low|replacement.*underpriced"))

(defn fee-too-low?
  "True when the fee fields are below what the chain accepts
   (`FeeCapTooLowError`, `TipAboveFeeCapError`, `MaxFeePerGasTooLowError`,
   `BaseFeeScalarError`, or \"underpriced\" / \"fee cap less than block base
   fee\" text) — bump the fees and resend. Includes replacement underpricing."
  [e]
  (or (cause? e #{"FeeCapTooLowError" "TipAboveFeeCapError" "MaxFeePerGasTooLowError" "BaseFeeScalarError"})
      (replacement-underpriced? e)
      (text? e #"underpriced|fee cap less than|max fee per gas less than block base fee|tip above fee cap")))

(defn gas-error?
  "True for an intrinsic-gas or fee-cap problem (`IntrinsicGasTooLowError`,
   `IntrinsicGasTooHighError`, `FeeCapTooHighError`, `\"out of gas\"`)."
  [e]
  (or (cause? e #{"IntrinsicGasTooLowError" "IntrinsicGasTooHighError" "FeeCapTooHighError"})
      (text? e #"intrinsic gas|out of gas|gas required exceeds")))

(defn rate-limited?
  "True when the RPC provider throttled the request: `LimitExceededRpcError`
   (-32005), HTTP 429, or \"rate limit\" / \"too many requests\" text —
   back off and retry."
  [e]
  (or (cause? e "LimitExceededRpcError")
      (= -32005 (rpc-code e))
      (= 429 (http-status e))
      (text? e #"rate limit|too many requests|\b429\b")))

(defn timeout?
  "True for a transport timeout (`TimeoutError`) or a receipt wait that
   gave up (`WaitForTransactionReceiptTimeoutError`) — the request may still
   have gone through; re-query before resending."
  [e]
  (or (cause? e #{"TimeoutError" "WaitForTransactionReceiptTimeoutError"})
      (text? e #"timed out|timeout")))

(defn connection-error?
  "True when the node could not be reached or the socket dropped: an
   `HttpRequestError` without a status (fetch failed), a 5xx status,
   `WebSocketRequestError`, `SocketClosedError`, `ProviderDisconnectedError`,
   `ChainDisconnectedError`, or Node's ECONNREFUSED / ECONNRESET / ENOTFOUND
   / \"fetch failed\" / \"socket hang up\" text."
  [e]
  (or (cause? e #{"WebSocketRequestError" "SocketClosedError" "ProviderDisconnectedError" "ChainDisconnectedError"})
      (let [s (http-status e)]
        (and (cause? e "HttpRequestError") (or (nil? s) (>= s 500))))
      (text? e #"econnrefused|econnreset|enotfound|etimedout|epipe|fetch failed|socket hang up|network error")))

(defn transient-rpc-error?
  "True for the JSON-RPC codes that mean \"try again\": `InternalRpcError`
   (-32603), `ResourceUnavailableRpcError` (-32002), plus a `UnknownRpcError`
   whose text says so."
  [e]
  (or (cause? e #{"InternalRpcError" "ResourceUnavailableRpcError"})
      (contains? #{-32603 -32002} (rpc-code e))))

(defn reverted?
  "True when execution reverted: `ExecutionRevertedError` (code 3),
   `ContractFunctionRevertedError`, `TransactionReceiptRevertedError`, a
   receipt-less `CallExecutionError` whose text says \"revert\", or the
   node's \"execution reverted\" text. Never retryable as is."
  [e]
  (or (cause? e #{"ExecutionRevertedError" "ContractFunctionRevertedError" "TransactionReceiptRevertedError"})
      (= 3 (rpc-code e))
      (text? e #"execution reverted|reverted")))

(defn zero-data?
  "True when a contract call returned `0x` (`ContractFunctionZeroDataError`
   / `AbiDecodingZeroDataError`): usually no code at the address, or a wrong
   chain — not retryable."
  [e]
  (cause? e #{"ContractFunctionZeroDataError" "AbiDecodingZeroDataError"}))

(defn not-found?
  "True for a missing block / transaction / receipt
   (`BlockNotFoundError`, `TransactionNotFoundError`,
   `TransactionReceiptNotFoundError`, `ResourceNotFoundRpcError` -32001)."
  [e]
  (or (cause? e #{"BlockNotFoundError" "TransactionNotFoundError" "TransactionReceiptNotFoundError" "ResourceNotFoundRpcError"})
      (= -32001 (rpc-code e))))

(defn unsupported-method?
  "True when the node / provider lacks the RPC method
   (`MethodNotFoundRpcError` -32601, `MethodNotSupportedRpcError` -32004,
   `UnsupportedProviderMethodError` 4200)."
  [e]
  (or (cause? e #{"MethodNotFoundRpcError" "MethodNotSupportedRpcError" "UnsupportedProviderMethodError"})
      (contains? #{-32601 -32004 4200} (rpc-code e))))

(defn chain-error?
  "True for a chain configuration problem (`ChainMismatchError`,
   `ChainNotFoundError`, `ClientChainNotConfiguredError`,
   `InvalidChainIdError`, `UnsupportedChainIdError`,
   `ChainDoesNotSupportContract`, `SwitchChainError` 4902, or the plain
   `Error` with the text \"client chain not configured\" the ENS actions throw)."
  [e]
  (or (cause? e #{"ChainMismatchError" "ChainNotFoundError" "ClientChainNotConfiguredError"
                  "InvalidChainIdError" "UnsupportedChainIdError" "ChainDoesNotSupportContract"
                  "SwitchChainError"})
      (= 4902 (rpc-code e))
      (text? e #"chain not configured|chain mismatch|does not support contract|unsupported chain")))

(defn abi-error?
  "True for an ABI codec problem (viem's `Abi*` / `*Mismatch` error
   classes): wrong ABI, wrong args, undecodable data — a bug, not a
   transient."
  [e]
  (boolean (walk-cause e #(re-find #"^Abi|^DecodeLog|^InvalidAbi|^BytesSizeMismatch|^UnsupportedPackedAbiType" (str (:name %))))))

(defn category
  "One keyword summarizing `e`, first match wins:
   `:argument` `:config` (viem-cljs pre-flight errors) `:user-rejected`
   `:insufficient-funds` `:reverted` `:zero-data` `:abi` `:nonce` `:fee`
   `:gas` `:rate-limited` `:timeout` `:connection` `:transient-rpc`
   `:not-found` `:unsupported-method` `:chain` `:viem` (any other viem error)
   `:unknown` (not an error)."
  [e]
  (let [t (error-type e)]
    (cond
      (nil? t) :unknown
      (= :viem-cljs/argument-error t) :argument
      (= :viem-cljs/config-error t) :config
      (user-rejected? e) :user-rejected
      (insufficient-funds? e) :insufficient-funds
      (reverted? e) :reverted
      (zero-data? e) :zero-data
      (abi-error? e) :abi
      (nonce-error? e) :nonce
      (fee-too-low? e) :fee
      (gas-error? e) :gas
      (rate-limited? e) :rate-limited
      (timeout? e) :timeout
      (connection-error? e) :connection
      (transient-rpc-error? e) :transient-rpc
      (not-found? e) :not-found
      (unsupported-method? e) :unsupported-method
      (chain-error? e) :chain
      :else :viem)))

(def retry-hints
  "`category` -> what a retry needs: `:backoff` (wait, then resend the same
   request), `:requery` (the request may have succeeded — look before
   resending), `:refresh-nonce` (fetch the pending nonce, then resend),
   `:bump-fee` (raise the fee fields, then resend). Categories absent here
   are not retryable."
  {:rate-limited :backoff
   :connection :backoff
   :transient-rpc :backoff
   :timeout :requery
   :nonce :refresh-nonce
   :fee :bump-fee})

(defn retry-hint
  "The retry advice keyword for `e` (see `retry-hints`), nil when a retry
   would not help (revert, insufficient funds, user rejection, bad
   arguments, unsupported method …)."
  [e]
  (get retry-hints (category e)))

(defn retryable?
  "True when a retry can succeed — after the adjustment named by
   `retry-hint`: throttling, connection loss, transient RPC errors and
   timeouts (backoff / requery), nonce-too-low (refresh the nonce), fee too
   low or replacement underpriced (bump the fee). False for reverts,
   insufficient funds, user rejections, ABI / argument / config errors,
   unsupported methods and non-errors."
  [e]
  (some? (retry-hint e)))

(defn summary
  "One-line diagnostic string: `<name> [<category>] <short message>` with the
   RPC code, HTTP status, action and revert name when present — safe to
   print (no key material is ever in error data)."
  [e]
  (let [d (data e)]
    (when d
      (str (or (error-name e) (name (:type d)))
           " [" (name (category e)) "]"
           (when-let [c (rpc-code e)] (str " code=" c))
           (when-let [s (http-status e)] (str " status=" s))
           (when-let [a (action e)] (str " action=" (name a)))
           (when-let [r (revert-error-name e)] (str " revert=" r))
           (when-let [rs (revert-reason e)] (str " reason=" (pr-str rs)))
           ": " (short-message e)))))
