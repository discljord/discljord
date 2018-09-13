(ns discljord.connections
  (:use com.rpl.specter)
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.stacktrace :refer [print-stack-trace]]))

(s/def ::url string?)
(s/def ::token string?)

(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::url ::shard-count]))

(defn append-api-suffix
  [url]
  (str url "?v=6&encoding=json"))
(s/fdef append-api-suffix
  :args (s/cat :url ::url)
  :ret ::url)

(defn api-url
  [gateway]
  (append-api-suffix (str "https://discordapp.com/api" gateway)))
(s/fdef api-url
  :args (s/cat :url ::url)
  :ret ::url)

(defn get-websocket-gateway!
  [url token]
  (if-let [result
           (try
             (let [response (json/read-str
                             (:body
                              @(http/get
                                url
                                {:headers {"Authorization" token}})))]
               {::url (response "url")
                ::shard-count (response "shards")})
                (catch Exception e
                  nil))]
    (when (::url result)
      result)))
(s/fdef get-websocket-gateway!
  :args (s/cat :url ::url :token ::token)
  :ret ::gateway)

(defn json-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef json-keyword
  :args (s/cat :string string?)
  :ret keyword?)

(defn clean-json-input
  [j]
  (cond
    (map? j) (->> j
                  (transform [MAP-KEYS] #(if (string? %)
                                           (json-keyword %)
                                           (clean-json-input %)))
                  (transform [MAP-VALS coll?] clean-json-input))
    (vector? j) (mapv clean-json-input j)
    :else j))

(defn reconnect-websocket
  "Takes a websocket connection atom and additional connection information,
  and reconnects a websocket, with options for resume or not."
  [url token conn ch shard out-ch & [init-shard-state]]
  (let [ack (atom false)
        connected (atom false)
        shard-state (atom (or init-shard-state
                              {:session-id nil
                               :seq nil}))]
    (reset! conn (ws/connect url
                   :on-connect
                   (fn [_]
                     ;; Put a connection event on the channel
                     (reset! connected true)
                     (a/go (a/>! out-ch [:shard-connect]))
                     (a/go (a/>! ch (if init-shard-state
                                      [:reconnect conn token
                                       (:session-id @shard-state)
                                       (:seq @shard-state)]
                                      [:connect conn token shard]))))
                   :on-receive
                   (fn [msg]
                     ;; Put a recieve event on the channel
                     (a/go
                       (let [msg (clean-json-input (json/read-str msg))
                             op (:op msg)
                             data (:d msg)]
                         (a/>! ch (if (= op 0)
                                    (let [new-seq (:s msg)
                                          msg-type (:t msg)]
                                      (swap! shard-state assoc :seq new-seq)
                                      [:event msg-type data shard-state out-ch])
                                    [:command op data
                                     #(do
                                        (ws/close @conn)
                                        (fn []
                                          (reconnect-websocket url token conn
                                                               ch shard out-ch)))
                                     #(do
                                        (ws/close @conn)
                                        (fn []
                                          (reconnect-websocket url token conn
                                                               ch shard out-ch
                                                               shard-state)))
                                     #(ws/send-msg @conn
                                                   (json/write-str
                                                    {"op" 1
                                                     "d" (:seq @shard-state)}))
                                     ack connected shard-state])))))
                   :on-close
                   (fn [stop-code msg]
                     ;; Put a disconnect event on the channel
                     (reset! connected false)
                     (a/go (a/>! ch [:disconnect stop-code msg
                                     #(reconnect-websocket url token conn
                                                           ch shard out-ch)
                                     #(reconnect-websocket url token conn
                                                           ch shard out-ch
                                                           shard-state)])))
                   :on-error
                   (fn [err]
                     (log/error err "Error caught on websocket"))))))

(defmulti handle-websocket-event
  "Handles events sent from discord over the websocket.
  Takes a vector of event type and event data, the websocket connection, and the events channel."
  (fn [out-ch event-type & event-data]
    event-type))

