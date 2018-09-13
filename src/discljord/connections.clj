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
  (fn [event-type & event-data]
    event-type))

(defmethod handle-websocket-event :connect
  [_ & [conn token shard]]
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
  [_ & [conn token session-id seq]]
  (ws/send-msg @conn
               (json/write-str {"op" 6
                                "d" {"token" token
                                     "session_id" session-id
                                     "seq" seq}})))

(defmethod handle-websocket-event :disconnect
  [_ & [stop-code msg reconnect resume]]
  (case stop-code
    ;; ------------------------------------------------------------------
    ;; Discord stop codes

    ;; Unknown error, reconnect and resume
    ;; TODO: Get actual session id and seq
    4000 (do (log/info "Unknown error, reconnect and resume")
             (resume))
    ;; Invalid op code
    4001 (log/fatal "Invalid op code sent to Discord servers")
    ;; Invalid payload
    4002 (log/fatal "Invalid payload sent to Discord servers")
    ;; Not authenticated
    4003 (log/fatal "Sent data to Discord without authenticating")
    ;; Invalid token
    4004 (do (log/error "Invalid token sent to Discord servers")
             (throw (Exception. "Invalid token sent to Discord servers")))
    ;; Already authenticated
    4005 (log/fatal "Sent identify packet to Discord twice from same shard")
    ;; Invalid seq
    4007 (do (log/info "Sent invalid seq to Discord on resume, reconnect")
             (reconnect))
    ;; Rate limited
    4008 (log/fatal "Rate limited by Discord")
    ;; Session timed out, reconnect, don't resume
    4009 (do (log/info "Session timed out, reconnecting")
             (reconnect))
    ;; Invalid shard
    4010 (log/fatal "Invalid shard set to discord servers")
    ;; Sharding Required
    ;; NOTE(Joshua): This should never happen, unless discord sends
    ;;               this as the bot joins more servers. If so, then
    ;;               make this cause a full disconnect and reconnect
    ;;               of the whole bot, not just this shard.
    4011 (log/fatal "Bot requires sharding")

    ;; ---------------------------------------------------------------
    ;; Normal stop codes

    ;; End of file
    1006 (do (log/info "Disconnected from Discord by server, reconnecting")
             (reconnect))

    ;; Closed by client
    1000 (log/info "Disconnected from Discord by client")
    1001 (log/info "Disconnected from Discord by client")

    ;; Unkown stop code, reconnect?
    (do (log/warn (str "Unknown stop code "
                       stop-code
                       " encountered with message:\n\t"
                       msg)))))

(defmethod handle-websocket-event :command
  [_ & [op data reconnect resume heartbeat ack connected shard-state]]
  (case op
    ;; Heartbeat payload
    1 (when @connected
        (swap! shard-state assoc :seq data)
        (heartbeat))
    ;; Reconnect payload
    7 (reconnect)
    ;; Invalid session payload
    9 (if data
        (resume)
        (reconnect))
    ;; Hello payload
    10 (let [interval (get data :heartbeat-interval)]
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
               (recur)))))
    ;; Heartbeat ACK
    11 (reset! ack true)
    ;; Other payload
    (do (log/error "Recieved an unhandled payload from Discord. op code" op "with data" data))))

;; TODO: make this update the shard state for everything
(defmethod handle-websocket-event :event
  [_ & [event-type data shard-state out-ch]]
  (case event-type
    :ready (let [session-id (:session-id data)]
             (swap! shard-state assoc :session-id session-id))
    nil)
  (a/go (a/>! out-ch [(json-keyword event-type) data])))

(defn start-event-loop
  "Starts a go loop which takes events from the channel and dispatches them
  via multimethod."
  [ch]
  (a/go-loop []
    (try (apply handle-websocket-event (a/<! ch))
         (catch Exception e
           (log/error e "Exception caught from handle-websocket-event")))
    (recur)))

(defn connect-shard
  "Takes a gateway URL and a bot token, creates a websocket connected to
  Discord's servers, and returns a function of no arguments which disconnects it"
  [url token shard-id shard-count out-ch]
  (let [event-ch (a/chan 100)
        conn (atom nil)]
    (reconnect-websocket url token conn event-ch [shard-id shard-count] out-ch)
    (start-event-loop event-ch)
    conn))


(defn connect-bot
  "Creates a connection process which will handle the services granted by
  Discord which interact over websocket.

  Takes a token for the bot, and a channel on which all events from Discord
  will be sent back across.

  Returns a channel used to communicate with the process and send packets to
  Discord."
  [token out-ch]
  )
