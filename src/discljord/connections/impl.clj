(ns discljord.connections.impl
  "Implementation namespace for `discljord.connections`."
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [discljord.connections.specs :as cs]
   [discljord.http :refer [api-url]]
   [discljord.specs :as ds]
   [discljord.util :refer [json-keyword clean-json-input *enable-logging*]]
   [gniazdo.core :as ws]
   [org.httpkit.client :as http]
   [taoensso.timbre :as log])
  (:import
   (org.eclipse.jetty websocket.client.WebSocketClient
                      util.ssl.SslContextFactory)))

(defn get-websocket-gateway!
  "Gets the shard count and websocket endpoint from Discord's API."
  [url token]
  (if-let [result
           (try
             (when-let [response (:body @(http/get url
                                                   {:headers
                                                    {"Authorization" token}}))]
               (when-let [json-body (clean-json-input (json/read-str response))]
                 {::ds/url (:url json-body)
                  ::cs/shard-count (:shards json-body)
                  ::cs/session-start-limit (:session-start-limit json-body)}))
             (catch Exception e
               (when *enable-logging*
                 (log/error e "Failed to get websocket gateway"))
               nil))]
    (when (::ds/url result)
      result)))
(s/fdef get-websocket-gateway!
  :args (s/cat :url ::ds/url :token ::ds/token)
  :ret (s/nilable ::cs/gateway))

(defn reconnect-websocket!
  "Takes a websocket connection atom and additional connection information,
  and reconnects a websocket, with options for resume or not."
  [url token conn ch shard out-ch & {:keys [init-shard-state
                                            buffer-size]}]
  (when *enable-logging*
    (log/debug (str "Connecting shard " shard)))
  (let [ack (atom false)
        connected (atom false)
        shard-state (atom (assoc (or init-shard-state
                                     {:session-id nil
                                      :seq nil
                                      :buffer-size (or buffer-size
                                                       100000)
                                      :max-connection-retries 10})
                                 :disconnect false))
        client (WebSocketClient. (SslContextFactory.))
        buffer-size (:buffer-size @shard-state)]
    (.setMaxTextMessageSize (.getPolicy client) buffer-size)
    (.setMaxBinaryMessageSize (.getPolicy client) buffer-size)
    (.setMaxTextMessageBufferSize client buffer-size)
    (.setMaxBinaryMessageBufferSize client buffer-size)
    (.start client)
    (reset!
     conn
     (loop [retries 0]
       (let [connection
             (try (ws/connect url
                    :client client
                    :on-connect
                    (fn [_]
                      ;; Put a connection event on the channel
                      (reset! connected true)
                      (a/put! out-ch [:shard-connect])
                      (a/put! ch (if init-shard-state
                                   [:reconnect conn token
                                    (:session-id @shard-state)
                                    (:seq @shard-state)]
                                   [:connect conn token shard])))
                    :on-receive
                    (fn [msg]
                      ;; Put a recieve event on the channel
                      (let [msg (clean-json-input (json/read-str msg))
                            op (:op msg)
                            data (:d msg)]
                        (a/put! ch (if (= op 0)
                                     (let [new-seq (:s msg)
                                           msg-type (json-keyword (:t msg))]
                                       (swap! shard-state assoc :seq new-seq)
                                       [:event msg-type data shard-state out-ch])
                                     [:command op data
                                      #(do
                                         (ws/close @conn)
                                         (fn []
                                           (apply reconnect-websocket! url token conn
                                                  ch shard out-ch
                                                  :buffer-size buffer-size
                                                  %&)))
                                      #(do
                                         (ws/close @conn)
                                         (fn []
                                           (apply reconnect-websocket! url token conn
                                                  ch shard out-ch
                                                  :init-shard-state @shard-state
                                                  :buffer-size buffer-size
                                                  %&)))
                                      #(ws/send-msg @conn
                                                    (json/write-str
                                                     {"op" 1
                                                      "d" (:seq @shard-state)}))
                                      ack connected shard-state]))))
                    :on-close
                    (fn [stop-code msg]
                      ;; Put a disconnect event on the channel
                      (reset! connected false)
                      (a/put! ch [:disconnect shard-state stop-code msg
                                  #(apply reconnect-websocket! url token conn
                                          ch shard out-ch
                                          :buffer-size buffer-size
                                          %&)
                                  #(apply reconnect-websocket! url token conn
                                          ch shard out-ch
                                          :init-shard-state @shard-state
                                          :buffer-size buffer-size
                                          %&)]))
                    :on-error
                    (fn [err]
                      (when *enable-logging*
                        (log/error err "Error caught on websocket"))))
                  (catch Exception e
                    (when *enable-logging*
                      (log/error e "Exception while trying to connect websocket to Discord"))
                    nil))]
         (if connection
           (do (when *enable-logging*
                 (log/trace "Successfully connected to Discord"))
               connection)
           (if (> (:max-connection-retries @shard-state) retries)
             (do (when *enable-logging*
                   (log/debug "Failed to connect to Discord, retrying in 10 seconds"))
                 (a/<!! (a/timeout 10000))
                 (recur (inc retries)))
             (when *enable-logging*
               (log/info (str "Could not connect to discord after "
                             retries
                             " retries, aborting"))))))))
    shard-state))
