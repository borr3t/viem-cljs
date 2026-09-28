(ns viem-cljs.actions
  "Every viem action of the main surface in the
   tree-shakable shape: `(action! client opts)` -> promesa promise, working
   on ANY client kind (bare, public, wallet, test). Names are viem's,
   kebab-cased 1:1 with a `!` suffix (`getBlockNumber` -> `get-block-number!`).
   Options are kebab maps translated by `viem-cljs.core/opts->js` (quantities
   coerced to BigInt, `:abi` in any form, `:chain` / `:account` resolved,
   `?`-booleans stripped); results are kebab maps with BigInt quantities.
   Watchers live in `viem-cljs.events`; the pure `verify-*` fns in
   `viem-cljs.utils`. Test actions (anvil / hardhat / ganache) are the last
   section. Every rejection is a viem-cljs ex-info tagged `:action`.
   Pure ClojureScript: interop lives in `viem-cljs.core`."
  (:refer-clojure :exclude [reset!])
  (:require [viem-cljs.core :as core]))

(defn- act
  ([kw client] (core/action! kw client nil))
  ([kw client opts] (core/action! kw client opts)))

;; ---------------------------------------------------------------------------
;; Public actions
;; ---------------------------------------------------------------------------

(defn call!
  "viem `call`: `eth_call`: `{:to :data :value :gas :account :block-tag | :block-number :state-override …}` -> `{:data hex|nil}`."
  ([client] (act :call client))
  ([client opts] (act :call client opts)))

(defn create-access-list!
  "viem `createAccessList`: `eth_createAccessList` for a tx request -> `{:access-list :gas-used}`."
  ([client] (act :create-access-list client))
  ([client opts] (act :create-access-list client opts)))

(defn estimate-contract-gas!
  "viem `estimateContractGas`: Gas estimate of a contract call `{:abi :address :function-name :args :account :value}` -> BigInt."
  ([client] (act :estimate-contract-gas client))
  ([client opts] (act :estimate-contract-gas client opts)))

(defn estimate-fees-per-gas!
  "viem `estimateFeesPerGas`: `{:type :eip1559 (default) | :legacy}` -> `{:max-fee-per-gas :max-priority-fee-per-gas}` or `{:gas-price}` (BigInt)."
  ([client] (act :estimate-fees-per-gas client))
  ([client opts] (act :estimate-fees-per-gas client opts)))

(defn estimate-gas!
  "viem `estimateGas`: `eth_estimateGas` for `{:to :data :value :account :block-tag …}` -> BigInt."
  ([client] (act :estimate-gas client))
  ([client opts] (act :estimate-gas client opts)))

(defn estimate-max-priority-fee-per-gas!
  "viem `estimateMaxPriorityFeePerGas`: -> BigInt max priority fee per gas."
  ([client] (act :estimate-max-priority-fee-per-gas client))
  ([client opts] (act :estimate-max-priority-fee-per-gas client opts)))

(defn fill-transaction!
  "viem `fillTransaction`: `eth_fillTransaction`: fills a tx request -> `{:raw :transaction}`."
  ([client] (act :fill-transaction client))
  ([client opts] (act :fill-transaction client opts)))

(defn get-balance!
  "viem `getBalance`: `eth_getBalance`: `{:address :block-tag | :block-number}` -> BigInt wei."
  ([client] (act :get-balance client))
  ([client opts] (act :get-balance client opts)))

(defn get-blob-base-fee!
  "viem `getBlobBaseFee`: `eth_blobBaseFee` -> BigInt."
  ([client] (act :get-blob-base-fee client))
  ([client opts] (act :get-blob-base-fee client opts)))

(defn get-block!
  "viem `getBlock`: `{:block-tag | :block-number | :block-hash :include-transactions?}` -> kebab block (BigInt `:number :timestamp :gas-used …`)."
  ([client] (act :get-block client))
  ([client opts] (act :get-block client opts)))

