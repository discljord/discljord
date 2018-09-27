(ns discljord.connections
  (:use com.rpl.specter)
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.util :refer [bot-token json-keyword clean-json-input]])
  (:import [org.eclipse.jetty
            websocket.client.WebSocketClient
            util.ssl.SslContextFactory]))


(defn get-websocket-gateway!
  "Gets the gateway from Discord's API, including the number of shards recommended
  for your bot, and the websocket URL to connect to."
  [url token]
  (if-let [result
           (try
             (let [response (json/read-str
                             (:body @(http/get url
                                               {:headers
                                                {"Authorization" token}})))]
               {::ds/url (response "url")
                ::ds/shard-count (response "shards")})
             (catch Exception e
               nil))]
    (when (::ds/url result)
      result)))
(s/fdef get-websocket-gateway!
  :args (s/cat :url ::ds/url :token ::ds/token)
  :ret (s/nilable ::ds/gateway))

;; TODO(Joshua): Change this to make sure that it handles retries
(defn reconnect-websocket!
  "Takes a websocket connection atom and additional connection information,
  and reconnects a websocket, with options for resume or not."
  [url token conn ch shard out-ch & {:keys [init-shard-state
                                            buffer-size]}]
  (log/debug (str "Connecting shard " shard))
  (let [ack (atom false)
        connected (atom false)
        shard-state (atom (assoc (or init-shard-state
                                     {:session-id nil
                                      :seq nil
                                      :buffer-size (or buffer-size
                                                       100000)
                                      :retries 0
                                      :max-retries 10
                                      :max-connection-retries 10})
                                 :disconnect false))
        client (WebSocketClient. (SslContextFactory.))
        buffer-size (:buffer-size @shard-state)]
    (.setMaxTextMessageSize (.getPolicy client) buffer-size)
    (.setMaxBinaryMessageSize (.getPolicy client) buffer-size)
    (.setMaxTextMessageBufferSize client buffer-size)
    (.setMaxBinaryMessageBufferSize client buffer-size)
    (.start client)
    (a/thread
      (reset! conn
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
                                                           %&)))
                                               #(do
                                                  (ws/close @conn)
                                                  (fn []
                                                    (apply reconnect-websocket! url token conn
                                                           ch shard out-ch
                                                           :init-shard-state @shard-state
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
                                                   %&)
                                           #(apply reconnect-websocket! url token conn
                                                   ch shard out-ch
                                                   :init-shard-state @shard-state
                                                   %&)]))
                             :on-error
                             (fn [err]
                               (log/error err "Error caught on websocket")))
                           (catch Exception e
                             (log/error e "Exception while trying to connect websocket to Discord")
                             nil))]
                  (if connection
                    connection
                    (when (> (:max-connection-retries @shard-state) retries)
                      (recur (inc retries))))))))
    shard-state))
(s/fdef reconnect-websocket!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :connection (ds/atom-of? ::ds/connection)
               :event-channel ::ds/channel
               :shard (s/tuple ::ds/shard-id ::ds/shard-count)
               :out-channel ::ds/channel
               :keyword-args (s/keys* :opt-un [::ds/init-shard-state
                                               ::ds/buffer-size]))
  :ret (ds/atom-of? ::ds/shard-state))

(defmulti handle-websocket-event!
  "Handles events sent from discord over the websocket.

  Takes the channel to send events out of the process, a
  keyword event type, and any number of event data arguments."
  (fn [out-ch event-type & event-data]
    event-type))

(defmethod handle-websocket-event! :connect
  [out-ch _ & [conn token shard]]
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
  [out-ch _ & [conn token session-id seq]]
  (ws/send-msg @conn
               (json/write-str {"op" 6
                                "d" {"token" token
                                     "session_id" session-id
                                     "seq" seq}})))

(defmulti handle-disconnect!
  "Handles the disconnection process for different stop codes.

  Takes an integer stop code, the message, a function to reconnect
  the shard, and a function to reconnect the shard with resume."
  (fn [socket-state stop-code msg reconnect resume]
    stop-code))