(s/fdef reconnect-websocket!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :connection (ds/atom-of? ::ds/connection)
               :event-channel ::ds/channel
               :shard (s/tuple ::cs/shard-id ::cs/shard-count)
               :out-channel ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/init-shard-state
                                               ::cs/buffer-size]))
  :ret (ds/atom-of? ::cs/shard-state))

(defmulti handle-websocket-event!
  "Handles events sent from discord over the websocket.

  Takes the channel to send events out of the process, a
  keyword event type, and any number of event data arguments."
  (fn [out-ch comm-ch event-type & event-data]
    event-type))

(defmethod handle-websocket-event! :connect
  [out-ch comm-ch _ & [conn token shard :as thing]]
  (ws/send-msg @conn
               (json/write-str {"op" 2
                                "d" {"token" token
                                     "properties" {"$os" "linux"
                                                   "$browser" "discljord"
                                                   "$device" "discljord"}
                                     "compress" false
                                     "large_threshold" 50
                                     "shard" shard}})))

(defmethod handle-websocket-event! :reconnect
  [out-ch comm-ch _ & [conn token session-id seq]]
  (ws/send-msg @conn
               (json/write-str {"op" 6
                                "d" {"token" token
                                     "session_id" session-id
                                     "seq" seq}})))

(defmulti handle-disconnect!
  "Handles the disconnection process for different stop codes.

  Takes an integer stop code, the message, a function to reconnect
  the shard, and a function to reconnect the shard with resume."
  (fn [socket-state comm-ch stop-code msg reconnect resume]
    stop-code))

(defmethod handle-disconnect! :default
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/debug (str "Stop code "
                   stop-code
                   " encountered with message:\n\t"
                   msg)))
  ;; NOTE(Joshua): Not sure if this should do a reconnect or a resume
  (when-not (:disconnect @socket-state)
    (when *enable-logging*
      (log/debug "Reconnecting"))
    (reconnect)))

(defmethod handle-disconnect! 4000
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/debug "Unknown error, reconnect and resume"))
  (resume))

(defmethod handle-disconnect! 4001
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Invalid op code sent to Discord servers")))

(defmethod handle-disconnect! 4002
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Invalid payload sent to Discord servers")))

(defmethod handle-disconnect! 4003
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Sent data to Discord without authenticating")))

(defmethod handle-disconnect! 4004
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/error "Invalid token sent to Discord servers"))
  (throw (Exception. "Invalid token sent to Discord servers")))

(defmethod handle-disconnect! 4005
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Sent identify packet to Discord twice from same shard")))

(defmethod handle-disconnect! 4007
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/debug "Sent invalid seq to Discord on resume, reconnect"))
  (reconnect))

