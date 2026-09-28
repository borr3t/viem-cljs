# viem-cljs

Ethereum from ClojureScript: a wrapper of [viem](https://viem.sh).

`viem-cljs` wraps the main surface of viem, the TypeScript Ethereum library, for
ClojureScript on Node.js: clients and transports, the chain registry, the public, wallet
and test actions, local accounts and signing, contracts and every ABI codec, the pure
utilities, event watchers and a classification of what a node can throw. Options and
results are kebab-case keyword maps, amounts and block numbers are BigInt end to end,
asynchronous functions return [promesa](https://github.com/funcool/promesa) promises and
a watcher is a `core.async` channel. Encoding, decoding, hashing and signing need no
node: they are pure functions and run offline. `viem` is the only runtime npm dependency.

- [Quick start](#quick-start)
- [What to use it for](#what-to-use-it-for)
- [Options are yours](#options-are-yours)
- [Features](#features)
- [Error handling](#error-handling)
- [Reference](#reference)
- [Development](#development)

## Quick start

Encode a contract call, decode a result and a log, hash a string and sign a message. No
node, no network and no funds are involved. You need Node.js 20 or later and a JDK (for
shadow-cljs).

**1. Get the code.** [viem](https://viem.sh) itself is an npm package that your project
installs.

```sh
git clone https://github.com/borr3t/viem-cljs.git

mkdir -p my-app/src/quickstart && cd my-app
npm init -y
npm install viem
npm install --save-dev shadow-cljs
```

**2. Declare the dependency.** With the Clojure CLI, `deps.edn` names the checkout and
`shadow-cljs.edn` defers to it:

```clojure
;; deps.edn
{:paths ["src"]
 :deps {borr3t/viem-cljs {:local/root "../viem-cljs"}
        thheller/shadow-cljs {:mvn/version "2.28.23"}}}
```

```clojure
;; shadow-cljs.edn
{:deps true
 :builds {:app {:target :node-script
                :main quickstart.core/main
                :output-to "out/quickstart.js"}}}
```

Without the Clojure CLI, leave `deps.edn` out and put the sources on the path of
shadow-cljs:

```clojure
;; shadow-cljs.edn
{:source-paths ["src" "../viem-cljs/src"]
 :dependencies [[funcool/promesa "12.0.1"]]
 :builds {:app {:target :node-script
                :main quickstart.core/main
                :output-to "out/quickstart.js"}}}
```

**3. Write the program.** `src/quickstart/core.cljs`:

```clojure
(ns quickstart.core
  (:require [promesa.core :as p]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as viem]
            [viem-cljs.errors :as errors]
            [viem-cljs.utils :as utils]))

(def abi
  ["function balanceOf(address owner) view returns (uint256)"
   "function transfer(address to, uint256 amount) returns (bool)"
   "event Transfer(address indexed from, address indexed to, uint256 value)"])

(def account
  ;; account #0 of the Hardhat / anvil development mnemonic. The key is
  ;; public: use it in examples and tests, never to hold funds.
  (viem/private-key->account
   "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"))

(def recipient
  "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")

(def transfer-log
  ;; a Transfer log as a node returns it: topic0 is the hash of the event
  ;; signature, the indexed parameters follow, the value is the data
  {:topics ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
            "0x000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266"
            "0x00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8"]
   :data "0x0000000000000000000000000000000000000000000000000000000001851960"})

(defn quick-start
  "Promise of everything the quick start computes, as a map."
  []
  (p/let [amount    (utils/parse-units "25.5" 6)
          calldata  (contract/encode-function-data {:abi abi
                                                    :function-name :transfer
                                                    :args [recipient amount]})
          call      (contract/decode-function-data {:abi abi :data calldata})
          balance   (contract/decode-function-result {:abi abi
                                                      :function-name :balance-of
                                                      :data (:data transfer-log)})
          log       (contract/decode-event-log (assoc transfer-log :abi abi))
          topic     (utils/event-selector "Transfer(address,address,uint256)")
          digest    (utils/keccak256 (utils/string->hex "hello"))
          signature (viem/sign-message account {:message "hello"})
          signer    (utils/recover-message-address {:message "hello"
                                                    :signature signature})
          valid?    (utils/verify-message {:address (:address (viem/account->map account))
                                           :message "hello"
                                           :signature signature})]
    {:amount amount
     :calldata calldata
     :call call
     :balance balance
     :formatted (utils/format-units balance 6)
     :log log
     :topic topic
     :digest digest
     :signature signature
     :signer signer
     :valid? valid?}))

(def printed
  "The keys of the quick start in the order `main` prints them."
  [:amount :calldata :call :balance :formatted :log :topic :digest :signature
   :signer :valid?])

(defn main [& _]
  (-> (quick-start)
      (p/then (fn [result]
                (doseq [k printed]
                  (println k (pr-str (get result k))))))
      (p/catch (fn [e]
                 (println (errors/summary e))
                 (js/process.exit 1)))))
```

**4. Run it.**

```sh
npx shadow-cljs compile app
node out/quickstart.js
```

It prints the following. Every value is a function of the input, so yours are the same:

```clojure
:amount 25500000N
:calldata "0xa9059cbb00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c80000000000000000000000000000000000000000000000000000000001851960"
:call {:function-name "transfer", :args ["0x70997970C51812dc3A010C7d01b50e0d17dc79C8" 25500000N]}
:balance 25500000N
:formatted "25.5"
:log {:event-name "Transfer", :args {:from "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", :to "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", :value 25500000N}}
:topic "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
:digest "0x1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8"
:signature "0xf16ea9a3478698f695fd1401bfe27e9e4a7e8e3da94aa72b021125e31fa899cc573c48ea3fe1d4ab61a9db10c19032026e3ed2dbccba5a178235ac27f94504311c"
:signer "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
:valid? true
```

| Line | What it is |
|------|------------|
| `:amount` | `"25.5"` of a token with 6 decimals, as the integer a contract takes. A BigInt: it prints as `25500000N` |
| `:calldata` | the call `transfer(recipient, amount)`: the 4-byte selector `0xa9059cbb` and one 32-byte word per argument |
| `:call` | the calldata decoded again against the ABI; the address comes back checksummed |
| `:balance` | 32 bytes of return data decoded against the outputs of `balanceOf` |
| `:formatted` | the balance as a decimal string, scaled by the 6 decimals |
| `:log` | the log decoded: the event is found by `topic0`, the parameters come back under their names |
| `:topic` | `topic0` of the event, the keccak-256 hash of its signature |
| `:digest` | keccak-256 of the bytes of `"hello"` |
| `:signature` | the EIP-191 signature of `"hello"`: 65 bytes, `r`, `s` and `v` |
| `:signer` | the address recovered from message and signature: the address of the account |
| `:valid?` | the signature verified against that address |

The checkout runs the same program without a project of your own:

```sh
cd viem-cljs
npm install
npx shadow-cljs compile examples && node target/examples.js
```

## What to use it for

The three examples below take a client, so they run against any JSON-RPC endpoint:

```clojure
(ns my.app
  (:require [cljs.core.async :as async]
            [promesa.core :as p]
            [viem-cljs.actions :as actions]
            [viem-cljs.contract :as contract]
            [viem-cljs.core :as viem]
            [viem-cljs.errors :as errors]
            [viem-cljs.events :as events]
            [viem-cljs.utils :as utils]))
```

### Read a token balance

A client is a chain and a transport. A contract is data: `contract/bind` returns a map of
the client, the address and the ABI, and `contract/read!` calls a function of it. The
three reads are pinned to one block, so they describe the same state.

```clojure
(defn client
  "A read-only client of Ethereum mainnet over the JSON-RPC endpoint `url`."
  [url]
  (viem/public-client {:chain :mainnet
                       :transport (viem/http url)}))

(defn token-balance!
  "Promise of the balance `owner` holds of the ERC-20 `token`:
   `{:symbol :decimals :raw :amount :block-number}`."
  [client token owner]
  (let [erc20 (contract/bind {:client client
                              :address token
                              :abi contract/erc20-abi})]
    (p/let [block-number (actions/get-block-number! client)
            at           {:block-number block-number}
            symbol       (contract/read! erc20 (assoc at :function-name :symbol))
            decimals     (contract/read! erc20 (assoc at :function-name :decimals))
            raw          (contract/read! erc20 (assoc at
                                                      :function-name :balance-of
                                                      :args [owner]))]
      {:symbol symbol
       :decimals decimals
       :raw raw
       :amount (utils/format-units raw decimals)
       :block-number block-number})))
```

```clojure
(token-balance! (client "https://ethereum-rpc.publicnode.com")
                "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
;; resolves to a map of this shape; the values are an example
{:symbol "USDC" :decimals 6 :raw 25500000N :amount "25.5" :block-number 16N}
```

`:raw` is the authoritative value. `:amount` is computed on the BigInt; no float touches
it.

### Send a transaction

A wallet client signs with a local account: the transaction is signed in your process and
broadcast with `eth_sendRawTransaction`, and the key never reaches the node. The transfer
is simulated first. A call that would revert is refused by the simulation and nothing is
sent.

```clojure
(defn wallet
  "A wallet client of `chain` over `url` that signs with the local `account`."
  [chain url account]
  (viem/wallet-client {:chain chain
                       :transport (viem/http url)
                       :account account}))

(defn transfer!
  "Transfers `amount` (a decimal string) of the ERC-20 `token` to `to`:
   simulates the call, sends what was simulated, waits for the receipt.
   Promise of `{:hash :status :block-number :transfers}`."
  [wallet token to amount decimals]
  (let [erc20 (contract/bind {:client wallet
                              :address token
                              :abi contract/erc20-abi})]
    (p/let [simulation (contract/simulate! erc20 {:function-name :transfer
                                                  :args [to (utils/parse-units amount decimals)]})
            hash       (contract/write! erc20 (:request simulation))
            receipt    (actions/wait-for-transaction-receipt! wallet {:hash hash})]
      {:hash hash
       :status (:status receipt)
       :block-number (:block-number receipt)
       :transfers (mapv :args (contract/parse-event-logs {:abi contract/erc20-abi
                                                          :event-name "Transfer"
                                                          :logs (:logs receipt)}))})))
```

```clojure
(transfer! (wallet :sepolia url account) token recipient "25.5" 6)
;; resolves to a map of this shape; the values are an example
{:hash "0x1111111111111111111111111111111111111111111111111111111111111111"
 :status "success"
 :block-number 16N
 :transfers [{:from "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
              :to "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
              :value 25500000N}]}
```

An account comes from a private key, from a mnemonic or from an encrypted keystore file
(`viem/account-from-keystore!`, Web3 Secret Storage version 3). Wrap a key that you hold
in `util/secret`: it prints as `#viem-cljs/secret <redacted>`.

### Watch events

A watcher is one `core.async` channel. Every value on it is a map tagged by `:event`, a
batch of n logs is n values in order, and closing the channel ends the subscription.

```clojure
(defn watch-transfers!
  "Watches the Transfer events of the ERC-20 `token` and hands every decoded
   log to `handle`. Returns the channel: (events/stop! ch) ends the watch."
  [client token handle opts]
  (let [erc20 (contract/bind {:client client
                              :address token
                              :abi contract/erc20-abi})
        ch    (events/watch-contract-event! client (assoc opts
                                                          :contract erc20
                                                          :event-name "Transfer"))]
    (async/go-loop []
      (when-some [{:keys [event log error]} (async/<! ch)]
        (case event
          :log (handle log)
          :error (println (errors/summary error)))
        (recur)))
    ch))
```

```clojure
(def ch
  (watch-transfers! client token prn {:polling-interval 4000 :buf 256}))
;; ... later
(events/stop! ch)
```

| `:event` | Watcher | Carries |
|----------|---------|---------|
| `:block-number` | `watch-block-number!` | `:block-number`, `:prev` |
| `:block` | `watch-blocks!` | `:block`, `:prev` |
| `:block-header` | `watch-block-headers!` | `:header`, `:prev` |
| `:log` | `watch-event!`, `watch-contract-event!` | `:log`, decoded when an ABI or an event is given |
| `:pending-transaction` | `watch-pending-transactions!` | `:hash` |
| `:error` | every watcher | `:error`; the channel stays open |

Over a WebSocket transport a watcher subscribes; over HTTP it polls.

## Options are yours

The library decides nothing about timing, retries or fees. An option map is translated
key by key and handed to viem: `:timeout`, `:retry-count`, `:retry-delay`,
`:polling-interval`, `:confirmations`, `:gas`, `:max-fee-per-gas` and `:nonce` take the
value you give. An option that is absent is absent for viem as well, and the default
that [viem documents](https://viem.sh/docs/clients/intro) for it applies.

```clojure
(viem/public-client {:chain :mainnet
                     :polling-interval 4000
                     :transport (viem/fallback [(viem/http url-a {:timeout 10000 :retry-count 3})
                                                (viem/http url-b {:timeout 10000 :retry-count 3})])})

(actions/wait-for-transaction-receipt! client {:hash hash
                                               :confirmations 3
                                               :timeout 120000})
```

Two values are set by the library itself, and both can be overridden:

| Option | Where | Absent |
|--------|-------|--------|
| `:buf` | every `watch-*!` | the channel buffers 1024 values. Give a number, or a buffer such as `(async/sliding-buffer 64)` |
| `:mode` | `viem/test-client` | `:anvil`. Give `:hardhat` or `:ganache` |

`(viem/http)` without a URL uses the public endpoint the chain registry lists for the
chain. It is rate limited and comes without a promise of availability; give the URL of
your own endpoint for anything that matters.

## Features

The one-liners use `abi`, `account` and `recipient` of the quick start; `chains` is
`viem-cljs.chains` and `util` is `viem-cljs.util`.

### Units and BigInt: `viem-cljs.utils`

Amounts are BigInt from the node to your code and back. They are equal by value, usable
as map keys and printed as `1500000000000000000N`. Arithmetic goes through `utils/add`,
`sub`, `mul`, `div`, `cmp`, `lt?` and their kin, which take a BigInt, a number or a
decimal string.

```clojure
(utils/parse-ether "1.5")
;; => 1500000000000000000N

(utils/format-ether (utils/parse-gwei "21000"))
;; => "0.000021"

(utils/format-units (utils/mul (utils/parse-units "25.5" 6) 2) 6)
;; => "51"

(utils/lt? (utils/parse-gwei "30") (utils/parse-ether "1"))
;; => true

(= (utils/big "25500000") (utils/parse-units "25.5" 6))
;; => true
```

### Addresses, hashes and selectors: `viem-cljs.utils`

```clojure
(utils/get-address "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
;; => "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

(utils/address? "0xf39fd6e51aad88f6f4ce6ab8827279cfffb9226")
;; => false

(utils/function-selector "transfer(address,uint256)")
;; => "0xa9059cbb"

(utils/namehash "vitalik.eth")
;; => "0xee6c4522aab0003e8d14cd40a6af439055fd2577951148c14b6cea9a53475835"
```

Also: signatures (parse, serialize, recover, verify), hex and bytes conversions, RLP,
transaction serialization and parsing, EIP-712 typed data and EIP-7702 authorizations.

### Contracts and ABI codecs: `viem-cljs.contract`

An ABI is a vector of human-readable signatures or the JSON ABI as kebab-case maps.
Function names are keywords or strings: `:balance-of` is `balanceOf`.

```clojure
(contract/format-abi abi)
;; => ["balanceOf(address)" "transfer(address,uint256)" "Transfer(address,address,uint256)"]

(contract/encode-abi-parameters "address, bool" [recipient true])
;; => "0x00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c80000000000000000000000000000000000000000000000000000000000000001"
```

| Function | Result |
|----------|--------|
| `encode-function-data`, `decode-function-data` | calldata from and to `{:function-name :args}` |
| `decode-function-result`, `encode-function-result` | return data from and to values |
| `decode-event-log`, `parse-event-logs`, `encode-event-topics` | logs from and to `{:event-name :args}` |
| `decode-error-result`, `encode-error-result` | revert data from and to `{:error-name :args}` |
| `encode-abi-parameters`, `decode-abi-parameters`, `encode-packed` | positional encoding |
| `bind` | a contract as a map, for `read!`, `simulate!`, `write!`, `simulate-and-write!`, `estimate-gas!`, `events!` |
| `deploy!`, `multicall!` | a deployment; many reads in one call through Multicall3 |
| `erc20-abi`, `erc721-abi`, `erc1155-abi`, `erc4626-abi`, `multicall3-abi` | the standard ABIs as data |

### Chains: `viem-cljs.chains`

Every chain of the viem registry by keyword or by id.

```clojure
(chains/chain-id :base)
;; => 8453

(chains/chain-name (chains/by-id 10))
;; => "OP Mainnet"

(chains/native-currency :polygon)
;; => {:name "POL", :symbol "POL", :decimals 18}

(chains/testnet? :sepolia)
;; => true
```

`chains/define-chain` makes a chain of your own from a map.

### Accounts and signing: `viem-cljs.core`

Local accounts from a private key, a mnemonic, an HD key or a keystore file; accounts with
signers of your own (`custom-account`); accounts a node signs for (`address-account`).
Signing with a local account is pure: `sign-message`, `sign-typed-data`,
`sign-transaction`, `sign-authorization`, `sign-hash`.

```clojure
(viem/account->map account)
;; => {:address "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", :type "local", :source "privateKey", :public-key "0x048318535b54105d4a7aae60c08fc45f9687181b4fdfc625bd1a753fa7397fed753547f11ca8696646f2f3acb08e31016afac23e630c5d11f59f61fef57b0d2aa5"}

(:address (viem/account->map (viem/mnemonic->account "test test test test test test test test test test test junk" {:address-index 1})))
;; => "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
```

Key material stays out of logs and data. The printable view of an account has no key, a
secret prints redacted, and a configuration is scrubbed before it is printed:

```clojure
(pr-str (util/secret "a key, a mnemonic or a password"))
;; => "#viem-cljs/secret <redacted>"

(util/redact {:address recipient, :private-key "0x01"})
;; => {:address "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", :private-key "<redacted>"}
```

### Clients and transports: `viem-cljs.core`

`public-client`, `wallet-client` and `test-client` over `http`, `web-socket`, `ipc`,
`fallback` or `custom`. A custom transport is a function of a request, which makes an
in-memory node for tests:

```clojure
(viem/public-client {:chain :mainnet
                     :transport (viem/custom (fn [{:keys [method params]}]
                                               (case method
                                                 "eth_chainId" "0x1"
                                                 "eth_blockNumber" "0x10")))})
```

### Actions: `viem-cljs.actions`

One function per viem action, named in kebab-case with a `!`: `get-block!`,
`get-balance!`, `get-logs!`, `estimate-gas!`, `send-transaction!`,
`wait-for-transaction-receipt!`, the ENS reads, and the test actions of anvil, Hardhat
and Ganache (`mine!`, `set-balance!`, `snapshot!`, `impersonate-account!`). Each takes the
client first and an option map second, and returns a promise.

### Watchers: `viem-cljs.events`

The six watchers of the table above as channels, `stop!`, `watching?`, and the filter
functions for pulling changes by hand.

### Errors: `viem-cljs.errors`

Accessors, classifiers and retry advice over everything the library rejects with; see
the next section.

## Error handling

Functions ending in `!` reject and never throw: a bad argument, an error of the node and
an error of the transport all arrive as the rejection of the promise. Synchronous
functions throw. Either way the error is an `ex-info` whose `ex-data` carries a `:type`:

| `:type` | Meaning |
|---------|---------|
| `:viem-cljs/argument-error` | an argument was refused before viem was called |
| `:viem-cljs/config-error` | a client, transport or account is misconfigured (`:reason`) |
| `:viem-cljs/viem-error` | viem or the node raised it: `:name` is the viem error class, `:causes` the chain of causes, outermost first |

`viem-cljs.errors` reads an error without knowledge of viem's classes. The transfer of
the section above, with every failure turned into data:

```clojure
(defn explain
  "What went wrong, as data."
  [e]
  {:category (errors/category e)
   :name (errors/error-name e)
   :action (errors/action e)
   :revert (errors/revert-error-name e)
   :revert-args (errors/revert-args e)
   :reason (errors/revert-reason e)
   :retryable? (errors/retryable? e)
   :retry-hint (errors/retry-hint e)})

(defn try-transfer!
  "Promise of the result of `transfer!`, or of `{:failed (explain e)}`.
   Never rejects."
  [wallet token to amount decimals]
  (-> (transfer! wallet token to amount decimals)
      (p/catch (fn [e] {:failed (explain e)}))))
```

A transfer of more than the sender holds reverts in the simulation, and nothing is sent:

```clojure
(try-transfer! wallet token recipient "25.5" 6)
;; resolves to
{:failed {:category :reverted
          :name "ContractFunctionExecutionError"
          :action :simulate-contract
          :revert "Error"
          :revert-args ["ERC20: transfer amount exceeds balance"]
          :reason "ERC20: transfer amount exceeds balance"
          :retryable? false
          :retry-hint nil}}
```

`errors/category` is one of `:argument` `:config` `:user-rejected` `:insufficient-funds`
`:reverted` `:zero-data` `:abi` `:nonce` `:fee` `:gas` `:rate-limited` `:timeout`
`:connection` `:transient-rpc` `:not-found` `:unsupported-method` `:chain` `:viem`.
`errors/retry-hint` says what a retry needs:

| Hint | Categories | Before sending again |
|------|------------|----------------------|
| `:backoff` | `:rate-limited` `:connection` `:transient-rpc` | wait |
| `:requery` | `:timeout` | look whether the request went through |
| `:refresh-nonce` | `:nonce` | fetch the pending nonce |
| `:bump-fee` | `:fee` | raise the fee |
| `nil` | every other | a retry does not help |

The categories are read from the viem error classes and, where a node reports only a
text, from the text of its message. The advice is a classification and no policy:
whether to retry, how often and after what delay is for the caller to decide.

## Reference

- [`docs/viem-cljs.org`](docs/viem-cljs.org) is the manual: clients and transports,
  chains, accounts and the keystore, the translation of options and results, contracts,
  utilities, watchers and their buffers, error handling, and an index of every public
  function by namespace.
- [`docs/viem-api-inventory.org`](docs/viem-api-inventory.org) maps the viem surface to
  the functions of this library, with what is wrapped and what is not.

Not wrapped: the chain-specific modules of viem (OP Stack, ZKsync, Celo, Linea), account
abstraction (ERC-4337), `viem/experimental`, the blob helpers of EIP-4844 and the
encryption of keystores. viem's `getContract` is replaced by the map of `contract/bind`.

Every public function carries a docstring; the namespaces are `viem-cljs.core`,
`viem-cljs.actions`, `viem-cljs.contract`, `viem-cljs.chains`, `viem-cljs.utils`,
`viem-cljs.events`, `viem-cljs.errors` and `viem-cljs.util`.

## Development

```sh
./run-tests.sh
```

runs the documentation gate and the test suite. It uses no network and no key of value:
every request goes to an in-memory node. The examples of this README are part of it: the
suite fails when the README shows code or values that differ from what runs.

```sh
npx shadow-cljs compile examples && node target/examples.js
```

runs the quick start.

```sh
npx shadow-cljs compile smoke && node target/smoke.js
```

drives the library against a local [anvil](https://book.getfoundry.sh/anvil/) node that
it starts and stops itself. It needs Foundry installed, talks to the loopback interface
only and moves no real funds.

```sh
./dev/check-api-index.sh
```

checks that every public function is listed in the index of the manual.

The library targets Node.js: the keystore uses Node's `crypto` and the IPC transport
Node's sockets. There is no browser build.

## License

MIT
