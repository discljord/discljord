(ns discljord.messaging.specs
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(s/def ::major-variable-type #{::ds/guild-id ::ds/channel-id ::ds/webhook-id})
(s/def ::major-variable-value ::ds/snowflake)
(s/def ::major-variable (s/keys :req [::major-variable-type
                                      ::major-variable-value]))

(s/def ::action keyword?)

(s/def ::endpoint (s/keys :req [::action]
                          :opt [::major-variable]))

(s/def ::rate (s/nilable number?))
(s/def ::remaining (s/nilable number?))
(s/def ::reset (s/nilable number?))
(s/def ::global (s/nilable boolean?))
(s/def ::rate-limit (s/keys :req [::rate ::remaining ::reset]
                            :opt [::global]))

(s/def ::endpoint-specific-rate-limits (s/map-of ::endpoint ::rate-limit))
(s/def ::global-rate-limit ::rate-limit)

(s/def ::rate-limits (s/keys :req [::endpoint-specific-rate-limits]
                             :opt [::global-rate-limit]))

(s/def ::process (s/keys :req [::rate-limits
                               ::ds/channel
                               ::ds/token]))

;; -----------------------------------------------------------------------
;; Argument specs

(s/def ::user-agent string?)

(s/def ::name (s/and string?
                     #(>= (count %) 2)
                     #(<= (count %) 100)))
(s/def ::position integer?)
(s/def ::topic (s/and string?
                      #(>= (count %) 0)
                      #(<= (count %) 1024)))
(s/def ::nsfw boolean?)
(s/def ::rate-limit-per-user (s/and integer?
                                    #(>= % 0)
                                    #(<= % 120)))
(s/def ::bitrate (s/and pos-int?
                        #(>= % 8000)
                        #(<= % 128000)))
(s/def ::user-limit (s/and integer?
                           #(>= % 0)
                           #(<= % 99)))
(s/def :overwrite/type #{"role" "member"})
(s/def ::overwrite-object (s/keys :req-un [::ds/id :overwrite/type ::allow ::deny]))
(s/def ::permission-overwrites (s/coll-of ::overwrite-object))
(s/def ::parent-id ::ds/snowflake)

(s/def ::around ::ds/snowflake)
(s/def ::before ::ds/snowflake)
(s/def ::after ::ds/snowflake)
(s/def ::limit integer?)

(s/def ::message-id ::ds/snowflake)

(s/def ::message (s/and string?
                        #(< (count %) 2000)))
(s/def ::tts boolean?)
(s/def ::nonce ::ds/snowflake)
(s/def ::file (partial instance? java.io.File))

(s/def :embed/title string?)
(s/def :embed/type string?)
(s/def :embed/description string?)
(s/def :embed/url string?)
(s/def :embed/timestamp string?)
(s/def :embed/color integer?)
(s/def :embed/footer (s/keys :opt-un [:embed/icon_url :embed/proxy_icon_url]))
(s/def :embed/image (s/keys :opt-un [:embed/url :embed/proxy_url
                                     :embed/height :embed/width]))

(s/def :embed/height integer?)
(s/def :embed/width integer?)
(s/def :embed/proxy_url string?)
(s/def :embed/thumbnail (s/keys :opt-un [:embed/url :embed/proxy_url
                                         :embed/height :embed/width]))

(s/def :embed/video (s/keys :opt-un [:embed/height :embed/width :embed/url]))
(s/def :embed/name string?)
(s/def :embed/provider (s/keys :opt-un [:embed/name :embed/url]))
(s/def :embed/icon_url string?)
(s/def :embed/proxy_icon_url string?)
(s/def :embed/author (s/keys :opt-un [:embed/name :embed/url
                                      :embed/icon_url
                                      :embed/proxy_icon_url]))
(s/def :embed.field/value any?)
(s/def :embed.field/inline boolean?)
(s/def :embed/fields (s/coll-of (s/keys :req-un [:embed/name :embed.field/value]
                                        :opt-un [:embed.field/inline])))

(s/def ::embed (s/keys :opt-un [:embed/title :embed/type :embed/description :embed/url :embed/timestamp
                                :embed/color :embed/footer :embed/image :embed/thumbnail :embed/video
                                :embed/provider :embed/author :embed/fields]))

(s/def ::emoji string?)

(s/def ::user-id ::ds/user-id)

(s/def ::content ::message)

(s/def ::messages (s/coll-of ::message-id))

(s/def ::overwrite-id ::ds/snowflake)

(s/def ::max-age integer?)
(s/def ::max-uses integer?)
(s/def ::temporary boolean?)
(s/def ::unique boolean?)

(s/def ::access-token any?)

(s/def ::nick string?)

(s/def ::image any?)
(s/def ::roles (s/coll-of ::ds/snowflake))

(s/def ::region string?)
(s/def ::icon string?)
(s/def ::verification-level integer?)
(s/def ::default-message-notifications integer?)
(s/def ::explicit-content-filter integer?)
(s/def :role/name string?)
(s/def :role/color integer?)
(s/def :role/hoist boolean?)
(s/def :role/managed boolean?)
(s/def :role/mentionable boolean?)
(s/def ::role (s/keys :req-un [::ds/id :role/name :role/color :role/hoist
                               ::position ::permissions :role/managed :role/mentionable]))
(s/def ::role-objects (s/coll-of ::role))

(s/def ::afk-channel-id ::ds/channel-id)
(s/def ::afk-timeout integer?)
(s/def ::owner-id ::ds/user-id)
(s/def ::splash string?)
(s/def ::system-channel-id ::ds/channel-id)

(s/def :position.modify/channel (s/keys :req-un [::ds/id ::position]))
(s/def ::channels (s/coll-of :position.modify/channel))

(s/def ::mute boolean?)
(s/def ::deaf boolean?)

(s/def ::channel-id ::ds/channel-id)
(s/def ::role-id ::ds/snowflake)

(s/def ::delete-message-days (s/and int?
                                    (complement neg?)
                                    #(< % 8)))

(s/def ::reason string?)

(s/def ::days integer?)
(s/def ::compute-prune-count boolean?)

(s/def ::type string?)
(s/def ::id ::ds/snowflake)

(s/def ::integration-id ::ds/snowflake)
(s/def ::expire-behavior integer?)
(s/def ::expire-grace-period integer?)
(s/def ::enable-emoticons boolean?)

(s/def ::style #{"shield" "banner1" "banner2" "banner3" "banner4"})

(s/def ::invite-code string?)
(s/def ::with-counts? boolean?)

(s/def ::username string?)
(s/def ::avatar string?)

(s/def ::access-tokens (s/coll-of ::access-token))
(s/def ::nicks (s/coll-of ::nick))

(s/def ::webhook-token string?)

(s/def ::embeds (s/coll-of ::embed))
