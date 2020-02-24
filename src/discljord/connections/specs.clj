(ns discljord.connections.specs
  "Contains all the specs required for the `discljord.connections` namespace."
  (:require
   [clojure.spec.alpha :as s]
   [discljord.specs :as ds]))

(s/def ::buffer-size number?)

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
