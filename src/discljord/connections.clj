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
   [discljord.http :refer [api-url]]
   [discljord.specs :as ds]
   [discljord.util :refer [bot-token]]
   [taoensso.timbre :as log]))

(defn connect-bot!
  "Creates a connection process which will handle the services granted by
  Discord which interact over websocket.

  Takes a token for the bot, and a channel on which all events from Discord
  will be sent back across.

  Returns a channel used to communicate with the process and send packets to
  Discord.

  Keep in mind that Discord sets a limit to how many shards can connect in a
  given period. This means that communication to Discord may be bounded based
  on which shard you use to talk to the server immediately after starting the bot."
  [token out-ch & {:keys [buffer-size]}]
  (let [token (bot-token token)
        {:keys [discljord.specs/url discljord.connections.specs/shard-count
                discljord.connections.specs/session-start-limit]}
        (impl/get-websocket-gateway! (api-url "/gateway/bot") token)]
    (if (and url shard-count session-start-limit)
      (do (when (< (:remaining session-start-limit) shard-count)
            (throw (RuntimeException. "Not enough remaining identify packets for number of shards.")))
          (let [communication-chan (a/chan 100)
                shards (atom (impl/connect-shards! url token shard-count out-ch
                                                   communication-chan
                                                   :buffer-size buffer-size))]
            (a/put! out-ch [:connect])
            (impl/start-communication-loop! shards token communication-chan out-ch communication-chan)
            communication-chan))
      (log/debug "Unable to recieve gateway information."))))
(s/fdef connect-bot!
  :args (s/cat :token ::ds/token :out-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/buffer-size]))
  :ret ::ds/channel)

(defn disconnect-bot!
  "Takes the channel returned by connect-bot! and stops the connection."
  [connection-ch]
  (a/put! connection-ch [:disconnect])
  nil)
(s/fdef disconnect-bot!
  :args (s/cat :channel ::ds/channel)
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
  type: keywords :game, :stream, or :music which change how the status message displays, as \"Playing\", \"Streaming\", or \"Listening to\" respectively, defaults to :game
  url: link to display with the :stream type, currently only urls starting with https://twitch.tv/ will work, defaults to nil"
  [& {:keys [name type url] :or {type :game} :as args}]
  (let [args (into {} (filter (fn [[key val]] val) args))
        args (if (:type args)
               args
               (assoc args :type :game))
        type (case (:type args)
               :game 0
               :stream 1
               :music 2
               0 0
               1 1
               2 2)]
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
