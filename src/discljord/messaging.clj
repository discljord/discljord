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

(defn delete-own-reaction!
  [])

(defn delete-user-reaction!
  [])

(defn get-reactions!
  [])

(defn delete-all-reactions!
  [])

(defn edit-message!
  [])

(defn delete-message!
  [])

(defn bulk-delete-messages!
  [])

(defn edit-channel-permissions!
  [])

(defn get-channel-invites!
  [])

(defn create-channel-invite!
  [])

(defn delete-channel-permission!
  [])

(defn trigger-typing-indicator!
  [])

(defn get-pinned-messages!
  [])

(defn add-channel-pinned-message!
  [])

(defn delete-pinned-channel-message!
  [])

(defn group-dm-add-recipient!
  [])

(defn group-dm-remove-recipient!
  [])

;; --------------------------------------------------
;; Emoji

(defn list-guild-emojis!
  [])

(defn get-guild-emoji!
  [])

(defn create-guild-emoji!
  [])

(defn modify-guild-emoji!
  [])

(defn delete-guild-emoji!
  [])

;; --------------------------------------------------
;; Guild

(defn create-guild!
  [])

(defn get-guild!
  [])

(defn modify-guild!
  [])

(defn delete-guild!
  [])

(defn get-guild-channels!
  [])

(defn create-guild-channel!
  [])

(defn modify-guild-channel-positions!
  [])

(defn get-guild-member!
  [])

(defn list-guild-members!
  [])

(defn add-guild-member!
  [])

(defn modify-guild-member!
  [])

(defn modify-current-user-nick!
  [])

(defn add-guild-member-role!
  [])

(defn remove-guild-member-role!
  [])

(defn remove-guild-member!
  [])

(defn get-guild-bans!
  [])

(defn get-guild-ban!
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

(defn remove-guild-ban!
  [])

(defendpoint get-guild-roles! ::ds/guild-id
  ""
  []
  [])
(s/fdef get-guild-roles!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :keyword-args (s/keys* :opt-un [::ms/user-agent])))

(defn create-guild-role!
  [])

(defn modify-guild-role-positions!
  [])

(defn modifiy-guild-role!
  [])

(defn delete-guild-role!
  [])

(defn get-guild-prune-count!
  [])

(defn begin-guild-prune!
  [])

(defn get-guild-voice-regions!
  [])

(defn get-guild-invites!
  [])

(defn get-guild-integrations!
  [])

(defn create-guild-integration!
  [])

(defn modify-guild-integration!
  [])

(defn delete-guild-integration!
  [])

(defn sync-guild-integration!
  [])

(defn get-guild-embed!
  [])

(defn modify-guild-embed!
  [])

(defn get-guild-vanity-url!
  [])

;; --------------------------------------------------
;; Invite

(defn get-invite!
  [])

(defn delete-invite!
  [])

;; --------------------------------------------------
;; User

(defn get-current-user!
  [])

(defn get-user!
  [])

(defn modify-current-user!
  [])

(defn get-current-user-guilds!
  [])

(defn leave-guild!
  [])

(defn get-user-dms!
  [])

(defendpoint create-dm! nil
  ""
  [user-id]
  [])
(s/fdef create-dm!
  :args (s/cat :conn ::ds/channel
               :user-id ::ds/user-id
               :keyword-args (s/keys* :opt-un [::ms/user-agent])))

(defn create-group-dm!
  [])

(defn get-user-connections!
  [])

;; --------------------------------------------------
;; Voice

(defn list-voice-regions!
  [])

;; --------------------------------------------------
;; Webhook

(defn create-webhook!
  [])

(defn get-channel-webhooks!
  [])

(defn get-guild-webhooks!
  [])

(defn get-webhook!
  [])

(defn get-webhook-with-token!
  [])

(defn modify-webhook!
  [])

(defn modify-webhook-with-token!
  [])

(defn delete-webhook!
  [])

(defn delete-webhook-with-token!
  [])

(defn execute-webhook!
  [])

(defn execute-slack-compatible-webhook!
  [])

(defn execute-github-compatible-webhook!
  [])
