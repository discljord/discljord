(ns discljord.specs
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

(s/def ::url string?)
(s/def ::token string?)

(s/def ::shard-id int?)
(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::url ::shard-count]))

(defn atom-of?
  [s]
  (fn [x]
    (and (instance? clojure.lang.Atom x)
         (s/valid? s x))))

(s/def ::channel (partial satisfies? clojure.core.async.impl.protocols/Channel))

(s/def ::session-id (s/nilable string?))
(s/def ::seq (s/nilable int?))
(s/def ::shard-state (s/keys :req-un [::session-id ::seq]))

(s/def ::connection any?)
(s/def ::future any?)
