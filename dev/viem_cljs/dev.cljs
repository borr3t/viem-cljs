(ns viem-cljs.dev
  "Node-script entry point for interactive development."
  (:require [viem-cljs.util :as util]))

(defn main [& _]
  (println "viem-cljs dev runtime loaded. Attach with: npx shadow-cljs cljs-repl dev")
  (println "kebab->camel sanity:" (util/kebab->camel :block-number))
  (js/setInterval #(println "…dev heartbeat") 60000))