(defmethod handle-disconnect! :default
  [socket-state stop-code msg reconnect resume]
  (log/debug (str "Stop code "
                  stop-code
                  " encountered with message:\n\t"
                  msg))
  ;; NOTE(Joshua): Not sure if this should do a reconnect or a resume
  (when-not (or (:disconnect @socket-state)
                (> (inc (:retries @socket-state))
                   (:max-retries @socket-state)))
    (log/debug "Reconnecting")
    (transform [ATOM :retries] inc socket-state)
    (reconnect)))

(defmethod handle-disconnect! 4000
  [socket-state stop-code msg reconnect resume]
  (log/debug "Unknown error, reconnect and resume")
  (resume))

(defmethod handle-disconnect! 4001
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Invalid op code sent to Discord servers"))

(defmethod handle-disconnect! 4002
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Invalid payload sent to Discord servers"))

(defmethod handle-disconnect! 4003
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Sent data to Discord without authenticating"))

(defmethod handle-disconnect! 4004
  [socket-state stop-code msg reconnect resume]
  (log/error "Invalid token sent to Discord servers")
  (throw (Exception. "Invalid token sent to Discord servers")))

(defmethod handle-disconnect! 4005
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Sent identify packet to Discord twice from same shard"))

(defmethod handle-disconnect! 4007
  [socket-state stop-code msg reconnect resume]
  (log/debug "Sent invalid seq to Discord on resume, reconnect")
  (reconnect))

(defmethod handle-disconnect! 4008
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Rate limited by Discord"))

(defmethod handle-disconnect! 4009
  [socket-state stop-code msg reconnect resume]
  (log/debug "Session timed out, reconnecting")
  (reconnect))

(defmethod handle-disconnect! 4010
  [socket-state stop-code msg reconnect resume]
  (log/fatal "Invalid shard set to discord servers"))

(defmethod handle-disconnect! 4011
  [socket-state stop-code msg reconnect resume]
  ;; NOTE(Joshua): This should never happen, unless discord sends
  ;;               this as the bot joins more servers. If so, then
  ;;               make this cause a full disconnect and reconnect
  ;;               of the whole bot, not just this shard.
  (log/fatal "Bot requires sharding"))

(defmethod handle-disconnect! 1009
  [socket-state stop-code msg reconnect resume]
  (log/info "Websocket size wasn't big enough! Resuming with a bigger buffer.")
  (resume :buffer-size (+ (:buffer-size @socket-state)
                          100000)))

(defmethod handle-websocket-event! :disconnect
  [out-ch _ & [socket-state stop-code msg reconnect resume]]
  (log/debug "Shard disconnected from Discord")
  (a/put! out-ch [:shard-disconnect])
  (handle-disconnect! socket-state stop-code msg reconnect resume))

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
  (log/trace "Heartbeat requested by Discord")
  (when @connected
    (swap! shard-state assoc :seq data)
    (heartbeat)))

(defmethod handle-payload! 7
  [op data reconnect resume heartbeat ack connected shard-state]
  (log/debug "Reconnect payload sent from Discord")
  (reconnect))

(defmethod handle-payload! 9
  [op data reconnect resume heartbeat ack connected shard-state]
  (log/debug "Invalid session sent from Discord, reconnecting")
  (if data
    (resume)
    (reconnect)))

(defmethod handle-payload! 10
  [op data reconnect resume heartbeat ack connected shard-state]
  (let [interval (get data :heartbeat-interval)]
    (a/go-loop []
      ;; Send heartbeat
      (reset! ack false)
      (heartbeat)
      ;; Wait the interval
      (a/<! (a/timeout interval))
      ;; If there's no ack, disconnect and reconnect with resume
      (if-not @ack
        (resume)
        ;; Make this check to see if we've disconnected
        (when @connected
          (recur))))))

(defmethod handle-payload! 11
  [op data reconnect resume heartbeat ack connected shard-state]
  (reset! ack true))

(defmethod handle-payload! :default
  [op data reconnect resume heartbeat ack connected shard-state]
  (log/error "Recieved an unhandled payload from Discord. op code" op "with data" data))

(defmethod handle-websocket-event! :command
  [out-ch _ & [op data reconnect resume heartbeat ack connected shard-state]]
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
  [out-ch _ & [event-type data shard-state out-ch]]
  (handle-event event-type data shard-state out-ch)
  (a/put! out-ch [event-type data]))

(defn start-event-loop!
  "Starts a go loop which takes events from the channel and dispatches them
  via multimethod."
  [conn ch out-ch]
  (a/go-loop []
    (try (apply handle-websocket-event! out-ch (a/<! ch))
         (catch Exception e
           (log/error e "Exception caught from handle-websocket-event!")))
    (when @conn
      (recur)))
  nil)
(s/fdef start-event-loop!
  :args (s/cat :connection (ds/atom-of? ::ds/connection)
               :event-channel ::ds/channel
               :out-channel ::ds/channel)
  :ret nil?)

(defn connect-shard!
  "Takes a gateway URL and a bot token, creates a websocket connected to
  Discord's servers, and returns a function of no arguments which disconnects it"
  [url token shard-id shard-count out-ch & {:keys [buffer-size]}]
  (let [event-ch (a/chan 100)
        conn (atom nil)
        shard-state (reconnect-websocket! url token conn event-ch [shard-id shard-count] out-ch
                                          :buffer-size buffer-size)]
    (start-event-loop! conn event-ch out-ch)
    [conn shard-state]))
(s/fdef connect-shard!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :shard-id int?
               :shard-count pos-int?
               :out-channel ::ds/channel
               :keyword-args (s/keys* :opt-un [::ds/buffer-size]))
  :ret (s/tuple (ds/atom-of? ::ds/connection)
                (ds/atom-of? ::ds/shard-state)))

(defn connect-shards!
  "Calls connect-shard! once per shard in shard-count, and returns a sequence
  of futures of the return results."
  [url token shard-count out-ch & {:keys [buffer-size]}]
  ;; FIXME: This is going to break when there's a high enough shard count
  ;;        that the JVM can't handle having more threads doing this.
  (doall
   (for [shard-id (range shard-count)]
     (let [prom (promise)]
       (a/thread
         (Thread/sleep (* 5000 shard-id))
         (deliver prom (connect-shard! url token shard-id shard-count out-ch
                                       :buffer-size buffer-size)))
       prom))))
(s/fdef connect-shards!
  :args (s/cat :url ::ds/url
               :token ::ds/token
               :shard-count pos-int?
               :out-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::ds/buffer-size]))
  :ret (s/coll-of ::ds/future))

(defmulti handle-command!
  "Handles commands from the outside world.

  Takes a vector of futures containing atoms of shard connections,
  the channel used to send events out of the process, a keyword command type,
  and any number of command data arguments."
  (fn [shards out-ch command-type & command-data]
    command-type))

(defmethod handle-command! :default
  [shards out-ch command-type & command-data]
  nil)

(defmethod handle-command! :disconnect
  [shards out-ch command-type & command-data]
  (a/put! out-ch [:disconnect])
  (doseq [fut shards]
    (a/thread
      (let [[conn shard-state] @fut]
        (swap! conn #(do (when %
                           (ws/close %))
                         nil))
        (swap! shard-state assoc :disconnect true)))))

