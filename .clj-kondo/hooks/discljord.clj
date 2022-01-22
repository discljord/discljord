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

(defn defdispatch [{:keys [node]}]
  (let [[_ params opts opts-sym
         _ status-sym body-sym url-str
         method-params promise-val] (rest (:children node))
        nil-node (api/token-node nil)]
    {:node (api/list-node
            (list
             (api/token-node 'let)
             (api/vector-node
              [params nil-node
               opts nil-node
               opts-sym nil-node
               status-sym (api/token-node 0)
               body-sym nil-node])
             url-str
             method-params
             promise-val))}))


(defn def-message-dispatch [{:keys [node]}]
  (let [[_ params _ url-str] (rest (:children node))]
    {:node (api/list-node
            (list
             (api/token-node 'let)
             (api/vector-node [params (api/token-node nil)])
             url-str))}))
