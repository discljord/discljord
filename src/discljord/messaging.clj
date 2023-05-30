(ns discljord.messaging
  "Contains functions for communicating with Discord, sending messages, and recieving data.

  All endpoint-based functions in this namespace return promises of a sort. The
  returned value is not a Clojure promise however, but is an implementation
  of [[IDeref]] which also functions as a `core.async` channel. This means that
  in addition to [[deref]]ing the return values, they may also have a parking
  take performed on them for better concurrency."
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [discljord.http :refer [api-url]]
   [discljord.messaging.impl :as impl]
   [discljord.messaging.specs :as ms]
   [discljord.specs :as ds]
   [discljord.util :as util]))

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

(def ^:const spec-ns "discljord.messaging.specs")

(defn- spec-for [sym]
  (if-let [ns (namespace sym)]
    (keyword (->> (str/split ns #"\.")
                  (map #(or (some-> (ns-aliases *ns*) (get (symbol %)) ns-name) %))
                  (str/join "."))
             (name sym))
    (keyword spec-ns (name sym))))

(defmacro defendpoint
  "Creates a new non-blocking function for a discord endpoint.

  - `endpoint-name`: the name of the endpoint function. must end with an '!'
  - `major-var-type`: the spec-name of the major variable used in this endpoint, if any. If there is none, `nil` should be used.
  - `doc-str`: Documentation for the endpoint function.
  - `params`: Required parameters for this endpoint. If the major variable is the first parameter, it need not be included here.
    The macro will attempt to find a spec for each parameter. If the parameter is an unqualified symbol, `:discljord.messaging.specs/param` will be used,
    otherwise the associated namespace. Aliases that do not contain dots `.` can be used as namespace segments. Some examples for spec resolution:
    - `baz` => `:discljord.messaging.specs/baz`
    - `foo.bar/baz` => `:foo.bar/baz`
    - `foo.bar/baz` and `foo` is an alias for `quz.foo` in the current namespace => `:quz.foo.bar/baz`
    - `foo.bar/baz` and `foo.bar` is an alias for `lorem.ipsum` in the current namespace => `:foo.bar/baz` (aliases with dots in them can't be used)
  - `opts`: Optional parameters for this endpoint. Spec resolution works exactly like for `params`."
  [endpoint-name major-var-type doc-str params opts]
  (let [major-var (when major-var-type
                    (symbol (name major-var-type)))
        sym-name (name endpoint-name)
        action (keyword (subs sym-name 0 (dec (count sym-name))))
        opts (conj opts 'user-agent 'audit-reason 'log-error?)
        prepend-major-var? (and major-var (not (contains? (set params) major-var)))
        spec-args (cond->> (map (juxt (comp keyword name) spec-for) params)
                           prepend-major-var? (cons [(keyword major-var) major-var-type])
                           true vec)
        spec-keys (vec (map spec-for opts))
        unqualified-params (cond->> (map (comp symbol name) params) prepend-major-var? (cons major-var))
        unqualified-opts (mapv (comp symbol name) opts)]
    `(do
       (defn ~endpoint-name
         ~doc-str
         [~'conn ~@unqualified-params ~'& {:keys ~unqualified-opts :as ~'opts :or {~'log-error? true}}]
         (let [p# (util/derefable-promise-chan)
               action# {::ms/action ~action}]
           (a/put! ~'conn (into [(cond-> action#
                                         ~major-var-type (assoc ::ms/major-variable
                                                           {::ms/major-variable-type ~major-var-type
                                                            ::ms/major-variable-value ~major-var}))
                                 p#
                                 ~@(remove #{major-var} unqualified-params)
                                 :log-error? ~'log-error?]
                                cat
                                (dissoc ~'opts :log-error?)))
           p#))
       (s/fdef ~endpoint-name
         :args (s/cat :conn ::ds/channel
                      ~@(mapcat identity spec-args)
                      :keyword-args (s/keys* :opt-un ~spec-keys))
         :ret ::ds/promise))))

;; --------------------------------------------------
;; Audit Log

(defendpoint get-guild-audit-log! ::ds/guild-id
  "Returns a promise containing an audit log object for the guild."
  []
  [])

;; --------------------------------------------------
;; Channel

(defendpoint get-channel! ::ds/channel-id
  "Returns a promise containing a channel object."
  []
  [])

(defendpoint modify-channel! ::ds/channel-id
  "Updates a channel's settings. Returns a promise containing the channel object."
  []
  [name position topic nsfw rate-limit-per-user bitrate
   user-limit permission-overwrites parent-id])

(defendpoint delete-channel! ::ds/channel-id
  "Deletes a channel. Returns a promise containing the deleted channel object."
  []
  [user-agent])

(defendpoint get-channel-messages! ::ds/channel-id
  "Returns a promise containing a vector of message objects."
  []
  [around before after limit])

(defendpoint get-channel-message! ::ds/channel-id
  "Returns a promise containing the message object."
  [message-id]
  [])

(defendpoint create-message! ::ds/channel-id
  "Sends a message on the channel. Returns a promise containing the message object.

  Keyword Arguments:
  :user-agent changes the User-Agent header sent to Discord.
  :tts is a boolean, defaulting to false, which tells Discord to read
       your message out loud.
  :file is a java.io.File object specifying a file for Discord to attach to the message.
  :attachments is a collection of file-like objects to attach to the message.
  :stream is a map that has a :content of a java.io.InputStream and a :filename of the filename to attach to the message.
  :embeds is a map specifying the embed format for the message (See Discord API)"
  []
  [content tts nonce embeds file allowed-mentions attachments stream message-reference components])

(defn ^:deprecated send-message!
  [conn channel-id msg & {:keys [tts none embed file] :as opts}]
  (apply create-message! conn channel-id :content msg
         (into [] cat (assoc (dissoc opts :embed) :embeds [(:embed opts)]))))

(defendpoint create-reaction! ::ds/channel-id
  "Creates a new reaction on the message with the given emoji (either unicode or \"name:id\" for a custom emoji). Returns a promise containing a boolean, telling you if it succeeded."
  [message-id emoji]
  [])

(defendpoint delete-own-reaction! ::ds/channel-id
  "Deletes your reaction on the messasge with the given emoji (either unicode or \"name:id\" for a custom emoji). Returns a promise containing a boolean, telling you if it succeeded."
  [message-id emoji]
  [])

(defendpoint delete-user-reaction! ::ds/channel-id
  "Deletes a given user's reaction to a message with the given emoji (either unicode or \"name:id\" for a custom emoji). Returns a promise containing a boolean, telling you if it succeeded."
  [message-id emoji user-id]
  [])

(defendpoint get-reactions! ::ds/channel-id
  "Returns a promise containing a list of all users who reacted to the message with the emoji (either unicode or \"name:id\" for a custom emoji), based on the provided limits."
  [message-id emoji]
  [before after limit])

(defendpoint delete-all-reactions! ::ds/channel-id
  "Deletes all reactions on a message. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint delete-all-reactions-for-emoji! ::ds/channel-id
  "Deletes all reactions of a particular emoji on a message. Returns a promise containing a boolean of if it succeeded."
  [message-id emoji]
  [])

(defendpoint edit-message! ::ds/channel-id
  "Edits the given message with the new content or embed. Returns a promise containing the new message."
  [message-id]
  [content embed components])

(defendpoint delete-message! ::ds/channel-id
  "Deletes the given message. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint bulk-delete-messages! ::ds/channel-id
  "Deletes all the messages whose id's are in the passed vector. Returns a promise containing a boolean of if it succeeded."
  [messages]
  [])

(defendpoint edit-channel-permissions! ::ds/channel-id
  "Edits the channel's permissions of either a user or role. Returns a promise containing a boolean of if it succeeded."
  [overwrite-id allow deny ms.overwrite/type]
  [])

(defendpoint get-channel-invites! ::ds/channel-id
  "Returns a promise containing a list of invite objects with invite metadata."
  []
  [])

(defendpoint create-channel-invite! ::ds/channel-id
  "Returns a promise containing a new invite object."
  []
  [max-age max-uses temporary unique])

(defendpoint delete-channel-permission! ::ds/channel-id
  "Deletes a permission override in a channel. Returns a promise containing a boolean of if it succeeded."
  [overwrite-id]
  [])

(defendpoint trigger-typing-indicator! ::ds/channel-id
  "Triggers the typing indicator in the given channel (not recommended for bots unless about to send a message which takes a while to compute). Returns a promise containing a boolean of if it succeeded."
  []
  [])

(defendpoint get-pinned-messages! ::ds/channel-id
  "Returns a promise containing a list of message objects."
  []
  [])

(defendpoint ^:deprecated add-channel-pinned-message! ::ds/channel-id
  "Pins the given message to the channel. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint ^:deprecated delete-pinned-channel-message! ::ds/channel-id
  "Removes a message from the pinned list in the channel. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint pin-message! ::ds/channel-id
  "Pins the given message to the channel. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint unpin-message! ::ds/channel-id
  "Removes a message from the pinned list in the channel. Returns a promise containing a boolean of if it succeeded."
  [message-id]
  [])

(defendpoint group-dm-add-recipient! ::ds/channel-id
  "NOT INTENDED FOR BOT USE. Adds a new recipient to a group DM channel. Requires an access token."
  [user-id]
  [access-token nick])

(defendpoint group-dm-remove-recipient! ::ds/channel-id
  "NOT INTENDED FOR BOT USE. Removes a recipient from a group DM channel. Requires an access token."
  [user-id]
  [])

; Threads

(defendpoint start-thread-with-message! ::ds/channel-id
  "Creates a new thread from an existing message.

  Returns a promise containing a channel object."
  [message-id name ms.thread/auto-archive-duration]
  [])

(defendpoint start-thread-without-message! ::ds/channel-id
  "Creates a new thread that is not connected to an existing message (private thread).

  Returns a promise containing a channel object."
  [name ms.thread/auto-archive-duration ms.channel/type]
  [])

(defendpoint join-thread! ::ds/channel-id
  "Adds the current user to a thread.

  Requires the thread is not archived.
  Returns a promise containing a boolean of if it succeeded."
  []
  [])

(defendpoint add-thread-member! ::ds/channel-id
  "Adds a member to a thread.

  Requires the thread is not archived and the ability to send messages in the thread.
  Returns a promise containing a boolean of if it succeeded."
  [user-id]
  [])

(defendpoint leave-thread! ::ds/channel-id
  "Removes the current user from a thread.

  Returns a promise containing a boolean of if it succeeded."
  []
  [])

(defendpoint remove-thread-member! ::ds/channel-id
  "Removes a member from a thread.

  Requires `:manage-threads` permissions or that you're the creator of the thread and the thread is not archived.
  Returns a promise containing a boolean of if it succeeded."
  [user-id]
  [])

(defendpoint list-thread-members! ::ds/channel-id
  "Returns a promise containing a vector of thread member objects for the given thread."
  []
  [])

(defendpoint list-active-threads! ::ds/guild-id
  "Returns a promise containing all active threads in the guild and thread member objects for the current user."
  []
  [])

(defendpoint list-public-archived-threads! ::ds/channel-id
  "Returns a promise containing all public archived threads, thread member objects for the current user
   and a boolean indicating whether there are possibly more public archived threads in the given channel.

   Requires `:read-message-history` permissions."
  []
  [ms.thread/before limit])

(defendpoint list-private-archived-threads! ::ds/channel-id
  "Returns a promise containing all private archived threads, thread member objects for the current user
   and a boolean indicating whether there are possibly more private archived threads in the given channel.

   Requires `:read-message-history` and `:manage-threads` permissions."
  []
  [ms.thread/before limit])

(defendpoint list-joined-private-archived-threads! ::ds/channel-id
  "Returns a promise containing all private archived threads, thread member objects for the current user
   and a boolean indicating whether there are possibly more private archived threads in the given channel
   that the current user has joined.

   Requires `:read-message-history` permissions."
  []
  [ms.thread/before limit])

;; --------------------------------------------------
;; Emoji

(defendpoint list-guild-emojis! ::ds/guild-id
  "Returns a promise containing a vector of guild emoji objects."
  []
  [])

(defendpoint get-guild-emoji! ::ds/guild-id
  "Returns a promise containing the given guild emoji object."
  [emoji]
  [])

(defendpoint create-guild-emoji! ::ds/guild-id
  "Creates a new guild emoji. Returns a promise containing the new emoji object."
  [name image roles]
  [])

(defendpoint modify-guild-emoji! ::ds/guild-id
  "Modifies an existing guild emoji. Returns a promise containing the modified emoji object."
  [emoji name roles]
  [])

(defendpoint delete-guild-emoji! ::ds/guild-id
  "Deletes an emoji from the guild. Returns a promise containing a boolean of if it succeeded."
  [emoji]
  [])

;; --------------------------------------------------
;; Guild

(defendpoint create-guild! nil
  "Returns a promise containing the created guild object."
  [name region icon verification-level
   default-message-notifications explicit-content-filter
   role-objects channels]
  [])

(defendpoint get-guild! ::ds/guild-id
  "Returns a promise containing the guild object."
  []
  [with-counts])

(defendpoint modify-guild! ::ds/guild-id
  "Modifies an existing guild. Returns a promise containing the modified guild object."
  []
  [reason name region verification-level default-message-notifications
   explicit-content-filter afk-channel-id afk-timeout icon
   owner-id splash system-channel-id])

(defendpoint delete-guild! ::ds/guild-id
  "Deletes a guild if the bot is the owner. Returns a promise containing a boolean of if it succeeded."
  []
  [])

(defendpoint get-guild-channels! ::ds/guild-id
  "Returns a promise containing a vector of channel objects."
  []
  [])

(defendpoint create-guild-channel! ::ds/guild-id
  "Returns a promise containing the new channel object."
  [name]
  [ms.channel/type topic bitrate user-limit rate-limit-per-user
   position permission-overwrites parent-id nsfw])

(defendpoint modify-guild-channel-positions! ::ds/guild-id
  "Modifies an existing channel. Returns a promise containing the modified channel object."
  [channels]
  [])

(defendpoint get-guild-member! ::ds/guild-id
  "Returns a promise containing the guild member object."
  [user-id]
  [])

(defendpoint list-guild-members! ::ds/guild-id
  "Returns a promise containing a vector of the guild member objects."
  []
  [limit after])

(defendpoint search-guild-members! ::ds/guild-id
  "Returns a promise containing a vector of the guild member objects matching the search."
  [query]
  [limit])

(defendpoint add-guild-member! ::ds/guild-id
  "NOT INTENDED FOR BOT USE. Adds a user to a guild. Requires an access token. Returns a promise containing the keyword :already-member if the user is already a member, or the guild member object."
  [user-id access-token]
  [nick roles mute deaf])

(defendpoint modify-guild-member! ::ds/guild-id
  "Modifies a guild member. Returns a promise containing the modified guild member object."
  [user-id]
  [nick roles mute deaf channel-id])

(defendpoint modify-current-user-nick! ::ds/guild-id
  "Modifies the username of the current user. Returns a promise containing either nil on failure or the new nickname on success."
  [nick]
  [])

(defendpoint add-guild-member-role! ::ds/guild-id
  "Adds the given role to the user. Returns a promise containing a boolean of if it succeeded."
  [user-id role-id]
  [])

(defendpoint remove-guild-member-role! ::ds/guild-id
  "Removes the role from the user. Returns a promise containing a boolean of if it succeeded."
  [user-id role-id]
  [])

(defendpoint remove-guild-member! ::ds/guild-id
  "Kicks the member from the guild. Returns a promise containing a boolean of if it succeeded."
  [user-id]
  [])

(defendpoint get-guild-bans! ::ds/guild-id
  "Returns a promise containing a vector of ban objects."
  []
  [])

(defendpoint get-guild-ban! ::ds/guild-id
  "Returns a promise containing a ban object for the given user."
  [user-id]
  [])

(defendpoint create-guild-ban! ::ds/guild-id
  "Bans a user. Returns a promise containing a boolean of if it succeeded."
  [user-id]
  [delete-message-days reason])

(defendpoint remove-guild-ban! ::ds/guild-id
  "Unbans a user. Returns a promise containing a boolean of if it succeeded."
  [user-id]
  [])

(defendpoint get-guild-roles! ::ds/guild-id
  "Returns a promise containing a vector of role objects."
  []
  [])

(defendpoint create-guild-role! ::ds/guild-id
  "Returns a promise containing the created role."
  []
  [name permissions color hoist mentionable])

(defendpoint modify-guild-role-positions! ::ds/guild-id
  "Modifies the position of the roles in the vector. Vector must contain at least two roles, each a map with :id and :position. Returns a promise containing a vector of the guild roles."
  [roles]
  [])

(defendpoint modify-guild-role! ::ds/guild-id
  "Modifies the given role. Returns a promise containing the modified role object."
  [role-id]
  [name permissions color hoist mentionable])

(defendpoint delete-guild-role! ::ds/guild-id
  "Deletes a guild role. Returns a promise containing a boolean of if it succeeded."
  [role-id]
  [])

(defendpoint get-guild-prune-count! ::ds/guild-id
  "Returns a promise containing the number of users to be pruned."
  []
  [days])

(defendpoint begin-guild-prune! ::ds/guild-id
  "Starts a guild prune. Returns a promise containing nil if compute-prune-count is false, otherwise the number of users to be prouned."
  [days compute-prune-count]
  [])

(defendpoint get-guild-voice-regions! ::ds/guild-id
  "Returns a promise containing a vector of voice region objects."
  []
  [])

(defendpoint get-guild-invites! ::ds/guild-id
  "Returns a promise containing a vector of guild invite objects."
  []
  [])

(defendpoint get-guild-integrations! ::ds/guild-id
  "Returns a promise containing a vector of guild integration objects."
  []
  [])

(defendpoint create-guild-integration! ::ds/guild-id
  "Creates a new integration in the guild. Returns a promise containing a boolean of if it succeeded."
  [ms.integration/type ms.integration/id]
  [])

(defendpoint modify-guild-integration! ::ds/guild-id
  "Modifies an existing guild integration. Returns a promise containing a boolean of if it succeeded."
  [integration-id expire-behavior expire-grace-period enable-emoticons]
  [])

(defendpoint delete-guild-integration! ::ds/guild-id
  "Deletes a guild integration. Returns a promise containing a boolean of if it succeeded."
  [integration-id]
  [])

(defendpoint sync-guild-integration! ::ds/guild-id
  "Syncs the guild integration. Returns a promise containing a boolean of if it succeeded."
  [integration-id]
  [])

(defendpoint get-guild-widget-settings! ::ds/guild-id
  "Returns a promise containing the guild widget settings object."
  []
  [])

(defn ^:deprecated get-guild-embed!
  "Returns a promise containing the guild embed object.

  DEPRECATED: Prefer using [[get-guild-widget-settings!]]"
  {:arglists '([conn guild-id & {:keys [user-agent audit-reason]}])}
  [& args]
  (apply get-guild-widget-settings! args))
(s/fdef get-guild-embed!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :keyword-args (s/keys* :opt-un [::ds/user-agent ::ds/audit-reason]))
  :ret ::ds/promise)

(defendpoint modify-guild-widget! ::ds/guild-id
  "Modifies a guild widget object. Returns a promise containing the modified guild widget."
  [embed]
  [])

(defn ^:deprecated modify-guild-embed!
  "Modifies the guild embed object. Returns a promise containing the modified guild embed object.

  DEPRECATED: Prefer using [[get-guild-widget-settings!]]"
  {:arglists '([conn guild-id embed & {:keys [user-agent audit-reason]}])}
  [& args]
  (apply modify-guild-widget! args))
(s/fdef modify-guild-embed!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :embed ::ms/widget
               :keyword-args (s/keys* :opt-un [::ds/user-agent ::ds/audit-reason]))
  :ret ::ds/promise)

(defendpoint get-guild-widget! ::ds/guild-id
  "Returns a promise containing the guild widget object."
  []
  [])

(defendpoint get-guild-vanity-url! ::ds/guild-id
  "Returns a promise containing a partial invite object if the guild supports it, otherwise nil."
  []
  [])

(defendpoint get-guild-widget-image! ::ds/guild-id
  "Returns a promise containing the guild widget image."
  []
  [style])

;; --------------------------------------------------
;; Scheduled Events

(defendpoint list-guild-scheduled-events! ::ds/guild-id
  "Returns a promise containing a list of scheduled events for the given guild"
  []
  [with-user-count])

(defendpoint create-guild-scheduled-event! ::ds/guild-id
  "Creates a guild scheduled event, returns the resulting event object in a promise on success."
  [ms.event/name privacy-level scheduled-start-time entity-type]
  [channel-id entity-metadata scheduled-end-time ms.event/description ms.event/image])

(defendpoint get-guild-scheduled-event! ::ds/guild-id
  "Returns a promise containing the scheduled event with the given id for the given guild."
  [event-id]
  [with-user-count])

(defendpoint modify-guild-scheduled-event! ::ds/guild-id
  "Modifies the given parameters of the scheduled event with the given id for the given guild."
  [event-id]
  [channel-id entity-metadata ms.event/name privacy-level scheduled-start-time scheduled-end-time
   ms.event/description entity-type ms.event/status ms.event/image])

(defendpoint delete-guild-scheduled-event! ::ds/guild-id
  "Deletes scheduled event with the given id, returning a promise of whether it succeeded."
  [event-id]
  [])

(defendpoint get-guild-scheduled-event-users! ::ds/guild-id
  "Returns a promise containing a list of event users subscribed to the given event."
  [event-id]
  [limit with-member before after])


;; --------------------------------------------------
;; Guild Template

(defendpoint get-guild-template! nil
  "Returns a promise containing the guild template identified by the given code"
  [ms.template/code]
  [])

(defendpoint create-guild-from-guild-template! nil
  "Creates a new guild based on the template identified by the given code.

   Returns a promise containing the resulting guild on success.
   Only works for bots that are in less than 10 guilds."
  [ms.template/code]
  [])

(defendpoint get-guild-templates! ::ds/guild-id
  "Returns a promise containing the list of available guild templates."
  []
  [])

(defendpoint create-guild-template! ::ds/guild-id
  "Creates a template for the given guild.

   Returns a promise containing the resulting guild template on success."
  [ms.template/name]
  [ms.template/description])

(defendpoint sync-guild-template! ::ds/guild-id
  "Syncs the given template to the current state of the guild.

   Returns a promise containing the updated guild template on success."
  [ms.template/code]
  [])

(defendpoint modify-guild-template! ::ds/guild-id
  "Modifies the guild template's metadata.

   Returns a promise containing the updated guild template on success."
  [ms.template/code]
  [ms.template/name ms.template/description])

(defendpoint delete-guild-template! ::ds/guild-id
  "Deletes the guild template.

   Returns a promise containing the deleted guild object on success."
  [ms.template/code]
  [])


;; --------------------------------------------------
;; Invite

(defendpoint get-invite! nil
  "Returns a promise containing the invite."
  [invite-code]
  [with-counts])

(defendpoint delete-invite! nil
  "Deletes the invite. Returns a promise containing the deleted invite."
  [invite-code]
  [])

;; --------------------------------------------------
;; User

(defendpoint get-current-user! nil
  "Returns a promise containing the user object for the current user."
  []
  [])

(defendpoint get-user! nil
  "Returns a promise containing the user object for the given user."
  [user-id]
  [])

(defendpoint modify-current-user! nil
  "Modifies the current user object. Returns a promise containing the modified user object."
  []
  [username avatar])

(defendpoint get-current-user-guilds! nil
  "Returns a promise containing the current user's guilds. Pagination will be required if the bot is in over 100 guilds."
  []
  [before after limit])

(defendpoint leave-guild! nil
  "Current user leaves the given guild. Returns a promise containing a boolean of if it succeeded."
  [guild-id]
  [])

(defendpoint get-user-dms! nil
  "Returns a promise containing a vector of DM channel objects."
  []
  [])

(defendpoint create-dm! nil
  "Returns a promise containing a DM channel object with the given user."
  [user-id]
  [])

(defendpoint create-group-dm! nil
  "NOT INTENDED FOR BOT USE. Returns a promise containing a DM channel object."
  [access-tokens nicks]
  [])

(defendpoint get-user-connections! nil
  "NOT INTENDED FOR BOT USE. Returns a promise containing a vector of connection objects."
  []
  [])

;; --------------------------------------------------
;; Voice

(defendpoint list-voice-regions! nil
  "Returns a promise containing a vector of voice regions."
  []
  [])

;; --------------------------------------------------
;; Webhook

(defendpoint create-webhook! ::ds/channel-id
  "Returns a promise containing the new webhook object."
  [name]
  [avatar])

(defendpoint get-channel-webhooks! ::ds/channel-id
  "Returns a promise containing a vector of webhook objects."
  []
  [])

(defendpoint get-guild-webhooks! ::ds/guild-id
  "Returns a promise containing a vector of webhook objects."
  []
  [])

(defendpoint get-webhook! ::ds/webhook-id
  "Returns a promise containing a webhook object."
  []
  [])

(defendpoint get-webhook-with-token! ::ds/webhook-id
  "Returns a promise containing a webhook object, but does not require authentication."
  [webhook-token]
  [])

(defendpoint modify-webhook! ::ds/webhook-id
  "Returns a promise containing the modified webhook object."
  []
  [name avatar channel-id])

(defendpoint modify-webhook-with-token! ::ds/webhook-id
  "Returns a promise containing the modified webhook object, but does not require authentication."
  [webhook-token]
  [name avatar channel-id])

(defendpoint delete-webhook! ::ds/webhook-id
  "Deletes the webhook. Returns a promise containing a boolean of if it succeeded."
  []
  [])

(defendpoint delete-webhook-with-token! ::ds/webhook-id
  "Deletes the webhook, but does not require authentication. Returns a promise containing a boolean of if it succeeded."
  [webhook-token]
  [])

(defendpoint execute-webhook! ::ds/webhook-id
  "Executes the given webhook. Returns a promise which contains either a boolean of if the message succeeded, or a map of the response body."
  [webhook-token]
  [content file stream embeds wait username avatar-url tts allowed-mentions components])

(defendpoint get-webhook-message! ::ds/webhook-id
  "Returns the webhook message sent by the given webhook with the given id."
  [webhook-token message-id]
  [])

(defendpoint edit-webhook-message! ::ds/webhook-id
  "Edits a previously-sent webhook message from the same token.

  Returns a promise containing the updated message object."
  [webhook-token message-id]
  [content embeds allowed-mentions components])

(defendpoint delete-webhook-message! ::ds/webhook-id
  "Deletes a messages that was sent from the given webhook.

  Returns a promise containing a boolean of if it succeeded."
  [webhook-token message-id]
  [])

#_(defendpoint execute-slack-compatible-webhook! ::ds/webhook-id
    ""
    [webhook-token]
    [wait])

#_(defendpoint execute-github-compatible-webhook! ::ds/webhook-id
    ""
    [webhook-token]
    [wait])

;; --------------------------------------------------
;; Slash Commands

(defendpoint get-global-application-commands! nil
  "Returns a promise containing a vector of application command objects."
  [application-id]
  [])

(defendpoint create-global-application-command! nil
  "Creates or updates a global slash command.

  New global commands will be available in all guilds after 1 hour.
  Returns a promise containing the new application command object."
  [application-id ms.command/name ms.command/description]
  [ms.command/options ms.command/default-permission ms.command/type])

(defendpoint edit-global-application-command! nil
  "Updates an existing global slash command by its id.

  Returns a promise containing the updated application command object."
  [application-id command-id]
  [ms.command/name ms.command/description ms.command/options ms.command/default-permission ms.command/type])

(defendpoint delete-global-application-command! nil
  "Deletes an existing global slash command by its id.

  Returns a promise containing a boolean of if it succeeded."
  [application-id command-id]
  [])

(defendpoint bulk-overwrite-global-application-commands! nil
  "Overwrites all global slash commands with the provided ones.

  If a command with a given name doesn't exist, creates that command.
  Returns a promise containing the updated application command objects."
  [application-id commands]
  [])

(defendpoint get-guild-application-commands! nil
  "Returns a promise containing a vector of application command objects."
  [application-id guild-id]
  [])

(defendpoint create-guild-application-command! nil
  "Creates or updates a guild slash command.

  Returns a promise containing the new application command object."
  [application-id guild-id ms.command/name ms.command/description]
  [ms.command/options ms.command/default-permission ms.command/type])

(defendpoint edit-guild-application-command! nil
  "Updates an existing guild slash command by its id.

  Returns a promise containing the updated application command object."
  [application-id guild-id command-id]
  [ms.command/name ms.command/description ms.command/options ms.command/default-permission ms.command/type])

(defendpoint delete-guild-application-command! nil
  "Deletes an existing guild slash command by its id.

  Returns a promise containing a boolean of if it succeeded."
  [application-id guild-id command-id]
  [])

(defendpoint bulk-overwrite-guild-application-commands! nil
  "Overwrites all guild slash commands with the provided ones.

  If a command with a given name doesn't exist, creates that command.
  Returns a promise containing the updated application command objects."
  [application-id guild-id commands]
  [])

(defendpoint get-guild-application-command-permissions! nil
  "Returns a promise containing the permission settings for all application commands accessible from the guild."
  [application-id guild-id]
  [])

(defendpoint get-application-command-permissions! nil
  "Returns a promise containing the permission settings for a specific application command accessible from the guild."
  [application-id guild-id command-id]
  [])

(defendpoint edit-application-command-permissions! nil
  "Sets the permission settings for the given command in the guild.

  Returns a promise containing the updated permission settings in a map with some additional information."
  [application-id guild-id command-id ms.command/permissions]
  [])

(defendpoint batch-edit-application-command-permissions! nil
  "Batch edits the permission settings for all commands in a guild.

  This will overwrite all existing permissions for all commands in the guild.
  Returns a promise containing the updated permission settings."
  [application-id guild-id ms.command.guild/permissions-array]
  [])

;; -------------------------------------------------
;; Interactions

(defendpoint create-interaction-response! nil
  "Sends a response to an interaction event.

  Returns a promise containing a boolean of if it succeeded."
  [interaction-id interaction-token ms.interaction-response/type]
  [ms.interaction-response/data file stream])

(defendpoint get-original-interaction-response! ::ms/interaction-token
  "Returns a promise containing the response object sent for the given interaction."
  [application-id interaction-token]
  [])

(defendpoint edit-original-interaction-response! ::ms/interaction-token
  "Edits the inital response to the given interaction.

  Returns a promise containing the updated message object"
  [application-id interaction-token]
  [content embeds allowed-mentions components])

(defendpoint delete-original-interaction-response! nil
  "Deletes the initial response to the given interaction.

  Returns a promise containing a boolean of if it succeeded."
  [application-id interaction-token]
  [])

(defendpoint create-followup-message! ::ms/interaction-token
  "Creates a followup message for the given interaction.

  Returns a promise containing the message that was created."
  [application-id interaction-token]
  [content file stream embeds username avatar-url tts allowed-mentions components flags])

(defendpoint edit-followup-message! ::ms/interaction-token
  "Edits a followup message to an interaction by its id.

  Returns a promise containing the updated message object."
  [application-id interaction-token message-id]
  [content embeds allowed-mentions components])

(defendpoint delete-followup-message! ::ms/interation-token
  "Deletes a followup message to an interaction by its id.

  Returns a promise containing a boolean of if it succeeded."
  [application-id interaction-token message-id]
  [])

(defendpoint get-current-application-information! nil
  "Returns a promise containing the bot's OAuth2 application info."
  []
  [])

;; -------------------------------------------------
;; Stages

(defendpoint create-stage-instance! nil
  "Creates a new stage instance associated to a stage channel.

  Returns a promise with the stage instance."
  [channel-id ms.stage/topic]
  [ms.stage/privacy-level])

(defendpoint get-stage-instance! ::ds/channel-id
  "Returns a promise containing the stage instance for the given channel."
  []
  [])

(defendpoint modify-stage-instance! ::ds/channel-id
  "Edits a stage instance for the given channel.

  Returns a promise with the modified stage instance."
  []
  [ms.stage/topic ms.stage/privacy-level])

(defendpoint delete-stage-instance! ::ds/channel-id
  "Deletes a stage instance for the given channel.

  Returns a promise of a boolean indicating whether the operation succeeded."
  []
  [])

;; -------------------------------------------------
;; Stickers

(defendpoint get-sticker! nil
  "Returns a promise containing a sticker object."
  [sticker-id]
  [])

(defendpoint list-nitro-sticker-packs! nil
  "Returns a promise containing a vector of sticker pack objects."
  []
  [])

(defendpoint list-guild-stickers! ::ds/guild-id
  "Returns a promise containing a vector of sticker objects."
  []
  [])

(defendpoint get-guild-sticker! ::ds/guild-id
  "Returns a promise containing a guild-specific sticker object."
  [sticker-id]
  [])

(defendpoint create-guild-sticker! ::ds/guild-id
  "Creates a new sticker on the guild with the given options.

  Returns a promise containing a guild-specific sticker object."
  [ms.sticker/name ms.sticker/description ms.sticker/tags file]
  [])

(defendpoint modify-guild-sticker! ::ds/guild-id
  "Modifies an existing sticker with new options.

  Returns a promise containing the modified sticker object."
  [sticker-id]
  [ms.sticker/name ms.sticker/description ms.sticker/tags])

(defendpoint delete-guild-sticker! ::ds/guild-id
  "Deletes an existing sticker from the guild.

  Returns a promise with a boolean indicating whether the operation succeeded."
  [sticker-id]
  [])
