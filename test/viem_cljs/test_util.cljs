(ns viem-cljs.test-util
  "Shared fixtures for the viem-cljs tests: well-known PUBLIC addresses and
   hashes (never a private key — the smoke generates a fresh random key in a
   temp dir and discards it), plus promise/throw helpers. Not a test
   namespace itself (the name does not end in -test)."
  (:require [promesa.core :as p]))

(def zero-address "0x0000000000000000000000000000000000000000")

(def weth-address
  "Mainnet WETH9 (checksummed) — a public contract address."
  "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")

(def weth-address-lower "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")

(def multicall3-address "0xcA11bde05977b3631167028862bE2a173976CA11")

(def tx-hash
  "A syntactically valid 32-byte hash (invented)."
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(def erc20-abi
  "Human-readable ABI fragment (viem parseAbi input)."
  ["function balanceOf(address owner) view returns (uint256)"
   "function transfer(address to, uint256 amount) returns (bool)"
   "event Transfer(address indexed from, address indexed to, uint256 value)"])

(defn rejection
  "Promise of the rejection reason of `p` (fails the assertion if it resolves)."
  ;; NB: promesa treats a js/Error RETURNED from a catch handler as a
  ;; rejection (IPromiseFactory on js/Error), so the error must be wrapped in
  ;; an already-resolved promise to travel as a value.
  [promise]
  (-> promise
      (p/then (fn [v] (throw (ex-info "expected rejection" {:resolved v}))))
      (p/catch (fn [e] (p/resolved e)))))

(defn thrown
  "The value thrown by (f), nil when it returns normally."
  [f]
  (try (f) nil (catch :default e e)))
