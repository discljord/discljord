(ns discljord.specs
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

;; -------------------------------------------------
;; generic specs

(s/def ::url string?)
(s/def ::token string?)

(s/def ::future any?)
(s/def ::promise any?)
(s/def ::channel (partial satisfies? clojure.core.async.impl.protocols/Channel))

(defn atom-of?
  [s]
  (fn [x]
    (and (instance? clojure.lang.Atom x)
         (s/valid? s x))))

(s/def ::snowflake string?)

;; -------------------------------------------------
;; discljord.connection specs

(s/def ::shard-id int?)
(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::url ::shard-count]))

(s/def ::session-id (s/nilable string?))
(s/def ::seq (s/nilable int?))
(s/def ::shard-state (s/keys :req-un [::session-id ::seq]))

(s/def ::connection any?)

;; -------------------------------------------------
;; discljord.messaging specs

(s/def ::major-variable-type #{::guild-id ::channel-id ::webhook-id})
(s/def ::major-variable-value ::snowflake)
(s/def ::major-variable (s/keys :req [::major-variable-type
                                      ::major-variable-value]))

(s/def ::action keyword?)

(s/def ::endpoint (s/keys :req [::action]
                          :opt [::major-variable]))

(s/def ::rate number?)
(s/def ::remaining number?)
(s/def ::reset number?)
(s/def ::global boolean?)
(s/def ::rate-limit (s/keys :req [::rate ::remaining ::reset]
                            :opt [::global]))

(s/def ::endpoint-specific-rate-limits (s/map-of ::endpoint ::rate-limit))
(s/def ::global-rate-limit ::rate-limit)

(s/def ::rate-limits (s/keys :req [::endpoint-specific-rate-limits]
                             :opt [::global-rate-limit]))

(s/def ::process (s/keys :req [::rate-limits
                               ::channel
                               ::token]))

(s/def ::message (s/and string?
                        #(< (count %) 2000)))

(s/def ::channel-id ::snowflake)
