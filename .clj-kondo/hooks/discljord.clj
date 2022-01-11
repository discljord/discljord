(ns hooks.discljord
  (:require [clj-kondo.hooks-api :as api]))

(defn unqualify-symbols [params-node]
  (when-not (api/vector-node? params-node)
    (throw (ex-info "Parameter lists must be vectors" {})))
  (->> params-node
       :children
       (mapv #(cond-> % (symbol? (:value %)) (-> :value name symbol api/token-node)))
       api/vector-node))

(defn defendpoint [{:keys [node]}]
  (let [[name _ _ req-bindings opt-bindings] (rest (:children node))
        un-req (unqualify-symbols req-bindings)
        un-opt (unqualify-symbols opt-bindings)]
    {:node (api/list-node
            (list
             (api/token-node 'def)
             name
             (api/list-node
              (list
               (api/token-node 'let)
               (api/vector-node
                [un-req (api/token-node nil)
                 un-opt (api/token-node nil)])
               un-req
               un-opt))))}))