(defn get-block-number!
  "viem `getBlockNumber`: `eth_blockNumber` -> BigInt (cached per client `:cache-time`; opts `{:cache-time 0}` to bypass)."
  ([client] (act :get-block-number client))
  ([client opts] (act :get-block-number client opts)))

(defn get-block-receipts!
  "viem `getBlockReceipts`: `{:block-tag | :block-number | :block-hash}` -> vector of receipts."
  ([client] (act :get-block-receipts client))
  ([client opts] (act :get-block-receipts client opts)))

(defn get-block-transaction-count!
  "viem `getBlockTransactionCount`: `{:block-tag | :block-number | :block-hash}` -> number."
  ([client] (act :get-block-transaction-count client))
  ([client opts] (act :get-block-transaction-count client opts)))

(defn get-chain-id!
  "viem `getChainId`: `eth_chainId` -> number."
  ([client] (act :get-chain-id client))
  ([client opts] (act :get-chain-id client opts)))

(defn get-code!
  "viem `getCode`: `eth_getCode`: `{:address :block-tag}` -> hex or nil when no code."
  ([client] (act :get-code client))
  ([client opts] (act :get-code client opts)))

(defn get-contract-events!
  "viem `getContractEvents`: Decoded logs of a contract: `{:abi :address :event-name :args :from-block :to-block | :block-hash :strict?}` -> vector of logs with `:event-name :args`."
  ([client] (act :get-contract-events client))
  ([client opts] (act :get-contract-events client opts)))

(defn get-delegation!
  "viem `getDelegation`: EIP-7702 delegation target of `{:address}` -> address or nil."
  ([client] (act :get-delegation client))
  ([client opts] (act :get-delegation client opts)))

(defn get-eip712-domain!
  "viem `getEip712Domain`: ERC-5267 `{:address}` -> `{:domain :fields :extensions}`."
  ([client] (act :get-eip712-domain client))
  ([client opts] (act :get-eip712-domain client opts)))

(defn get-fee-history!
  "viem `getFeeHistory`: `{:block-count :reward-percentiles [..] :block-tag}` -> `{:base-fee-per-gas [BigInt] :gas-used-ratio [number] :oldest-block BigInt :reward [[BigInt]]}`."
  ([client] (act :get-fee-history client))
  ([client opts] (act :get-fee-history client opts)))

(defn get-filter-changes!
  "viem `getFilterChanges`: `{:filter <filter map>}` -> new logs / hashes (see `events/filter-changes!`)."
  ([client] (act :get-filter-changes client))
  ([client opts] (act :get-filter-changes client opts)))

(defn get-filter-logs!
  "viem `getFilterLogs`: `{:filter <filter map>}` -> all logs (see `events/filter-logs!`)."
  ([client] (act :get-filter-logs client))
  ([client opts] (act :get-filter-logs client opts)))

(defn get-gas-price!
  "viem `getGasPrice`: `eth_gasPrice` -> BigInt."
  ([client] (act :get-gas-price client))
  ([client opts] (act :get-gas-price client opts)))

(defn get-logs!
  "viem `getLogs`: `eth_getLogs`: `{:address :event (human-readable) :events :args :from-block :to-block | :block-hash :strict?}` -> vector of logs (decoded when `:event` given)."
  ([client] (act :get-logs client))
  ([client opts] (act :get-logs client opts)))

(defn get-proof!
  "viem `getProof`: `eth_getProof`: `{:address :storage-keys [..] :block-tag}` -> proof map."
  ([client] (act :get-proof client))
  ([client opts] (act :get-proof client opts)))

(defn get-raw-transaction!
  "viem `getRawTransaction`: `{:hash}` -> serialized tx hex."
  ([client] (act :get-raw-transaction client))
  ([client opts] (act :get-raw-transaction client opts)))

(defn get-storage-at!
  "viem `getStorageAt`: `{:address :slot :block-tag}` -> hex or nil."
  ([client] (act :get-storage-at client))
  ([client opts] (act :get-storage-at client opts)))

