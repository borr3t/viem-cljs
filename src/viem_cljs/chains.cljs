(ns viem-cljs.chains
  "Chain registry access: every `viem/chains` export by kebab
   keyword (`:mainnet`, `:base-sepolia`, `:zk-sync` …) or by id, lazily —
   chain objects stay opaque and are never converted unless `chain->map` is
   asked. Aliases sharing an id (`:anvil` / `:foundry` / `:hardhat` = 31337)
   resolve by id to the FIRST registry name (`by-id`). Pure ClojureScript:
   all interop is in `viem-cljs.core`."
  (:require [viem-cljs.core :as core]))

(defn chain
  "The opaque viem chain object for a kebab keyword (`:mainnet`), a viem
   export name (\"baseSepolia\"), a chain id (`8453`), a kebab map (built with
   `define-chain`), or a chain object (returned as is). Unknown keyword / id
   -> :viem-cljs/config-error {:reason :unknown-chain}."
  [x]
  (core/chain-object x))

(defn chain?
  "True when `x` is a viem chain object."
  [x]
  (core/chain-object? x))

(defn known?
  "True when `x` (keyword, export name or id) is in the registry."
  [x]
  (try (some? (chain x))
       (catch :default _ false)))

(defn chains
  "Every registry chain as a kebab keyword, in viem's export order (737
   entries in viem 2.56.9, aliases included)."
  []
  (core/chain-keywords))

(defn chain-count
  "Number of registry exports (aliases included)."
  []
  (count (chains)))

(defn chain-id
  "The numeric chain id of anything `chain` accepts."
  [x]
  (core/chain-get x "id"))

(defn chain-name
  "The display name of a chain (\"Ethereum\", \"Base Sepolia\" …)."
  [x]
  (core/chain-get x "name"))

(defn chain-ids
  "All distinct chain ids in the registry, in registry order (709 in viem
   2.56.9)."
  []
  (distinct (map chain-id (chains))))

(defn by-id
  "The canonical kebab keyword for a chain id — the first registry name with
   that id (`31337` -> `:anvil`); nil when unknown."
  [id]
  (core/chain-id->keyword id))

(defn aliases
  "Every registry keyword sharing the id of `x` (`:anvil` -> `[:anvil :foundry
   :hardhat]`), canonical first."
  [x]
  (let [id (chain-id x)]
    (vec (filter #(= id (chain-id %)) (chains)))))

(defn chain->map
  "Data projection of a chain: `{:id :name :native-currency :rpc-urls
   :block-explorers :contracts :testnet :block-time :source-id :formatters?
   :serializers? …}` — function-valued fields (`extend`, `formatters`,
   `serializers`, `fees`) are dropped."
  [x]
  (core/chain->map x))

(defn define-chain
  "Builds a chain object from a kebab map (viem `defineChain`):
   `{:id 31337 :name \"Local\" :native-currency {:name \"Ether\" :symbol \"ETH\"
   :decimals 18} :rpc-urls {:default {:http [\"http://127.0.0.1:8545\"]}}
   :block-explorers {:default {:name .. :url ..}} :contracts {:multicall3
   {:address ..}} :testnet true}`."
  [m]
  (core/define-chain m))

(defn extract-chain
  "The chain with `id` among `chains` (a vector of anything `chain`
   accepts) — viem `extractChain`; nil when absent."
  [chains id]
  (core/extract-chain chains id))

(defn contract-address
  "The address of a chain-level contract (`:multicall3`, `:ens-registry`,
   `:ens-universal-resolver` …) — viem `getChainContractAddress`; throws a
   wrapped `ChainDoesNotSupportContract` when the chain lacks it. Optional
   `block-number` checks the contract's `blockCreated`."
  ([chain contract] (core/chain-contract-address chain contract))
  ([chain contract block-number] (core/chain-contract-address chain contract block-number)))

(defn testnet?
  "True when the chain is flagged `testnet` (337 registry entries; note
   `:localhost` 1337 is unflagged)."
  [x]
  (boolean (core/chain-get x "testnet")))

(defn testnets
  "Lazy seq of the registry keywords flagged as testnets."
  []
  (filter testnet? (chains)))

(defn mainnets
  "Lazy seq of the registry keywords NOT flagged as testnets."
  []
  (remove testnet? (chains)))

(defn rpc-urls
  "The chain's default HTTP RPC URLs (vector)."
  [x]
  (vec (core/chain-get x "rpcUrls" "default" "http")))

(defn rpc-url
  "The chain's first default HTTP RPC URL (public, often rate-limited)."
  [x]
  (first (rpc-urls x)))

(defn ws-urls
  "The chain's default WebSocket RPC URLs (vector, may be empty)."
  [x]
  (vec (core/chain-get x "rpcUrls" "default" "webSocket")))

(defn ws-url
  "The chain's first default WebSocket RPC URL, nil when none."
  [x]
  (first (ws-urls x)))

(defn explorer-url
  "The default block explorer URL, nil when none."
  [x]
  (core/chain-get x "blockExplorers" "default" "url"))

(defn native-currency
  "`{:name :symbol :decimals}` of the chain's native currency."
  [x]
  (core/chain-get x "nativeCurrency"))

(defn contracts
  "The chain's `contracts` map (`{:multicall3 {:address .. :block-created ..}
   …}`), nil when none."
  [x]
  (core/chain-get x "contracts"))

(defn source-id
  "For an L2, the id of its settlement layer (`sourceId`), nil otherwise."
  [x]
  (core/chain-get x "sourceId"))