(defmethod handle-websocket-event :connect
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

(defmethod handle-websocket-event :reconnect
  [out-ch _ & [conn token session-id seq]]
  (ws/send-msg @conn
               (json/write-str {"op" 6
                                "d" {"token" token
                                     "session_id" session-id
                                     "seq" seq}})))

(defmulti handle-disconnect
  "Handles the disconnection process for different stop codes"
  (fn [stop-code msg reconnect resume]
    stop-code))

(defmethod handle-disconnect :default
  [stop-code msg reconnect resume]
  (log/warn (str "Unknown stop code "
                 stop-code
                 " encountered with message:\n\t"
                 msg)))

(defmethod handle-disconnect 4000
  [stop-code msg reconnect resume]
  (log/info "Unknown error, reconnect and resume")
  (resume))

(defmethod handle-disconnect 4001
  [stop-code msg reconnect resume]
  (log/fatal "Invalid op code sent to Discord servers"))

(defmethod handle-disconnect 4002
  [stop-code msg reconnect resume]
  (log/fatal "Invalid payload sent to Discord servers"))

(defmethod handle-disconnect 4003
  [stop-code msg reconnect resume]
  (log/fatal "Sent data to Discord without authenticating"))

(defmethod handle-disconnect 4004
  [stop-code msg reconnect resume]
  (log/error "Invalid token sent to Discord servers")
  (throw (Exception. "Invalid token sent to Discord servers")))

(defmethod handle-disconnect 4005
  [stop-code msg reconnect resume]
  (log/fatal "Sent identify packet to Discord twice from same shard"))

(defmethod handle-disconnect 4007
  [stop-code msg reconnect resume]
  (log/info "Sent invalid seq to Discord on resume, reconnect")
  (reconnect))

(defmethod handle-disconnect 4008
  [stop-code msg reconnect resume]
  (log/fatal "Rate limited by Discord"))

(defmethod handle-disconnect 4009
  [stop-code msg reconnect resume]
  (log/info "Session timed out, reconnecting")
  (reconnect))

(defmethod handle-disconnect 4010
  [stop-code msg reconnect resume]
  (log/fatal "Invalid shard set to discord servers"))

(defmethod handle-disconnect 4011
  [stop-code msg reconnect resume]
  ;; NOTE(Joshua): This should never happen, unless discord sends
  ;;               this as the bot joins more servers. If so, then
  ;;               make this cause a full disconnect and reconnect
  ;;               of the whole bot, not just this shard.
  (log/fatal "Bot requires sharding"))

(defmethod handle-disconnect 1006
  [stop-code msg reconnect resume]
  (log/info "Disconnected from Discord by server, reconnecting")
  (reconnect))

(defmethod handle-disconnect 1000
  [stop-code msg reconnect resume]
  (log/info "Disconnected from Discord by client"))

(defmethod handle-disconnect 1001
  [stop-code msg reconnect resume]
  (log/info "Disconnected from Discord by client"))

(defmethod handle-websocket-event :disconnect
  [out-ch _ & [stop-code msg reconnect resume]]
  (a/go (a/>! out-ch [:shard-disconnect]))
  (handle-disconnect stop-code msg reconnect resume))

(defmulti handle-payload
  "Handles a command payload sent from Discord"
  (fn [op data reconnect resume heartbeat ack connected shard-state]
    op))

(defmethod handle-payload 1
  [op data reconnect resume heartbeat ack connected shard-state]
  (when @connected
    (swap! shard-state assoc :seq data)
    (heartbeat)))

(defmethod handle-payload 7
  [op data reconnect resume heartbeat ack connected shard-state]
  (reconnect))

(defmethod handle-payload 9
  [op data reconnect resume heartbeat ack connected shard-state]
  (if data
    (resume)
    (reconnect)))

(defmethod handle-payload 10
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

(defmethod handle-payload 11
  [op data reconnect resume heartbeat ack connected shard-state]
  (reset! ack true))

(defmethod handle-payload :default
  [op data reconnect resume heartbeat ack connected shard-state]
  (log/error "Recieved an unhandled payload from Discord. op code" op "with data" data))

(defmethod handle-websocket-event :command
  [out-ch _ & [op data reconnect resume heartbeat ack connected shard-state]]
  (handle-payload op data reconnect resume heartbeat ack connected shard-state))

(defmulti handle-event
  "Handles event payloads sent from Discord in the context of the connection,
  before it gets to the regular event handlers"
  (fn [event-type data shard-state out-ch]
    event-type))

(defmethod handle-event :ready
  [event-type data shard-state out-ch]
  (let [session-id (:session-id data)]
    (swap! shard-state assoc :session-id session-id)))

(defmethod handle-event :default
  [event-type data shard-state out-ch]
  nil)

(defmethod handle-websocket-event :event
  [out-ch _ & [event-type data shard-state out-ch]]
  (handle-event event-type data shard-state out-ch)
  (a/go (a/>! out-ch [(json-keyword event-type) data])))

(defn start-event-loop
  "Starts a go loop which takes events from the channel and dispatches them
  via multimethod."
  [conn ch out-ch]
  (a/go-loop []
    (try (apply handle-websocket-event out-ch (a/<! ch))
         (catch Exception e
           (log/error e "Exception caught from handle-websocket-event")))
    (when @conn
      (recur))))

(defn connect-shard
  "Takes a gateway URL and a bot token, creates a websocket connected to
  Discord's servers, and returns a function of no arguments which disconnects it"
  [url token shard-id shard-count out-ch]
  (let [event-ch (a/chan 100)
        conn (atom nil)]
    (reconnect-websocket url token conn event-ch [shard-id shard-count] out-ch)
    (start-event-loop conn event-ch out-ch)
    conn))

(defn connect-shards
  [url token shard-count out-ch]
  ;; FIXME: This is going to break when there's a high enough shard count
  ;;        that the JVM can't handle having more threads doing this.
  (doall
   (for [shard-id (range shard-count)]
     (future
       (a/<!! (a/go (a/<! (a/timeout (* 5000 shard-id)))
                    (connect-shard url token shard-id shard-count out-ch)))))))

(defmulti handle-command
  "Handles commands from the outside world"
  (fn [shards out-ch command-type & command-data]
    command-type))

(defmethod handle-command :default
  [shards out-ch command-type & command-data]
  nil)

(defmethod handle-command :disconnect
  [shards out-ch command-type & command-data]
  (a/go (a/>! out-ch [:disconnect]))
  (doseq [fut shards]
    (a/thread
      (when @@fut
        (ws/close @@fut)))))

(defn start-communication-loop
  "Takes a vector of futures representing the atoms of websocket connections of the shards."
  [shards ch out-ch]
  (a/go-loop []
    (let [command (a/<! ch)]
      ;; Handle the communication command
      (apply handle-command shards out-ch command)
      ;; Handle the rate limit
      (a/<! (a/timeout 500))
      (when-not (= command [:disconnect])
        (recur)))))

(defn connect-bot
  "Creates a connection process which will handle the services granted by
  Discord which interact over websocket.

  Takes a token for the bot, and a channel on which all events from Discord
  will be sent back across.

  Returns a channel used to communicate with the process and send packets to
  Discord.

  Keep in mind that Discord sets a limit to how many shards can connect in a
  given period. This means that communication to Discord may be bounded based
  on which shard you use to talk to the server immediately after starting the bot."
  [token out-ch]
  (let [token (str "Bot " token)
        {::keys [url shard-count]} (get-websocket-gateway! (api-url "/gateway/bot")
                                                           token)
        communication-chan (a/chan 100)
        shards (connect-shards url token shard-count out-ch)]
    (a/go (a/>! out-ch [:connect]))
    (start-communication-loop shards communication-chan out-ch)
    communication-chan))