(defn get-storage-values!
  "viem `getStorageValues`: Batch storage read `{:requests {addr [slots]}}` -> `{addr [values]}`."
  ([client] (act :get-storage-values client))
  ([client opts] (act :get-storage-values client opts)))

(defn get-transaction!
  "viem `getTransaction`: One of `{:hash}` | `{:block-hash :index}` | `{:block-number :index}` | `{:block-tag :index}` -> kebab transaction."
  ([client] (act :get-transaction client))
  ([client opts] (act :get-transaction client opts)))

(defn get-transaction-confirmations!
  "viem `getTransactionConfirmations`: `{:hash}` or `{:transaction-receipt}` -> BigInt confirmations."
  ([client] (act :get-transaction-confirmations client))
  ([client opts] (act :get-transaction-confirmations client opts)))

(defn get-transaction-count!
  "viem `getTransactionCount`: `{:address :block-tag}` -> number (the nonce)."
  ([client] (act :get-transaction-count client))
  ([client opts] (act :get-transaction-count client opts)))

(defn get-transaction-receipt!
  "viem `getTransactionReceipt`: `{:hash}` -> kebab receipt (`:status` \"success\"/\"reverted\", BigInt gas fields, `:logs`)."
  ([client] (act :get-transaction-receipt client))
  ([client opts] (act :get-transaction-receipt client opts)))

(defn multicall!
  "viem `multicall`: Multicall3 aggregate `{:contracts [{:address :abi :function-name :args}] :allow-failure? :batch-size :multicall-address :deployless?}` -> results (see `contract/multicall!`)."
  ([client] (act :multicall client))
  ([client opts] (act :multicall client opts)))

(defn read-contract!
  "viem `readContract`: `{:abi :address :function-name :args :block-tag :account :state-override}` -> decoded value."
  ([client] (act :read-contract client))
  ([client opts] (act :read-contract client opts)))

(defn simulate-blocks!
  "viem `simulateBlocks`: `eth_simulateV1`: `{:blocks [{:calls [..] :block-overrides :state-overrides}] :return-full-transactions? :trace-transfers? :validation?}` -> simulated blocks."
  ([client] (act :simulate-blocks client))
  ([client opts] (act :simulate-blocks client opts)))

(defn simulate-calls!
  "viem `simulateCalls`: Simulates `{:calls [{:to :data :value}] :account :state-overrides :trace-asset-changes?}` -> `{:results :block :asset-changes}`."
  ([client] (act :simulate-calls client))
  ([client opts] (act :simulate-calls client opts)))

(defn simulate-contract!
  "viem `simulateContract`: Simulates a write `{:abi :address :function-name :args :account :value}` -> `{:result :request}`; `:request` feeds `write-contract!`."
  ([client] (act :simulate-contract client))
  ([client opts] (act :simulate-contract client opts)))

(defn uninstall-filter!
  "viem `uninstallFilter`: `{:filter <filter map>}` -> boolean."
  ([client] (act :uninstall-filter client))
  ([client opts] (act :uninstall-filter client opts)))

(defn verify-hash!
  "viem `verifyHash`: `{:address :hash :signature}` -> boolean (EOA + ERC-1271 + ERC-6492 + ERC-8010; may `eth_call`)."
  ([client] (act :verify-hash client))
  ([client opts] (act :verify-hash client opts)))

(defn verify-message!
  "viem `verifyMessage`: `{:address :message :signature}` -> boolean (EOA + smart-account verification via the client)."
  ([client] (act :verify-message client))
  ([client opts] (act :verify-message client opts)))

(defn verify-typed-data!
  "viem `verifyTypedData`: `{:address :signature :domain :types :primary-type :message}` -> boolean (via the client)."
  ([client] (act :verify-typed-data client))
  ([client opts] (act :verify-typed-data client opts)))

