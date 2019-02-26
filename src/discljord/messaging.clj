(ns discljord.messaging
  "Contains functions for communicating with Discord, sending messages, and recieving data."
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [discljord.http :refer [api-url]]
   [discljord.messaging.impl :as impl]
   [discljord.messaging.specs :as ms]
   [discljord.specs :as ds]))

(defn start-connection!
  "Takes a token for a bot, and returns a channel which is passed
  to the various messaging functions."
  [token]
  (impl/start! token))
(s/fdef start-connection!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop-connection!
  "Takes a channel returned by start-connection! and stops the associated
  connection."
  [conn]
  (impl/stop! conn))
(s/fdef stop-connection!
  :args (s/cat :conn ::ds/channel))

(defmacro defendpoint
  "Creates a new non-blocking function for a discord endpoint. `endpoint-name` must end with an '!'"
  [endpoint-name major-var-type doc-str params opts]
  (let [major-var (when major-var-type
                    (symbol (name major-var-type)))
        sym-name (name endpoint-name)
        action (keyword (subs sym-name 0 (dec (count sym-name))))
        opts (conj opts 'user-agent)]
    `(defn ~endpoint-name
       ~doc-str
       [~'conn ~@(when major-var-type [major-var]) ~@params ~'& {:keys ~opts :as ~'opts}]
       (let [user-agent# (:user-agent ~'opts)
             p# (promise)
             action# {::ms/action ~action}]
         (a/put! ~'conn (into [(if ~major-var-type
                                 (assoc action#
                                        ::ms/major-variable {::ms/major-variable-type ~major-var-type
                                                             ::ms/major-variable-value ~major-var})
                                 action#)
                               p#
                               ~@params
                               :user-agent user-agent#]
                              cat
                              (dissoc ~'opts :user-agent)))
         p#))))

;; --------------------------------------------------
;; Audit Log

(defendpoint get-guild-audit-log! ::ds/guild-id
  ""
  []
  [])

;; --------------------------------------------------
;; Channel

(defendpoint get-channel! ::ds/channel-id
  ""
  []
  [])

(defendpoint modify-channel! ::ds/channel-id
  ""
  []
  [name position topic nsfw rate-limit-per-user bitrate
   user-limit permission-overwrites parent-id])

(defendpoint delete-channel! ::ds/channel-id
  ""
  []
  [user-agent])

(defendpoint get-channel-messages! ::ds/channel-id
  ""
  []
  [around before after limit])

(defendpoint get-channel-message! ::ds/channel-id
  ""
  [message-id]
  [])

(defendpoint create-message! ::ds/channel-id
  "Takes a core.async channel returned by start-connection!, a Discord
  channel id as a string, and the message you want to send to Discord.

  Keyword Arguments:
  :user-agent changes the User-Agent header sent to Discord.
  :tts is a boolean, defaulting to false, which tells Discord to read
       your message out loud."
  [msg]
  [tts])
(s/fdef create-message!
  :args (s/cat :conn ::ds/channel
               :channel-id ::ds/channel-id
               :msg ::ms/message
               :keyword-args (s/keys* :opt-un [::ms/user-agent
                                               ::ms/tts]))
  :ret ::ds/promise)

(def ^:depricated send-message! create-message!)

(defendpoint create-reaction! ::ds/channel-id
  ""
  [message-id emoji]
  [])

(defendpoint delete-own-reaction! ::ds/channel-id
  ""
  [message-id emoji]
  [])

(defendpoint delete-user-reaction! ::ds/channel-id
  ""
  [message-id emoji user-id]
  [])

(defendpoint get-reactions! ::ds/channel-id
  ""
  [message-id emoji]
  [before after limit])

(defendpoint delete-all-reactions! ::ds/channel-id
  ""
  [message-id]
  [])

(defendpoint edit-message! ::ds/channel-id
  ""
  [message-id]
  [content embed])

(defendpoint delete-message! ::ds/channel-id
  ""
  [message-id]
  [])

(defendpoint bulk-delete-messages! ::ds/channel-id
  ""
  [messages]
  [])

(defendpoint edit-channel-permissions! ::ds/channel-id
  ""
  [overwrite-id allow deny type]
  [])

(defendpoint get-channel-invites! ::ds/channel-id
  ""
  []
  [])

(defendpoint create-channel-invite! ::ds/channel-id
  ""
  []
  [max-age max-uses temporary unique])

(defendpoint delete-channel-permission! ::ds/channel-id
  ""
  [overwrite-id]
  [])

(defendpoint trigger-typing-indicator! ::ds/channel-id
  ""
  []
  [])

(defendpoint get-pinned-messages! ::ds/channel-id
  ""
  []
  [])

(defendpoint add-channel-pinned-message! ::ds/channel-id
  ""
  [message-id]
  [])

(defendpoint delete-pinned-channel-message! ::ds/channel-id
  ""
  [message-id]
  [])

(defendpoint group-dm-add-recipient! ::ds/channel-id
  "NOT INTENDED FOR BOT USE"
  [user-id]
  [access-token nick])

(defendpoint group-dm-remove-recipient! ::ds/channel-id
  "NOT INTENDED FOR BOT USE"
  [user-id]
  [])

;; --------------------------------------------------
;; Emoji

(defendpoint list-guild-emojis! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-emoji! ::ds/guild-id
  ""
  [emoji-id]
  [])

(defendpoint create-guild-emoji! ::ds/guild-id
  ""
  [name image roles]
  [])

(defendpoint modify-guild-emoji! ::ds/guild-id
  ""
  [emoji-id name roles]
  [])

(defendpoint delete-guild-emoji! ::ds/guild-id
  ""
  [emoji-id]
  [])

;; --------------------------------------------------
;; Guild

(defendpoint create-guild! nil
  ""
  []
  [])

(defendpoint get-guild! ::ds/guild-id
  ""
  []
  [])

(defendpoint modify-guild! ::ds/guild-id
  ""
  []
  [name region verification-level default-message-notifications
   explicit-content-filter afk-channel-id afk-timeout icon
   owner-id splash system-channel-id])

(defendpoint delete-guild! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-channels! ::ds/guild-id
  ""
  []
  [])

(defendpoint create-guild-channel! ::ds/guild-id
  ""
  [name]
  [type topic bitrate user-limit rate-limit-per-user
   position permission-overwrites parent-id nsfw])

(defendpoint modify-guild-channel-positions! ::ds/guild-id
  ""
  [channels]
  [])

(defendpoint get-guild-member! ::ds/guild-id
  ""
  [user-id]
  [])

(defendpoint list-guild-members! ::ds/guild-id
  ""
  []
  [limit after])

(defendpoint add-guild-member! ::ds/guild-id
  ""
  [user-id access-token]
  [nick roles mute deaf])

(defendpoint modify-guild-member! ::ds/guild-id
  ""
  [user-id]
  [nick roles mute deaf channel-id])

(defendpoint modify-current-user-nick! ::ds/guild-id
  ""
  [nick]
  [])

(defendpoint add-guild-member-role! ::ds/guild-id
  ""
  [user-id role-id]
  [])

(defendpoint remove-guild-member-role! ::ds/guild-id
  ""
  [user-id role-id]
  [])

(defendpoint remove-guild-member! ::ds/guild-id
  ""
  [user-id]
  [])

(defendpoint get-guild-bans! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-ban! ::ds/guild-id
  ""
  [user-id]
  [])

(defendpoint create-guild-ban! ::ds/guild-id
  ""
  [user-id]
  [delete-message-days reason user-agent])
(s/fdef create-guild-ban!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :user-id ::ds/user-id
               :keyword-args (s/keys* :opt-un [::ms/delete-message-days
                                               ::ms/reason
                                               ::ms/user-agent])))

(defendpoint remove-guild-ban! ::ds/guild-id
  ""
  [user-id]
  [])

(defendpoint get-guild-roles! ::ds/guild-id
  ""
  []
  [])
(s/fdef get-guild-roles!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :keyword-args (s/keys* :opt-un [::ms/user-agent])))

(defendpoint create-guild-role! ::ds/guild-id
  ""
  []
  [name permissions color hoist mentionable])

(defendpoint modify-guild-role-positions! ::ds/guild-id
  ""
  [roles]
  [])

(defendpoint modifiy-guild-role! ::ds/guild-id
  ""
  [role-id]
  [name permissions color hoist mentionable])

(defendpoint delete-guild-role! ::ds/guild-id
  ""
  [role-id]
  [])

(defendpoint get-guild-prune-count! ::ds/guild-id
  ""
  []
  [days])

(defendpoint begin-guild-prune! ::ds/guild-id
  ""
  [days compute-prune-count]
  [])

(defendpoint get-guild-voice-regions! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-invites! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-integrations! ::ds/guild-id
  ""
  []
  [])

(defendpoint create-guild-integration! ::ds/guild-id
  ""
  [type id]
  [])

(defendpoint modify-guild-integration! ::ds/guild-id
  ""
  [integration-id expire-behavior expire-grace-period enable-emoticons]
  [])

(defendpoint delete-guild-integration! ::ds/guild-id
  ""
  [integration-id]
  [])

(defendpoint sync-guild-integration! ::ds/guild-id
  ""
  [integration-id]
  [])

(defendpoint get-guild-embed! ::ds/guild-id
  ""
  []
  [])

(defendpoint modify-guild-embed! ::ds/guild-id
  ""
  [embed]
  [])

(defendpoint get-guild-vanity-url! ::ds/guild-id
  ""
  []
  [])

(defendpoint get-guild-widget-image! ::ds/guild-id
  ""
  []
  [style shield banner1 banner2 banner3 banner4])

;; --------------------------------------------------
;; Invite

(defendpoint get-invite! nil
  ""
  [invite-code]
  [with-counts?])

(defendpoint delete-invite! nil
  ""
  [invite-code]
  [])

;; --------------------------------------------------
;; User

(defendpoint get-current-user! nil
  ""
  []
  [])

(defendpoint get-user! nil
  ""
  [user-id]
  [])

(defendpoint modify-current-user! nil
  ""
  []
  [username avatar])

(defendpoint get-current-user-guilds! nil
  ""
  []
  [before after limit])

(defendpoint leave-guild! nil
  ""
  [guild-id]
  [])

(defendpoint get-user-dms! nil
  ""
  []
  [])

(defendpoint create-dm! nil
  ""
  [user-id]
  [])
(s/fdef create-dm!
  :args (s/cat :conn ::ds/channel
               :user-id ::ds/user-id
               :keyword-args (s/keys* :opt-un [::ms/user-agent])))

(defendpoint create-group-dm! nil
  ""
  [access-tokens nicks]
  [])

(defendpoint get-user-connections! nil
  ""
  []
  [])

;; --------------------------------------------------
;; Voice

(defendpoint list-voice-regions! nil
  ""
  []
  [])

;; --------------------------------------------------
;; Webhook

(defendpoint create-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint get-channel-webhooks! ::ds/webhook-id
  ""
  []
  [])

(defendpoint get-guild-webhooks! ::ds/webhook-id
  ""
  []
  [])

(defendpoint get-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint get-webhook-with-token! ::ds/webhook-id
  ""
  []
  [])

(defendpoint modify-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint modify-webhook-with-token! ::ds/webhook-id
  ""
  []
  [])

(defendpoint delete-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint delete-webhook-with-token! ::ds/webhook-id
  ""
  []
  [])

(defendpoint execute-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint execute-slack-compatible-webhook! ::ds/webhook-id
  ""
  []
  [])

(defendpoint execute-github-compatible-webhook! ::ds/webhook-id
  ""
  []
  [])
