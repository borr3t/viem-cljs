# viem-cljs

ClojureScript (Node.js) wrapper of [viem](https://viem.sh), the TypeScript Ethereum
library: clients and transports, the chain registry, public/wallet/test actions,
accounts, contracts (ABI codecs, read/write/simulate/multicall), utilities and event
watchers. A **complete wrapper** of viem's main surface, written as ClojureScript only.

The authoritative spec is `docs/viem-api-inventory.org` (viem 2.56.9 census, coverage
tiers, translation rules, 19 numbered uncertainties). Zero runtime npm dependencies
beyond `viem` itself; promesa promises in and out; kebab-case maps in and out; watchers
are core.async channels.

## Hard style rules

- ClojureScript only: no `^js` type hints anywhere, no JavaScript interop at call sites.
  All viem interop is confined to `viem-cljs.core` (the binding namespace: `js-invoke` +
  `goog.object` house style) and the small boundary helpers in `viem-cljs.util`; every
  other namespace (`actions`, `contract`, `chains`, `utils`, `events`) is plain
  ClojureScript calling those two.
- kebab-case keyword maps in (`{:block-tag :latest}`) and out (`{:block-number 16N}`);
  keys are re-cased mechanically, values never are (hex strings stay strings).
- promesa promises for every read/call; core.async channels for watchers (one event per
  put, close = unsubscribe).
- Wei, gas and block numbers stay JavaScript BigInt (opaque, exact); the library makes
  BigInt hashable and prints it as `1000N`. Arithmetic on them uses the provided helpers
  only (`utils/add`, `utils/mul`, `utils/format-units` …), never `+`/`inc` with CLJS
  numbers. See inventory §11.3 and §12.1.
- `!` suffix on every fn that can hit the network or move chain state; pure/local fns
  (format/parse, checksums, address tests, encode/decode, local signing) have none.
- Zero runtime npm deps beyond `viem`.
- Never print or persist private keys: tests use public fixtures only; the smoke
  generates a fresh random key in-process and discards it.

## Status

| Phase | Content | State |
|-------|---------|-------|
| 0 | scaffold: shadow-cljs `:node-test` / `:dev` builds, `run-tests.sh`, `util` (kebab/camel, BigInt, error envelope) | done |
| 1 | viem API inventory (`docs/viem-api-inventory.org`) | done |
| 2 | `core` (the one interop namespace: clients, transports, chain registry, accounts incl. `account-from-keystore!`, action/watcher/utility dispatch), `chains` (registry access, `define-chain`, projections) | done |
| 3 | `actions` (40 public + `wait-for-transaction-receipt!` + 26 wallet + 5 ens + 1 siwe + 30 test actions = 103 fns), `contract` (ABI codecs, `bind` / `get-contract` data contract, `read!` `simulate!` `write!` `simulate-and-write!` `estimate-gas!` `events!` `deploy!` `multicall!`, standard ABIs as data) | done |
| 4 | `utils` (≈120 pure fns: units, address, hash, signature, encoding, data, rlp, transaction, typed data, authorization, ens hashing, constants, BigInt helpers), `events` (six watchers as channels + `stop!`, seven filter fns) — pulled into phase 3; `errors` accessor namespace (revert decoding, `rpc-code`, `user-rejected?` …) still pending | utils + events done, `errors` planned |
| 5 | live smoke (`dev/viem_cljs/smoke.cljs`): spawns `~/.foundry/bin/anvil` as a subprocess (test + wallet + contract sections) and read-only public RPC sections, PASS/FAIL report | planned |
| 6 | tier-2 extensions decided in inventory §10 (siwe builders, tokens, ens extras, blob helpers; op-stack / zksync / account-abstraction as separate follow-ups) | planned |

`./run-tests.sh` — 63 tests / 728 assertions, no network, no keys (transports are
`core/custom` over an in-memory JSON-RPC stub, `test/viem_cljs/test_util.cljs`).

## Namespaces

| Namespace | Role |
|-----------|------|
| `viem-cljs.core` | clients (`client` `public-client` `wallet-client` `test-client`, `client-info`, `request!`), transport specs (`http` `web-socket` `ipc` `custom` `fallback`, or shorthand maps `{:http url}`), chain registry primitives, accounts (`private-key->account` `mnemonic->account` `hd-key->account` `custom-account` `address-account` `account-from-keystore!` `account->map` `generate-private-key` `generate-mnemonic` `wordlist` `nonce-manager`), local signing (`sign-message` `sign-typed-data` `sign-transaction` `sign-authorization` `sign-hash`), option translation (`opts->js`), dispatch (`action!` `action-scalar!` `watch!` `invoke-util` `constant`) |
| `viem-cljs.actions` | every viem action, `<kebab-name>!` (`get-block!`, `send-transaction!`, `mine!` …) |
| `viem-cljs.contract` | `parse-abi` and friends, every ABI codec, `bind` → data contract, `read!` `simulate!` `write!` … `multicall!`, `erc20-abi` … |
| `viem-cljs.chains` | `chain` `chains` `by-id` `aliases` `chain->map` `define-chain` `extract-chain` `contract-address` `testnet?` `rpc-url` `ws-url` `explorer-url` `native-currency` … |
| `viem-cljs.utils` | pure utilities + BigInt helpers (`big` `add` `sub` `mul` `div` `cmp` …) + constants |
| `viem-cljs.events` | `watch-block-number!` `watch-blocks!` `watch-block-headers!` `watch-event!` `watch-contract-event!` `watch-pending-transactions!` → channel, `stop!`, `watching?`, filter fns |
| `viem-cljs.util` | boundary helpers (kebab/camel, `->js` `->clj`, BigInt, the error envelope + `error-name` `error-causes` `error-cause?`) |

## Coverage tiers (inventory §10)

- **Tier 1 (complete wrap, phases 2–4):** clients (`createClient` / public / wallet / test),
  transports (`http` `webSocket` `fallback` `custom` `ipc`), the whole chain registry
  (737 exports) and `defineChain`, all 113 non-deprecated actions, accounts (private key,
  mnemonic/HD, custom signer, json-rpc), every ABI codec + `parseAbi`, ≈90 utilities,
  the six watchers, the error envelope.
- **Tier 2 (later / partial):** siwe message builders, `viem/tokens`, ENS extras,
  EIP-4844 blob helpers (need a KZG impl), `viem/experimental`, ERC-4337
  (`viem/account-abstraction`), chain-specific modules (op-stack, zksync, celo, linea, tempo).
- **Tier 3 (not wrapped):** decorated client methods (same fns as `viem/actions`),
  `getContract` Proxy instances (replaced by the data-based `contract/get-contract` map), deprecated
  aliases, internal formatters/rpc clients, `viem/window`.

## Quick start

```clojure
(require '[viem-cljs.core :as core]
         '[viem-cljs.actions :as actions]
         '[viem-cljs.contract :as contract]
         '[viem-cljs.utils :as utils]
         '[viem-cljs.events :as events]
         '[promesa.core :as p]
         '[cljs.core.async :as async])

(def c (core/public-client {:chain :mainnet
                            :transport (core/http "https://rpc.example")}))   ; or {:http "https://rpc.example"}

(p/let [n   (actions/get-block-number! c)                        ; 23085558N (BigInt)
        bal (actions/get-balance! c {:address "0xC02a…6Cc2"})    ; wei as BigInt
        b   (actions/get-block! c {:block-tag :latest})]         ; kebab map
  (println (utils/format-ether bal) (:timestamp b)))

(def weth (contract/bind {:client c :address "0xC02a…6Cc2"
                          :abi ["function totalSupply() view returns (uint256)"]}))
(p/let [supply (contract/read! weth {:function-name :total-supply})]   ; BigInt
  (println (utils/format-units supply 18)))

;; wallet: local account, simulate -> write pair
(def w (core/wallet-client {:chain :sepolia :transport {:http "https://rpc.example"}
                            :account (core/private-key->account pk)}))       ; or (core/account-from-keystore! json pw)
(p/let [{:keys [request result]} (actions/simulate-contract! w {:abi abi :address addr
                                                                :function-name :transfer :args [to (utils/parse-ether "1")]})
        hash (actions/write-contract! w request)
        receipt (actions/wait-for-transaction-receipt! w {:hash hash})]
  (println (:status receipt)))

(let [ch (events/watch-block-number! c {:emit-on-begin? true})]
  ;; events: {:event :block-number :block-number 100N :prev 99N} | {:event :error :error ex}
  (async/go (println (async/<! ch)) (events/stop! ch)))
```

Tests use `(core/custom request-fn)` — any `(fn [{:keys [method params]}] result)` — as the
transport, so every action and watcher runs against an in-memory node.

## Errors

Rejections are `ex-info` values (`viem-cljs.util/error-data`):
`:viem-cljs/viem-error` (any viem throw: `:name` is the viem class,
`:short-message`, `:details`, `:meta-messages`, `:code`, `:causes` — the cause chain,
outermost first — and `:cause`, the raw error), `:viem-cljs/argument-error`,
`:viem-cljs/config-error`. `util/error-cause?` is the CLJS equivalent of viem's
`err.walk(e => e instanceof X)`.

## Development

```
./run-tests.sh                                  # exec's node at the end — never chain it mid-command
npx shadow-cljs watch dev && node target/dev.js # REPL runtime
```