(defn wait-for-transaction-receipt!
  "viem `waitForTransactionReceipt`: `{:hash :confirmations (1) :polling-interval
   :timeout :retry-count :retry-delay :check-replacement? :on-replaced}` ->
   receipt. `:on-replaced` is a CLJS fn receiving `{:reason \"cancelled\" |
   \"replaced\" | \"repriced\" :replaced-transaction :transaction
   :transaction-receipt}`."
  [client opts]
  (core/action-with-callbacks! :wait-for-transaction-receipt client
                               (dissoc opts :on-replaced)
                               (when-let [f (:on-replaced opts)] {:on-replaced f})))

;; ---------------------------------------------------------------------------
;; Wallet actions
;; ---------------------------------------------------------------------------

(defn add-chain!
  "viem `addChain`: `wallet_addEthereumChain` for `{:chain <anything chains/chain accepts>}` -> nil."
  ([client] (act :add-chain client))
  ([client opts] (act :add-chain client opts)))

(defn deploy-contract!
  "viem `deployContract`: `{:abi :bytecode :args :account :value …}` -> tx hash."
  ([client] (act :deploy-contract client))
  ([client opts] (act :deploy-contract client opts)))

(defn get-addresses!
  "viem `getAddresses`: `eth_accounts` -> vector of addresses."
  ([client] (act :get-addresses client))
  ([client opts] (act :get-addresses client opts)))

(defn get-calls-status!
  "viem `getCallsStatus`: EIP-5792 `{:id}` -> `{:status :receipts …}`."
  ([client] (act :get-calls-status client))
  ([client opts] (act :get-calls-status client opts)))

(defn get-capabilities!
  "viem `getCapabilities`: EIP-5792 `{:account :chain-id}` -> capabilities map."
  ([client] (act :get-capabilities client))
  ([client opts] (act :get-capabilities client opts)))

(defn get-permissions!
  "viem `getPermissions`: `wallet_getPermissions` -> vector of permissions."
  ([client] (act :get-permissions client))
  ([client opts] (act :get-permissions client opts)))

(defn prepare-authorization!
  "viem `prepareAuthorization`: EIP-7702 `{:contract-address :chain-id :nonce :executor}` -> unsigned authorization."
  ([client] (act :prepare-authorization client))
  ([client opts] (act :prepare-authorization client opts)))

(defn prepare-transaction-request!
  "viem `prepareTransactionRequest`: Fills `{:to :value :data …}` with nonce / fees / gas / type / chain-id -> the request (opaque `:account` / `:chain` re-attached) for `send-transaction!` / `sign-transaction!`."
  ([client] (act :prepare-transaction-request client))
  ([client opts] (act :prepare-transaction-request client opts)))

(defn request-addresses!
  "viem `requestAddresses`: `eth_requestAccounts` -> vector of addresses."
  ([client] (act :request-addresses client))
  ([client opts] (act :request-addresses client opts)))

(defn request-permissions!
  "viem `requestPermissions`: `wallet_requestPermissions` with a permissions map -> vector of permissions."
  ([client] (act :request-permissions client))
  ([client opts] (act :request-permissions client opts)))

(defn send-calls!
  "viem `sendCalls`: EIP-5792 `{:calls [{:to :data :value | :abi :function-name :args}] :capabilities :force-atomic?}` -> `{:id}`."
  ([client] (act :send-calls client))
  ([client opts] (act :send-calls client opts)))

(defn send-calls-sync!
  "viem `sendCallsSync`: `send-calls!` that waits for the bundle -> calls status."
  ([client] (act :send-calls-sync client))
  ([client opts] (act :send-calls-sync client opts)))

(defn send-raw-transaction!
  "viem `sendRawTransaction`: `{:serialized-transaction hex}` -> tx hash."
  ([client] (act :send-raw-transaction client))
  ([client opts] (act :send-raw-transaction client opts)))

(defn send-raw-transaction-sync!
  "viem `sendRawTransactionSync`: EIP-7966 `{:serialized-transaction :timeout :throw-on-receipt-revert?}` -> receipt."
  ([client] (act :send-raw-transaction-sync client))
  ([client opts] (act :send-raw-transaction-sync client opts)))

(defn send-transaction!
  "viem `sendTransaction`: `{:to :value :data :gas :nonce :account :chain …}` (or a prepared request) -> tx hash. A local account signs and broadcasts `eth_sendRawTransaction`; a json-rpc account delegates `eth_sendTransaction`."
  ([client] (act :send-transaction client))
  ([client opts] (act :send-transaction client opts)))

(defn send-transaction-sync!
  "viem `sendTransactionSync`: EIP-7966 `send-transaction!` that returns the receipt."
  ([client] (act :send-transaction-sync client))
  ([client opts] (act :send-transaction-sync client opts)))

(defn show-calls-status!
  "viem `showCallsStatus`: EIP-5792 `{:id}` -> nil."
  ([client] (act :show-calls-status client))
  ([client opts] (act :show-calls-status client opts)))

(defn sign-authorization!
  "viem `signAuthorization`: EIP-7702 `{:contract-address :chain-id :nonce}` -> signed authorization map."
  ([client] (act :sign-authorization client))
  ([client opts] (act :sign-authorization client opts)))

(defn sign-message!
  "viem `signMessage`: EIP-191 `{:message \"..\" | {:raw hex} :account}` -> signature hex."
  ([client] (act :sign-message client))
  ([client opts] (act :sign-message client opts)))