(defmethod handle-disconnect! 4008
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Rate limited by Discord")))

(defmethod handle-disconnect! 4009
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/debug "Session timed out, reconnecting"))
  (reconnect))

(defmethod handle-disconnect! 4010
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/fatal "Invalid shard set to discord servers")))

(defmethod handle-disconnect! 4011
  [socket-state comm-ch stop-code msg reconnect resume]
  ;; NOTE(Joshua): This should never happen, unless discord sends
  ;;               this as the bot joins more servers. If so, then
  ;;               make this cause a full disconnect and reconnect
  ;;               of the whole bot, not just this shard.
  (a/put! comm-ch [:re-shard (::cs/buffer-size @socket-state)])
  (when *enable-logging*
    (log/info "Bot requires sharding")))

(defmethod handle-disconnect! 1009
  [socket-state comm-ch stop-code msg reconnect resume]
  (when *enable-logging*
    (log/info "Websocket size wasn't big enough! Resuming with a bigger buffer."))
  (resume :buffer-size (+ (:buffer-size @socket-state)
                          100000)))

(defmethod handle-websocket-event! :disconnect
  [out-ch comm-ch _ & [socket-state stop-code msg reconnect resume]]
  (when *enable-logging*
    (log/debug "Shard disconnected from Discord"))
  (a/put! out-ch [:shard-disconnect])
  (handle-disconnect! socket-state comm-ch stop-code msg reconnect resume))

(defmulti handle-payload!
  "Handles a command payload sent from Discord.

  Takes an integer op code, the data sent from Discord, a function to reconnect
  the shard to Discord, a function to reconnect to Discord with a resume,
  a function to send a heartbeat to Discord, an atom containing the ack state
  from the last heartbeat, an atom containing whether the current shard is connected,
  and an atom containing the shard state, including the seq and session-id."
  ;; FIXME(Joshua): The ack and connected atoms should be moved to keys in the shard-state atom
  (fn [op data reconnect resume heartbeat ack connected shard-state]
    op))

(defmethod handle-payload! 1
  [op data reconnect resume heartbeat ack connected shard-state]
  (when *enable-logging*
    (log/trace "Heartbeat requested by Discord"))
  (when @connected
    (swap! shard-state assoc :seq data)
    (heartbeat)))

(defmethod handle-payload! 7
  [op data reconnect resume heartbeat ack connected shard-state]
  (when *enable-logging*
    (log/debug "Reconnect payload sent from Discord"))
  (reconnect))

(defmethod handle-payload! 9
  [op data reconnect resume heartbeat ack connected shard-state]
  (when *enable-logging*
    (log/debug "Invalid session sent from Discord, reconnecting"))
  (if data
    (resume)
    (reconnect)))

(defmethod handle-payload! 10
  [op data reconnect resume heartbeat ack connected shard-state]
  (let [interval (get data :heartbeat-interval)]
    (a/go-loop []
      ;; Send heartbeat
      (reset! ack false)
      (try (heartbeat)
           (catch Exception e
             (when *enable-logging*
               (log/error e "Unable to send heartbeat"))))
      ;; Wait the interval
      (a/<! (a/timeout interval))
      ;; If there's no ack, disconnect and reconnect with resume
      (if-not @ack
        (try (resume)
             (catch Exception e
               (when *enable-logging*
                 (log/error e "Unable to resume"))))
        ;; Make this check to see if we've disconnected
        (when @connected
          (recur))))))

(defmethod handle-payload! 11
  [op data reconnect resume heartbeat ack connected shard-state]
  (reset! ack true))

(defmethod handle-payload! :default
  [op data reconnect resume heartbeat ack connected shard-state]
  (when *enable-logging*
    (log/error "Recieved an unhandled payload from Discord. op code" op "with data" data)))

