(ns viem-cljs.chains-test
  (:require [cljs.test :refer [deftest is testing]]
            [viem-cljs.chains :as chains]
            [viem-cljs.test-util :as tu]
            [viem-cljs.util :as util]))

(deftest registry-census
  (is (= 737 (chains/chain-count)))
  (is (= 737 (count (chains/chains))))
  (is (= 709 (count (chains/chain-ids))) "distinct ids")
  (is (= 337 (count (chains/testnets))))
  (is (= 400 (count (chains/mainnets))))
  (is (every? keyword? (chains/chains)))
  (is (= :mainnet (first (filter #{:mainnet} (chains/chains))))))

(deftest lookup-by-keyword-name-id-and-aliases
  (is (= 1 (chains/chain-id :mainnet)))
  (is (= "Ethereum" (chains/chain-name :mainnet)))
  (is (= 11155111 (chains/chain-id :sepolia)))
  (is (= 84532 (chains/chain-id :base-sepolia)))
  (is (= 84532 (chains/chain-id "baseSepolia")))
  (is (= 324 (chains/chain-id :zk-sync)) "camelCase export zkSync")
  (is (= 324 (chains/chain-id :zksync)) "lowercase alias export")
  (is (= 31337 (chains/chain-id 31337)))
  (is (= :anvil (chains/by-id 31337)) "canonical = first registry name")
  (is (= [:anvil :foundry :hardhat] (chains/aliases :hardhat)))
  (is (= [:mainnet] (chains/aliases 1)))
  (is (nil? (chains/by-id 424242424)))
  (is (chains/known? :mainnet))
  (is (chains/known? 10))
  (is (not (chains/known? :narnia)))
  (is (chains/chain? (chains/chain :mainnet)))
  (is (not (chains/chain? {:id 1})))
  (is (identical? (chains/chain :mainnet) (chains/chain 1)))
  (let [e (tu/thrown #(chains/chain :narnia))]
    (is (= :viem-cljs/config-error (util/error-type e)))
    (is (= :unknown-chain (:reason (ex-data e))))))

(deftest chain-data-accessors
  (is (true? (chains/testnet? :sepolia)))
  (is (false? (chains/testnet? :mainnet)))
  (is (false? (chains/testnet? :localhost)) "localhost 1337 is unflagged")
  (is (= "http://127.0.0.1:8545" (chains/rpc-url :anvil)))
  (is (= ["http://127.0.0.1:8545"] (chains/rpc-urls :anvil)))
  (is (= "ws://127.0.0.1:8545" (chains/ws-url :anvil)))
  (is (string? (chains/rpc-url :mainnet)))
  (is (= "https://etherscan.io" (chains/explorer-url :mainnet)))
  (is (= {:name "Ether" :symbol "ETH" :decimals 18} (chains/native-currency :mainnet)))
  (is (= (clojure.string/lower-case tu/multicall3-address)
         (clojure.string/lower-case (chains/contract-address :mainnet :multicall3))))
  (is (= 14353601 (get-in (chains/contracts :mainnet) [:multicall3 :block-created])))
  (is (= 1 (chains/source-id :optimism)))
  (is (nil? (chains/source-id :mainnet)))
  (let [e (tu/thrown #(chains/contract-address :anvil :ens-registry))]
    (is (= "ChainDoesNotSupportContract" (util/error-name e)))))

(deftest chain-map-projection
  (let [m (chains/chain->map :mainnet)]
    (is (= 1 (:id m)))
    (is (= "Ethereum" (:name m)))
    (is (= "ETH" (get-in m [:native-currency :symbol])))
    (is (vector? (get-in m [:rpc-urls :default :http])))
    (is (= "Etherscan" (get-in m [:block-explorers :default :name])))
    (is (contains? (:contracts m) :ens-universal-resolver))
    (is (false? (:formatters? m)))
    (is (not-any? fn? (vals m)) "pure data")
    (is (not (contains? m :extend))))
  (is (true? (:serializers? (chains/chain->map :zk-sync))))
  (is (nil? (chains/chain->map nil))))

(deftest define-and-extract
  (let [local (chains/define-chain {:id 424242 :name "Local" :testnet true
                                    :native-currency {:name "Ether" :symbol "ETH" :decimals 18}
                                    :rpc-urls {:default {:http ["http://127.0.0.1:9999"] :web-socket ["ws://127.0.0.1:9999"]}}
                                    :block-explorers {:default {:name "X" :url "http://x"}}
                                    :contracts {:multicall3 {:address tu/multicall3-address}}})]
    (is (chains/chain? local))
    (is (= 424242 (chains/chain-id local)))
    (is (= "Local" (chains/chain-name local)))
    (is (chains/testnet? local))
    (is (= "http://127.0.0.1:9999" (chains/rpc-url local)))
    (is (= "ws://127.0.0.1:9999" (chains/ws-url local)))
    (is (= "http://x" (chains/explorer-url local)))
    (is (= tu/multicall3-address (chains/contract-address local :multicall3)))
    (is (= 424242 (chains/chain-id (chains/chain {:id 424242 :name "M" :native-currency {:name "E" :symbol "E" :decimals 18}
                                                  :rpc-urls {:default {:http []}}})))
        "chain accepts a kebab map")
    (is (= 11155111 (chains/chain-id (chains/extract-chain [:mainnet :sepolia local] 11155111))))
    (is (= 424242 (chains/chain-id (chains/extract-chain [:mainnet local] 424242))))
    (is (nil? (chains/extract-chain [:mainnet] 5)))))