(defn sign-transaction!
  "viem `signTransaction`: `{:to :value … :account}` (or prepared request) -> serialized signed hex."
  ([client] (act :sign-transaction client))
  ([client opts] (act :sign-transaction client opts)))

(defn sign-typed-data!
  "viem `signTypedData`: EIP-712 `{:domain :types :primary-type :message :account}` -> signature hex."
  ([client] (act :sign-typed-data client))
  ([client opts] (act :sign-typed-data client opts)))

(defn switch-chain!
  "viem `switchChain`: `wallet_switchEthereumChain` `{:id}` -> nil."
  ([client] (act :switch-chain client))
  ([client opts] (act :switch-chain client opts)))

(defn wait-for-calls-status!
  "viem `waitForCallsStatus`: EIP-5792 `{:id :polling-interval :timeout}` -> calls status."
  ([client] (act :wait-for-calls-status client))
  ([client opts] (act :wait-for-calls-status client opts)))

(defn watch-asset!
  "viem `watchAsset`: `wallet_watchAsset` `{:type \"ERC20\" :options {:address :symbol :decimals :image}}` -> boolean."
  ([client] (act :watch-asset client))
  ([client opts] (act :watch-asset client opts)))

(defn write-contract!
  "viem `writeContract`: `{:abi :address :function-name :args :account :value …}` or the `:request` of `simulate-contract!` -> tx hash."
  ([client] (act :write-contract client))
  ([client opts] (act :write-contract client opts)))

(defn write-contract-sync!
  "viem `writeContractSync`: EIP-7966 `write-contract!` that returns the receipt."
  ([client] (act :write-contract-sync client))
  ([client opts] (act :write-contract-sync client opts)))

;; ---------------------------------------------------------------------------
;; ENS actions (chain needs contracts.ensUniversalResolver; names pre-normalized with utils/normalize)
;; ---------------------------------------------------------------------------

(defn get-ens-address!
  "viem `getEnsAddress`: `{:name (normalized) :coin-type :block-tag …}` -> address or nil."
  ([client] (act :get-ens-address client))
  ([client opts] (act :get-ens-address client opts)))

(defn get-ens-avatar!
  "viem `getEnsAvatar`: `{:name :asset-gateway-urls …}` -> avatar URL or nil."
  ([client] (act :get-ens-avatar client))
  ([client opts] (act :get-ens-avatar client opts)))

(defn get-ens-name!
  "viem `getEnsName`: `{:address …}` -> primary name or nil."
  ([client] (act :get-ens-name client))
  ([client opts] (act :get-ens-name client opts)))

