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

(s/def ::gateway-intent #{:guilds :guild-members :guild-bans :guild-emojis
                          :guild-integrations :guild-webhooks :guild-invites
                          :guild-voice-states :guild-presences :guild-messages
                          :guild-message-reactions :guild-message-typing
                          :direct-messages :direct-message-reactions
                          :direct-message-typing})
(s/def ::intents (s/coll-of ::gateway-intent :kind set?))

(s/def ::url string?)
(s/def ::shard-count pos-int?)
(s/def ::remaining nat-int?)
(s/def ::reset-after pos-int?)
(s/def ::session-start-limit (s/keys :req-un [::remaining ::reset-after]))
(s/def ::gateway (s/keys :req-un [::url ::shard-count ::session-start-limit]))

(s/def ::identify-when ifn?)
(s/def ::disable-compression boolean?)

(s/def :shard/id nat-int?)
(s/def :shard/session string?)
(s/def :shard/count pos-int?)
(s/def :shard/seq nat-int?)
(s/def ::shard (s/keys :req-un [:shard/id :shard/session :shard/count :shard/seq]))