(defn start-communication-loop!
  "Takes a vector of futures representing the atoms of websocket connections of the shards."
  [shards ch out-ch]
  (a/go-loop []
    (let [command (a/<! ch)]
      ;; Handle the communication command
      (apply handle-command! shards out-ch command)
      ;; Handle the rate limit
      (a/<! (a/timeout 500))
      (when-not (= command [:disconnect])
        (recur))))
  nil)
(s/fdef start-communication-loop!
  :args (s/cat :shards (s/coll-of ::ds/future)
               :event-channel ::ds/channel
               :out-channel ::ds/channel)
  :ret nil?)

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
        {url ::ds/url
         shard-count ::ds/shard-count} (get-websocket-gateway! (api-url "/gateway/bot")
                                                               token)
        communication-chan (a/chan 100)
        shards (connect-shards! url token shard-count out-ch
                                :buffer-size buffer-size)]
    (a/put! out-ch [:connect])
    (start-communication-loop! shards communication-chan out-ch)
    communication-chan))
(s/fdef connect-bot!
  :args (s/cat :token ::ds/token :out-ch ::ds/channel
               :keyword-args (s/keys* :opt-un [::ds/buffer-size]))
  :ret ::ds/channel)

(defn disconnect-bot!
  "Takes the channel returned by connect-bot! and stops the connection."
  [connection-ch]
  (a/put! connection-ch [:disconnect])
  nil)
(s/fdef disconnect-bot!
  :args (s/cat :channel ::ds/channel)
  :ret nil?)