(defn get-ens-resolver!
  "viem `getEnsResolver`: `{:name}` -> resolver address."
  ([client] (act :get-ens-resolver client))
  ([client opts] (act :get-ens-resolver client opts)))

(defn get-ens-text!
  "viem `getEnsText`: `{:name :key}` -> text record or nil."
  ([client] (act :get-ens-text client))
  ([client opts] (act :get-ens-text client opts)))

;; ---------------------------------------------------------------------------
;; SIWE
;; ---------------------------------------------------------------------------

(defn verify-siwe-message!
  "viem `verifySiweMessage`: EIP-4361 `{:message :signature :address :domain :nonce :scheme :time}` -> boolean (from `viem/siwe`)."
  ([client] (act :verify-siwe-message client))
  ([client opts] (act :verify-siwe-message client opts)))

;; ---------------------------------------------------------------------------
;; Test actions (anvil / hardhat / ganache, via a test client)
;; ---------------------------------------------------------------------------

(defn drop-transaction!
  "viem `dropTransaction`: `<mode>_dropTransaction` `{:hash}` -> nil."
  ([client] (act :drop-transaction client))
  ([client opts] (act :drop-transaction client opts)))

(defn dump-state!
  "viem `dumpState`: `<mode>_dumpState` -> hex state blob."
  ([client] (act :dump-state client))
  ([client opts] (act :dump-state client opts)))

(defn get-automine!
  "viem `getAutomine`: `<mode>_getAutomine` -> boolean."
  ([client] (act :get-automine client))
  ([client opts] (act :get-automine client opts)))

(defn get-txpool-content!
  "viem `getTxpoolContent`: `txpool_content` -> `{:pending :queued}`."
  ([client] (act :get-txpool-content client))
  ([client opts] (act :get-txpool-content client opts)))

(defn get-txpool-status!
  "viem `getTxpoolStatus`: `txpool_status` -> `{:pending :queued}` numbers."
  ([client] (act :get-txpool-status client))
  ([client opts] (act :get-txpool-status client opts)))

(defn impersonate-account!
  "viem `impersonateAccount`: `<mode>_impersonateAccount` `{:address}` -> nil."
  ([client] (act :impersonate-account client))
  ([client opts] (act :impersonate-account client opts)))

(defn increase-time!
  "viem `increaseTime`: `evm_increaseTime` `{:seconds n}` -> hex."
  ([client] (act :increase-time client))
  ([client opts] (act :increase-time client opts)))

(defn inspect-txpool!
  "viem `inspectTxpool`: `txpool_inspect` -> map."
  ([client] (act :inspect-txpool client))
  ([client opts] (act :inspect-txpool client opts)))

(defn load-state!
  "viem `loadState`: `<mode>_loadState` `{:state hex}` -> nil."
  ([client] (act :load-state client))
  ([client opts] (act :load-state client opts)))

(defn mine!
  "viem `mine`: `<mode>_mine` `{:blocks n :interval? secs}` -> nil."
  ([client] (act :mine client))
  ([client opts] (act :mine client opts)))

(defn remove-block-timestamp-interval!
  "viem `removeBlockTimestampInterval`: `<mode>_removeBlockTimestampInterval` -> nil."
  ([client] (act :remove-block-timestamp-interval client))
  ([client opts] (act :remove-block-timestamp-interval client opts)))

(defn reset!
  "viem `reset`: `<mode>_reset` `{:block-number :json-rpc-url}` -> nil."
  ([client] (act :reset client))
  ([client opts] (act :reset client opts)))

(defn revert!
  "viem `revert`: `evm_revert` `{:id snapshot-id}` -> nil."
  ([client] (act :revert client))
  ([client opts] (act :revert client opts)))

(defn send-unsigned-transaction!
  "viem `sendUnsignedTransaction`: `eth_sendUnsignedTransaction` with a tx request -> hash."
  ([client] (act :send-unsigned-transaction client))
  ([client opts] (act :send-unsigned-transaction client opts)))