(defmethod handle-websocket-event! :command
  [out-ch comm-ch _ & [op data reconnect resume heartbeat ack connected shard-state]]
  (handle-payload! op data reconnect resume heartbeat ack connected shard-state))

(defmulti handle-event
  "Handles event payloads sent from Discord in the context of the connection,
  before it gets to the regular event handlers.

  Takes a keyword event type, event data from Discord, an atom containing the shard's
  state, and the channel for sending events out of the process."
  (fn [event-type data shard-state out-ch]
    event-type))

(defmethod handle-event :ready
  [event-type data shard-state out-ch]
  (let [session-id (:session-id data)]
    (swap! shard-state assoc :session-id session-id)))

(defmethod handle-event :default
  [event-type data shard-state out-ch]
  nil)

(defmethod handle-websocket-event! :event
  [out-ch comm-ch _ & [event-type data shard-state out-ch]]
  (handle-event event-type data shard-state out-ch)
  (a/put! out-ch [event-type data]))

(defn start-event-loop!
  "Starts a go loop which takes events from the channel and dispatches them
  via multimethod."
  [conn ch out-ch comm-ch]
  (a/go-loop []
    (try (apply handle-websocket-event! out-ch comm-ch (a/<! ch))
         (catch Exception e
           (when *enable-logging*
             (log/error e "Exception caught from handle-websocket-event!"))))
    (when @conn
      (recur)))
  nil)
(s/fdef start-event-loop!
  :args (s/cat :connection (ds/atom-of? ::cs/connection)
               :event-channel ::ds/channel
               :out-channel ::ds/channel
               :comm-ch ::ds/channel)
  :ret nil?)

(defn connect-shard!
  "Takes a gateway URL and a bot token, creates a websocket connected to
  Discord's servers, and returns a function of no arguments which disconnects it"
  [url token shard-id shard-count out-ch comm-ch & {:keys [buffer-size]}]
  (let [event-ch (a/chan 100)
        conn (atom nil)
        shard-state (reconnect-websocket! url token conn event-ch [shard-id shard-count] out-ch
                                          :buffer-size buffer-size)]
    (start-event-loop! conn event-ch out-ch comm-ch)
    [conn shard-state]))
(s/fdef connect-shard!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :shard-id int?
               :shard-count pos-int?
               :out-channel ::ds/channel
               :comm-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/buffer-size]))
  :ret (s/tuple (ds/atom-of? ::cs/connection)
                (ds/atom-of? ::cs/shard-state)))

(defn connect-shards!
  "Calls connect-shard! once per shard in shard-count, and returns a sequence
  of promises of the return results."
  [url token shard-count out-ch comm-ch & {:keys [buffer-size]}]
  ;; FIXME: This is going to break when there's a high enough shard count
  ;;        that the JVM can't handle having more threads doing this.
  (doall
   (for [shard-id (range shard-count)]
     (let [prom (promise)]
       (a/thread
         (Thread/sleep (* 5000 shard-id))
         (deliver prom (connect-shard! url token shard-id shard-count out-ch comm-ch
                                       :buffer-size buffer-size)))
       prom))))
(s/fdef connect-shards!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :shard-count pos-int?
               :out-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::cs/buffer-size]))
  :ret (s/coll-of ::ds/promise))

(defmulti handle-command!
  "Handles commands from the outside world.

  Takes an atom of a vector of promises containing atoms of shard connections,
  the channel used to send events out of the process, a keyword command type,
  and any number of command data arguments."
  (fn [shards token out-ch comm-ch command-type & command-data]
    command-type))

(defmethod handle-command! :default
  [shards token out-ch comm-ch command-type & command-data]
  nil)

(defmethod handle-command! :disconnect
  [shards token out-ch comm-ch command-type & command-data]
  (a/put! out-ch [:disconnect])
  (doseq [fut @shards]
    (a/thread
      (let [[conn shard-state] @fut]
        (swap! shard-state assoc :disconnect true)
        (swap! conn #(do (when %
                           (ws/close %))
                         nil))))))

