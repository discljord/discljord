(ns discljord.connections
  "Namespace for creating a connection to Discord, and recieving messages.
  Contains functionality required to create and maintain a sharded and auto-reconnecting
  connection object which will recieve messages from Discord, and pass them on to client
  code."
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [discljord.connections.impl :as impl]
   [discljord.connections.specs :as cs]
   [discljord.http :refer [gateway-url]]
   [discljord.specs :as ds]
   [discljord.util :refer [bot-token derefable-promise-chan]]
   [clojure.tools.logging :as log]))

(def gateway-intents #{:guilds :guild-members :guild-bans :guild-emojis
                       :guild-integrations :guild-webhooks :guild-invites
                       :guild-voice-states :guild-presences :guild-messages
                       :guild-message-reactions :guild-message-typing
                       :direct-messages :direct-message-reactions
                       :direct-message-typing})

(defn connect-bot!
  "Creates a connection process which will handle the services granted by
  Discord which interact over websocket.

  Takes a token for the bot, and a channel on which all events from Discord
  will be sent back across.

  Returns a channel used to communicate with the process and send packets to
  Discord.

  Keep in mind that Discord sets a limit to how many shards can connect in a
  given period. This means that communication to Discord may be bounded based on
  which shard you use to talk to the server immediately after starting the bot.

  `intents` is a set containing keywords representing which events will be sent
  to the bot by Discord. Valid values for the set are in [[gateway-intents]]. If
  `intents` is unspecified, a [[clojure.core/ex-info]] is returned with a
  relevant message."
  [token out-ch & {:keys [intents disable-compression]}]
  (if-not intents
    (ex-info "Intents are required as of v8 of the API")
    (let [token (bot-token token)
          {:keys [url shard-count session-start-limit]}
          (impl/get-websocket-gateway gateway-url token)]
      (if (and url shard-count session-start-limit)
        (do (when (< (:remaining session-start-limit) shard-count)
              (throw (ex-info "Not enough remaining identify packets for number of shards."
                              {:token token
                               :shard-count shard-count
                               :remaining-starts (:remaining session-start-limit)
                               :reset-after (:reset-after session-start-limit)})))
            (let [communication-chan (a/chan 100)]
              (binding [impl/*identify-limiter* (agent nil)]
                (impl/connect-shards! out-ch communication-chan url token intents shard-count (range shard-count)
                                      (not disable-compression)))
              communication-chan))
        (log/debug "Unable to recieve gateway information.")))))
(s/fdef connect-bot!
  :args (s/cat :token ::ds/token :out-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/intents ::cs/disable-compression]))
  :ret ::ds/channel)

(defn get-websocket-gateway
  "Gets the shard count and websocket endpoint from Discord's API.

  Takes the `token` of the bot.

  Returns a map with the keys :url, :shard-count, and :session-start limit, or
  nil in the case of an error."
  [token]
  (impl/get-websocket-gateway gateway-url (bot-token token)))
(s/fdef get-websocket-gateway
  :args (s/cat :token ::ds/token)
  :ret ::cs/gateway)

(defn connect-shards!
  "Connects a specific set of shard ids to Discord.

  Acts like [[connect-bot!]], but also requires a gateway returned
  from [[get-websocket-gateway]] and a sequence of shard-ids to connect with, as
  well as a function used to determine when identify payloads are permitted to
  be sent.

  `identify` must be a function from token to core.async channel which yields an
  item when an identify is permitted. This must be at least five seconds after
  the most recent identify by any shard, however any additional attempts to
  identify from this connection will include that wait by default, only
  identifies from other connections must be taken into account for this.

  The recommended number of shards for your bot can be determined by the return
  value of [[get-websocket-gateway]].

  If Discord determines the bot must re-shard, then a `:re-shard` event will be
  emitted from the returned channel and all shards will be disconnected.

  Because Discord has a limit of one shard connecting per five seconds, some
  amount of synchronization between calls to [[connect-shards!]] must be had.
  Additional calls should only be made five seconds after the
  `:connected-all-shards` event has been received."
  [token out-ch gateway shard-ids & {:keys [intents identify-when disable-compression]}]
  (if-not intents
    (ex-info "Intents are required as of v8 of the API" {})
    (let [token (bot-token token)
          {:keys [url shard-count session-start-limit]} gateway]
      (if (and url shard-count session-start-limit)
        (do (when (< (:remaining session-start-limit) (count shard-ids))
              (throw (ex-info "Not enough remaining identify packets for number of shards."
                              {:token token
                               :shard-count (count shard-ids)
                               :shard-ids shard-ids
                               :remaining-starts (:remaining session-start-limit)
                               :reset-after (:reset-after session-start-limit)})))
            (let [communication-chan (a/chan 100)]
              (binding [impl/*handle-re-shard* false
                        impl/*identify-when* identify-when
                        impl/*identify-limiter* (agent nil)]
                (impl/connect-shards! out-ch communication-chan url token intents shard-count shard-ids
                                      (not disable-compression)))
              communication-chan))
        (log/debug "Unable to receive gateway information.")))))
(s/fdef connect-shards!
  :args (s/cat :token ::ds/token :out-ch ::ds/channel
               :gateway ::cs/gateway :shard-ids (s/coll-of nat-int?)
               :keyword-args (s/keys* :opt-un [::cs/intents ::cs/identify-when ::cs/disable-compression]))
  :ret ::ds/channel)

(defn disconnect-bot!
  "Takes the channel returned by connect-bot! and stops the connection."
  [connection-ch]
  (a/put! connection-ch [:disconnect])
  nil)
(s/fdef disconnect-bot!
  :args (s/cat :channel ::ds/channel)
  :ret nil?)

(defn get-shard-state!
  "Fetches the current shard session state.

  `shards` is an optional set of shard state values. If it is not included, all
  shards will be fetched. Any shard which matches with the given keys will be
  included.

  Returns a promise which can be derefed or taken off of like a channel."
  ([connection-ch] (get-shard-state! connection-ch nil))
  ([connection-ch shards]
   (let [prom (derefable-promise-chan)]
     (a/put! connection-ch [:get-shard-state
                            (when shards
                              (set (map #(dissoc % :seq) shards))) prom])
     prom)))
(s/fdef get-shard-state!
  :args (s/cat :connection-ch ::ds/channel
               :shards (s/? (s/coll-of ::cs/shard :kind set?)))
  :ret ::ds/promise)

(defn add-shards!
  "Adds new shard connections using state fetched with `get-shard-state!`."
  [connection-ch new-shards intents & {:keys [disable-compression]}]
  (a/put! connection-ch [:connect-shards new-shards intents disable-compression])
  nil)
(s/fdef add-shards!
  :args (s/cat :connection-ch ::ds/channel
               :new-shards ::cs/shard
               :intents ::cs/intents
               :keyword-args (s/keys* :opt-un [::cs/disable-compression]))
  :ret nil?)

(defn remove-shards!
  "Removes shards matching any of the passed shards.

  This will ignore the `:seq` keyword on any passed shard, but otherwise is as
  one of the shards returned from `get-shard-state!`."
  [connection-ch shards]
  (a/put! connection-ch [:disconnect-shards (set (map #(dissoc % :seq) shards))])
  nil)
(s/fdef remove-shards!
  :args (s/cat :connection-ch ::ds/channel
               :shards (s/coll-of ::cs/shard :kind set?))
  :ret nil?)

(defn guild-request-members!
  "Takes the channel returned by connect-bot!, the snowflake guild id, and optional arguments
  about the members you want to get information about, and signals Discord to send you
  :guild-members-chunk events.

  Keyword Arguments:
  query: a string that the username of the searched user starts with, or empty string for all users, defaults to empty string
  limit: the maximum number of members to give based on the query"
  [connection-ch guild-id & args]
  (a/put! connection-ch (apply vector :guild-request-members :guild-id guild-id args)))
(s/fdef guild-request-members!
  :args (s/cat :channel ::ds/channel
               :guild-id ::ds/snowflake
               :keyword-args (s/keys* :opt-un [::cs/query
                                               ::cs/limit])))

(defn create-activity
  "Takes keyword arguments and constructs an activity to be used in status updates.

  Keyword Arguments:
  name: a string which will display as the bot's status message, required
  type: keywords :game, :stream, :music, or :watch which change how the status message displays, as \"Playing\", \"Streaming\", \"Listening to\", or \"Watching\" respectively, defaults to :game. You can also pass the number of the discord status type directly if it isn't listed here.
  url: link to display with the :stream type, currently only urls starting with https://twitch.tv/ will work, defaults to nil"
  [& {:keys [name type url] :or {type :game} :as args}]
  (let [args (into {} (filter (fn [[key val]] val) args))
        args (if (:type args)
               args
               (assoc args :type :game))
        type (if (number? type)
               type
               (case type
                 :game 0
                 :stream 1
                 :music 2
                 :watch 3))]
    (assert (:name args) "A name should be provided to an activity")
    (assoc args :type type)))
(s/fdef create-activity
  :args (s/keys* :req-un [::cs/name]
                 :opt-un [::cs/type
                          ::ds/url])
  :ret ::cs/activity)

(defn status-update!
  "Takes the channel returned by connect-bot! and a set of keyword options, and updates
  Discord with a new status for your bot.

  Keyword Arguments:
  idle-since: epoch time in milliseconds of when the bot went idle, defaults to nil
  activity: an activity map, from create-activity, which is used for the bot, defaults to nil
  status: a keyword representing the current status of the bot, can be :online, :dnd, :idle, :invisible, or :offline, defaults to :online
  afk: a boolean to say if the bot is afk, defaults to false"
  [connection-ch & args]
  (a/put! connection-ch (apply vector :status-update args)))
(s/fdef status-update!
  :args (s/cat :channel ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/idle-since
                                               ::cs/activity
                                               ::cs/status
                                               ::cs/afk])))

(defn voice-state-update!
  "Takes the channel returned by connect-bot!, a guild id, and a set of keyword options and
  updates Discord with a new voice state.

  Keyword Arguments:
  channel-id: the new channel id snowflake that your bot is in, disconnect if nil, defaults to nil
  mute: boolean which says if the bot is muted
  deaf: boolean which says if the bot is deafened"
  [connection-ch guild-id & args]
  (a/put! connection-ch (apply vector :voice-state-update :guild-id guild-id args)))
(s/fdef voice-state-update!
  :args (s/cat :channel ::ds/channel
               :guild-id ::ds/snowflake
               :keyword-args (s/keys* :opt-un [::ds/channel-id
                                               ::cs/mute
                                               ::cs/deaf])))