(defn set-balance!
  "viem `setBalance`: `<mode>_setBalance` `{:address :value wei}` -> nil."
  ([client] (act :set-balance client))
  ([client opts] (act :set-balance client opts)))

(defn set-block-gas-limit!
  "viem `setBlockGasLimit`: `evm_setBlockGasLimit` `{:gas-limit}` -> nil."
  ([client] (act :set-block-gas-limit client))
  ([client opts] (act :set-block-gas-limit client opts)))

(defn set-block-timestamp-interval!
  "viem `setBlockTimestampInterval`: `<mode>_setBlockTimestampInterval` `{:interval secs}` -> nil."
  ([client] (act :set-block-timestamp-interval client))
  ([client opts] (act :set-block-timestamp-interval client opts)))

(defn set-code!
  "viem `setCode`: `<mode>_setCode` `{:address :bytecode}` -> nil."
  ([client] (act :set-code client))
  ([client opts] (act :set-code client opts)))

(defn set-coinbase!
  "viem `setCoinbase`: `<mode>_setCoinbase` `{:address}` -> nil."
  ([client] (act :set-coinbase client))
  ([client opts] (act :set-coinbase client opts)))

(defn set-interval-mining!
  "viem `setIntervalMining`: `evm_setIntervalMining` `{:interval secs}` -> nil."
  ([client] (act :set-interval-mining client))
  ([client opts] (act :set-interval-mining client opts)))

(defn set-min-gas-price!
  "viem `setMinGasPrice`: `<mode>_setMinGasPrice` `{:gas-price}` -> nil."
  ([client] (act :set-min-gas-price client))
  ([client opts] (act :set-min-gas-price client opts)))

(defn set-next-block-base-fee-per-gas!
  "viem `setNextBlockBaseFeePerGas`: `<mode>_setNextBlockBaseFeePerGas` `{:base-fee-per-gas}` -> nil."
  ([client] (act :set-next-block-base-fee-per-gas client))
  ([client opts] (act :set-next-block-base-fee-per-gas client opts)))

(defn set-next-block-timestamp!
  "viem `setNextBlockTimestamp`: `evm_setNextBlockTimestamp` `{:timestamp}` -> nil."
  ([client] (act :set-next-block-timestamp client))
  ([client opts] (act :set-next-block-timestamp client opts)))

(defn set-nonce!
  "viem `setNonce`: `<mode>_setNonce` `{:address :nonce n}` -> nil."
  ([client] (act :set-nonce client))
  ([client opts] (act :set-nonce client opts)))

(defn set-storage-at!
  "viem `setStorageAt`: `<mode>_setStorageAt` `{:address :index :value hex}` -> nil."
  ([client] (act :set-storage-at client))
  ([client opts] (act :set-storage-at client opts)))

(defn snapshot!
  "viem `snapshot`: `evm_snapshot` -> snapshot id hex."
  ([client] (act :snapshot client))
  ([client opts] (act :snapshot client opts)))

(defn stop-impersonating-account!
  "viem `stopImpersonatingAccount`: `<mode>_stopImpersonatingAccount` `{:address}` -> nil."
  ([client] (act :stop-impersonating-account client))
  ([client opts] (act :stop-impersonating-account client opts)))

(defn set-automine!
  "viem `setAutomine`: `evm_setAutomine` with a boolean -> nil."
  [client enabled]
  (core/action-scalar! :set-automine client enabled))

(defn set-logging-enabled!
  "viem `setLoggingEnabled`: `<mode>_setLoggingEnabled` with a boolean -> nil."
  [client enabled]
  (core/action-scalar! :set-logging-enabled client enabled))

(defn set-rpc-url!
  "viem `setRpcUrl`: `<mode>_setRpcUrl` with the URL string -> nil."
  [client url]
  (core/action-scalar! :set-rpc-url client url))
