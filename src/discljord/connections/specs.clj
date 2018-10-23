(ns discljord.connections.specs
  "Contains all the specs required for the `discljord.connections` namespace."
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

;; ---------------------------------------------------
(s/def ::shard-id int?)
(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::ds/url ::shard-count]))

(s/def ::session-id (s/nilable string?))
(s/def ::seq (s/nilable int?))
(s/def ::buffer-size number?)
(s/def ::disconnect boolean?)
(s/def ::max-connection-retries number?)
(s/def ::shard-state (s/keys :req-un [::session-id ::seq
                                      ::buffer-size ::disconnect
                                      ::max-connection-retries]))
(s/def ::init-shard-state ::shard-state)

(s/def ::connection any?)

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
