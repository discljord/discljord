(ns discljord.messaging.specs
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(defn- string-spec
  ([min-length max-length]
   (s/and string? #(<= min-length (count %) max-length)))
  ([pattern]
   (s/and string? (partial re-matches pattern))))

(s/def ::major-variable-type #{::ds/guild-id ::ds/channel-id ::ds/webhook-id ::ds/application-id})
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
(s/def ::rate-limit (s/keys :req [::reset]
                            :opt [::rate ::remaining ::global]))
(s/def ::rate-limit-family (s/map-of ::major-variable ::rate-limit))

(s/def ::endpoint-agents (s/map-of ::endpoint (ds/agent-of? string?)))

(s/def ::rate-limits (ds/atom-of? (s/map-of string? ::rate-limit-family)))

(s/def ::global-limit (ds/atom-of? (s/nilable number?)))

(s/def ::process (s/keys :req [::ds/channel
                               ::ds/token
                               ::rate-limits
                               ::endpoint-agents
                               ::global-limit]))

;; -----------------------------------------------------------------------
;; Argument specs

(s/def ::user-agent string?)

(def channel-types
  {:guild-text 0
   :dm 1
   :guild-voice 2
   :group-dm 3
   :guild-category 4
   :guild-news 5
   :guild-store 6
   :guild-stage-voice 13})

(s/def ::name (string-spec 2 100))
(s/def :discljord.messaging.specs.channel/type (set (vals channel-types)))
(s/def ::position integer?)
(s/def ::topic (string-spec 0 1024))
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

; Threads
(s/def :discljord.messaging.specs.thread/auto-archive-duration #{60 1440 4320 10080})
; This should validate an ISO 8601 timestamp - use a regex instead?
(s/def :discljord.messaging.specs.thread/before string?)

(s/def :discljord.messaging.specs.overwrite/type #{"role" "member"})
(s/def ::overwrite-object (s/keys :req-un [::ds/id :discljord.messaging.specs.overwrite/type ::allow ::deny]))
(s/def ::permission-overwrites (s/coll-of ::overwrite-object))
(s/def ::parent-id ::ds/snowflake)

(s/def ::around ::ds/snowflake)
(s/def ::before ::ds/snowflake)
(s/def ::after ::ds/snowflake)
(s/def ::limit integer?)

(s/def ::message-id ::ds/snowflake)

(s/def ::message (string-spec 0 1999))
(s/def ::tts boolean?)
(s/def ::nonce ::ds/snowflake)
(s/def ::file (partial instance? java.io.File))

(s/def :stream/content (partial instance? java.io.InputStream))
(s/def :stream/filename string?)
(s/def ::stream (s/keys :req-un [:stream/content :stream/filename]))

(s/def :embed/title string?)
(s/def :embed/type #{"rich" "image" "video" "gifv" "article" "link"})
(s/def :embed/description string?)
(s/def :embed/url string?)
(s/def :embed/timestamp string?)
(s/def :embed/color integer?)
(s/def :embed.footer/text string?)
(s/def :embed/footer (s/keys :req-un [:embed.footer/text]
                             :opt-un [:embed/icon_url :embed/proxy_icon_url]))
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
(s/def :embed.field/value string?)
(s/def :embed.field/inline boolean?)
(s/def :embed/fields (s/coll-of (s/keys :req-un [:embed/name :embed.field/value]
                                        :opt-un [:embed.field/inline])))

(s/def ::embed (s/keys :opt-un [:embed/title :embed/type :embed/description :embed/url :embed/timestamp
                                :embed/color :embed/footer :embed/image :embed/thumbnail :embed/video
                                :embed/provider :embed/author :embed/fields]))

(s/def ::emoji string?)

(s/def ::user-id ::ds/user-id)

(s/def ::content ::message)

(def component-types {:action-row 1, :button 2})

(s/def :component/type (set (vals component-types)))

(def component-styles
  {:primary 1
   :secondary 2
   :success 3
   :danger 4
   :link 5})

(s/def :component/style (set (vals component-styles)))
(s/def :component/label (string-spec 0 80))

(s/def :component.emoji/name string?)
(s/def :component.emoji/id ::ds/snowflake)
(s/def :component.emoji/animated boolean?)

(s/def :component/emoji
  (s/keys :req-un [:component.emoji/id :component.emoji/animated :component.emoji/name]))
(s/def :component/custom_id (string-spec 0 100))
(s/def :component/url string?)
(s/def :component/disabled boolean?)

(s/def ::component
  (s/keys :req-un [:component/type]
          :opt-un [:component/style :component/label :component/emoji
                   :component/custom_id :component/url :component/disabled ::components]))

(s/def ::components
  (s/coll-of ::component))

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

(s/def :discljord.messaging.specs.integration/type string?)
(s/def :discljord.messaging.specs.integration/id ::ds/snowflake)

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

(s/def ::wait boolean?)

(s/def ::embeds (s/coll-of ::embed))

(def allowed-mention-types #{:roles :users :everyone})
(s/def :allowed-mentions/parse (s/coll-of allowed-mention-types :kind vector?))
(s/def :allowed-mentions/users (s/coll-of ::user-id :kind vector?))
(s/def :allowed-mentions/roles (s/coll-of ::role-id :kind vector?))
(s/def ::allowed-mentions (s/or :parse (s/keys :req-un [:allowed-mentions/parse])
                                :manual (s/keys :opt-un [:allowed-mentions/users
                                                         :allowed-mentions/roles])))

(s/def :message-reference/message_id ::ds/snowflake)
(s/def :message-reference/channel_id ::ds/snowflake)
(s/def :message-reference/guild_id ::ds/snowflake)
(s/def ::message-reference (s/keys :req-un [:message-reference/message_id
                                            :message-reference/channel_id
                                            :message-reference/guild_id]))

(s/def ::application-id ::ds/application-id)

(def command-option-types
  {:sub-command 1
   :sub-command-group 2
   :string 3
   :integer 4
   :boolean 5
   :user 6
   :channel 7
   :role 8})

(s/def :command.option/type (set (vals command-option-types)))

(s/def :command.option/name (string-spec #"\S{1,32}"))
(s/def :command.option/description (string-spec 1 100))
(s/def :command.option/default boolean?)
(s/def :command.option/required boolean?)

(s/def :command.option.choice/name (string-spec 1 100))

(s/def :command.option.choice/value
  (s/or :string string? :int int?))

(s/def :command.option/choice (s/keys :req-un [:command.option.choice/name
                                               :command.option.choice/value]))

(s/def :command.option/choices (s/coll-of :command.option/choice))

(s/def :command/option (s/and (s/keys :req-un [:command.option/type
                                               :command.option/name
                                               :command.option/description]
                                      :opt-un [:command.option/default
                                               :command.option/required
                                               :command.option/choices
                                               :command.option/options])
                              #(<= (count (:choices %)) 25)
                              #(not-any? #{(command-option-types :sub-command-group)} (map :type (:options %)))
                              #(or (= (command-option-types :sub-command-group) (:type %))
                                   (not-any? #{(command-option-types :sub-command)} (map :type (:options %))))))

(def command-permission-types
  {:role 1
   :user 2})

(s/def :command.permission/id ::ds/snowflake)

(s/def :command.permission/type (set (vals command-permission-types)))

(s/def :command.permission/permission boolean?)

(s/def :command/permission (s/keys :req-un [:command.permission/id
                                            :command.permission/type
                                            :command.permission/permission]))

(s/def :discljord.messaging.specs.command/permissions (s/coll-of :command/permission))

(s/def ::command-id ::ds/snowflake)
(s/def :command/id ::command-id)

(s/def :discljord.messaging.specs.command.guild/permissions
  (s/keys :req-un [:command/id
                   :discljord.messaging.specs.command/permissions]))

(s/def :discljord.messaging.specs.command.guild/permissions-array
  (s/coll-of :discljord.messaging.specs.command.guild/permissions))

(s/def :discljord.messaging.specs.command/options
  (s/and (s/coll-of :command/option)
         (comp (partial >= 25) count)
         (fn [[{first-required? :required} :as opts]]
           (let [option-segments (partition-by :required opts)
                 amount (count option-segments)]
             (or (<= amount 1)
                 (and (= amount 2) first-required?))))))

(s/def :discljord.messaging.specs.command/default-permission boolean?)

(s/def :command.option/options :discljord.messaging.specs.command/options)

(s/def :discljord.messaging.specs.command/name (string-spec #"\S{1,32}"))

(s/def :discljord.messaging.specs.command/description (string-spec 1 100))

(s/def ::command
  (s/and (s/keys :req-un [:discljord.messaging.specs.command/name
                          :discljord.messaging.specs.command/description]
                 :opt-un [:discljord.messaging.specs.command/options
                          :discljord.messaging.specs.command/default-permission])
         (fn [cmd]
           (<= (->> cmd
                    (tree-seq :options :options)
                    (map (juxt :name :description (comp (partial map (juxt :name :value)) :choices)))
                    flatten
                    (filter string?)
                    (map count)
                    (reduce +))
               4000))))

(s/def ::commands (s/and (s/coll-of ::command) #(<= (count %) 100)))

(s/def ::interaction-id ::ds/snowflake)
(s/def ::interaction-token string?)

(def interaction-response-types
  {:pong 1
   :acknowledge 2
   :channel-message 3
   :channel-message-with-source 4
   :deferred-channel-message-with-source 5})

(s/def :discljord.messaging.specs.interaction-response/type
  (set (vals interaction-response-types)))

(s/def :interaction-response.data/flags int?)

(s/def :discljord.messaging.specs.interaction-response/data
  (s/keys :req-un [::content]
          :opt-un [::embeds
                   ::tts
                   ::allowed-mentions
                   ::components
                   :interaction-response.data/flags]))

(s/def :widget/enabled boolean?)
(s/def :widget/channel_id ::ds/snowflake)
(s/def ::widget (s/keys :req-un [:widget/enabled :widget/channel_id]))