(defmethod handle-command! :re-shard
  [shards token out-ch comm-ch command-type & [buffer-size]]
  (doseq [fut @shards]
    (let [[conn shard-state] @fut]
      (swap! shard-state assoc :disconnect true)
      (swap! conn #(do (when %
                         (ws/close %))
                       nil))))
  (let [{:keys [discljord.specs/url discljord.connections.specs/shard-count
                discljord.connections.specs/session-start-limit]}
        (get-websocket-gateway! (api-url "/gateway/bot") token)]
    (when (< (:remaining session-start-limit) shard-count)
      (a/put! comm-ch [:disconnect])
      (throw (RuntimeException. "Attempted to re-shard a bot with no more session starts.")))
    (reset! shards (connect-shards! url token shard-count out-ch
                                    comm-ch
                                    :buffer-size buffer-size))))

(defn start-communication-loop!
  "Takes a vector of promises representing the atoms of websocket connections of the shards."
  [shards token ch out-ch comm-ch]
  (a/go-loop []
    (let [command (a/<! ch)]
      ;; Handle the communication command
      (try (apply handle-command! shards token out-ch comm-ch command)
           (catch Exception e
             (when *enable-logging*
               (log/error e "Exception in handle-command!"))))
      ;; Handle the rate limit
      (a/<! (a/timeout 500))
      (when-not (= command [:disconnect])
        (recur))))
  nil)
(s/fdef start-communication-loop!
  :args (s/cat :shards (ds/atom-of? (s/coll-of ::ds/promise))
               :token ::ds/token
               :event-channel ::ds/channel
               :out-channel ::ds/channel)
  :ret nil?)

(defn get-shard-from-guild
  [guild-id guild-count]
  (mod (bit-shift-right (Long. guild-id) 22) guild-count))
(s/fdef get-shard-from-guild
  :args (s/cat :guild-id ::ds/snowflake
               :guild-cound pos-int?)
  :ret int?)

(defmethod handle-command! :guild-request-members
  [shards token out-ch comm-ch command-type & {:keys [guild-id query limit]
                                               :or {query ""
                                                    limit 0}}]
  (assert guild-id "did not provide a guild id to guild-request-members")
  (let [shard-id (get-shard-from-guild guild-id (count @shards))
        [conn shard-state] @(nth @shards shard-id)
        msg (json/write-str {:op 8
                             :d {"guild_id" guild-id
                                 "query" query
                                 "limit" limit}})]
    (when (> (count msg) 4096)
      (throw (RuntimeException. "Attempting to send too large a message in guild-request-members")))
    (ws/send-msg @conn msg)))

(defmethod handle-command! :status-update
  [shards token out-ch comm-ch command-type & {:keys [idle-since activity status afk]
                                               :or {afk false
                                                    status "online"}}]
  (let [[conn shard-state] @(nth @shards 0)
        msg (json/write-str {:op 3
                             :d {"since" idle-since
                                 "game" activity
                                 "status" status
                                 "afk" afk}})]
    (when (> (count msg) 4096)
      (throw (RuntimeException. "Attempting to send too large a message in status-update")))
    (ws/send-msg @conn msg)))

(defmethod handle-command! :voice-state-update
  [shards token out-ch comm-ch command-type & {:keys [guild-id channel-id mute deaf]
                                               :or {mute false
                                                    deaf false}}]
  (assert guild-id "did not provide a guild id to voice-state-update")
  (let [shard-id (get-shard-from-guild guild-id (count @shards))
        [conn shard-state] @(nth @shards shard-id)
        msg (json/write-str {:op 4
                             :d {"guild_id" guild-id
                                 "channel_id" channel-id
                                 "self_mute" mute
                                 "self_deaf" deaf}})]
    (when (> (count msg) 4096)
      (throw (RuntimeException. "Attempting to send too large a message in voice-state-update")))
    (ws/send-msg @conn msg)))
