(ns discljord.connections.specs
  "Contains all the specs required for the `discljord.connections` namespace."
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

;; ---------------------------------------------------
;; Domain Model

(s/def ::heartbeat-interval (s/nilable number?))
(s/def ::last-heartbeat (s/nilable number?))
(s/def ::connection any?)
(s/def ::connection-state (ds/atom-of? #{::connected ::disconnected}))
(s/def ::messages ::ds/channel)
(s/def ::shard (s/keys :req-un [::heartbeat-interval ::last-heartbeat
                                ::connection ::connection-state
                                ::messages]))

(s/def ::shards (s/coll-of ::shard))
(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::ds/url ::shard-count]))
(s/def ::bot-connection (s/keys :req-un [::ds/token ::shards]
                                :opt-un [::gateway]))

(s/def ::op-code int?)
(s/def ::message-data any?)
(s/def ::session string?)
(s/def ::seq int?)
(s/def ::message (s/keys :req [::op-code ::message-data]
                         :opt [::seq ::session]))

(s/def ::event-type keyword?)
(s/def ::event-data any?)
(s/def ::event (s/keys :req [::event-type ::event-data]))

(s/def ::command-type #{::disconnect ::update-status
                        ::update-voice-state
                        ::request-guild-members})
(s/def ::command-data any?)
(s/def ::command (s/keys :req [::command-type ::command-data]))

;; ---------------------------------------------------
;; Specific command specs

(s/def ::query string?)
(s/def ::limit number?)

(s/def ::name string?)
(s/def ::type (s/or :keyword #{:game :stream :music}
                    :int int?))
(s/def ::activity (s/keys :req-un [::name ::type]
                          :opt-un [::url]))

(s/def ::idle-since number?)
(s/def ::status #{:online :offline :invisible :idle :dnd})
(s/def ::afk boolean?)

(s/def ::mute boolean?)
(s/def ::deaf boolean?)
