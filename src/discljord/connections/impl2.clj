(ns discljord.connections.impl2
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [discljord.http :refer (gateway-url)]
   [discljord.util :refer (json-keyword clean-json-input)]
   [gniazdo.core :as ws]
   [org.httpkit.client :as http]
   [taoensso.timbre :as log])
  (:import
   (org.eclipse.jetty.websocket.client
    WebSocketClient)
   (org.eclipse.jetty.util.ssl
    SslContextFactory)))

(def buffer-size
  "The suggested size of a buffer, namely 4MiB"
  4194304)

(defmulti handle-websocket-event
  "Updates a shard based on shard events. Takes a shard and a shard event vector
  and returns a map of the new state of the shard and zero or more events to
  process."
  (fn [shard [event-type & args]]
    event-type))

(def new-session-stop-code?
  "Set of stop codes after which a resume isn't possible"
  #{4003 4004 4007 4009})

(defn should-resume?
  "Returns if a shard should try to resume"
  [shard]
  (when (:stop-code shard)
    (and (not (new-session-stop-code? (:stop-code shard)))
         (:seq shard)
         (:session-id shard))))

(defmethod handle-websocket-event :connect
  [shard [_]]
  ;; This one also needs to have handling for creating and sending the identify packet
  {:shard shard
   :effects [(if (should-resume? shard)
               [:resume]
               [:identify])]})

(def ^:dynamic *stop-on-fatal-code*
  "Boolean which tells discljord to disconnect the entire bot when running into
  fatal stop codes"
  false)

(def fatal-code?
  "Set of stop codes which after recieving, discljord will disconnect all shards"
  #{4001 4002 4003 4004 4005 4008 4010})

(def re-shard-stop-code
  "Stop code which Discord will send when the bot needs to be re-sharded"
  4011)

(defmethod handle-websocket-event :disconnect
  [shard [_ stop-code msg]]
  {:shard (assoc shard
                 :stop-code stop-code
                 :disconnect-msg msg)
   :effects [(if-not (= re-shard-stop-code stop-code)
               (if-not (and *stop-on-fatal-code*
                            (fatal-code? stop-code))
                 [:reconnect]
                 [:disconnect])
               [:re-shard])]})

(defmethod handle-websocket-event :error
  [shard [_ err]]
  {:shard shard
   :effects [[:error err]]})

(def ^:private payloads
  {0 :event-dispatch
   1 :heartbeat
   7 :reconnect
   9 :invalid-session
   10 :hello
   11 :heartbeat-ack})

(defmulti handle-payload
  "Take a shard and a message payload and return a map of a new shard and zero
  or more events to be processed by the bot."
  (fn [shard msg]
    (payloads (:op msg))))

(defmethod handle-websocket-event :message
  [shard [_ msg]]
  (handle-payload shard (clean-json-input (json/read-str msg))))

(defmulti handle-discord-event
  "Takes a discord event and returns a vector of effects to process"
  (fn [event-type event]
    event-type))

(defmethod handle-discord-event :default
  [event-type event]
  [[:send-discord-event event-type event]])

(defmethod handle-payload :event-dispatch
  [shard {:keys [d t s] :as msg}]
  {:shard (assoc shard :seq s)
   :effects (handle-discord-event (json-keyword t) d)})

(defmethod handle-payload :heartbeat
  [shard msg]
  {:shard shard
   :effects [[:send-heartbeat]]})

(defmethod handle-payload :reconnect
  [shard {d :d}]
  {:shard shard
   :effects [[:reconnect]]})

(defmethod handle-payload :invalid-session
  [shard {d :d}]
  {:shard (assoc (dissoc shard
                         :session-id
                         :seq)
                 :invalid-session true)
   :effects [[:reconnect]]})

(defmethod handle-payload :hello
  [shard {{:keys [heartbeat-interval]} :d}]
  {:shard shard
   :effects [[:start-heartbeat heartbeat-interval]]})

(defmethod handle-payload :heartbeat-ack
  [shard msg]
  {:shard (assoc shard :ack true)
   :effects []})

(defn connect-websocket!
  "Connect a websocket with the given buffer size to the url and put all events
  onto the event-ch.

  Events are represented as vectors with a keyword for the event type and then
  event data as the rest of the vector based on the type of event.

  Connection events are of type :connect and have no additional data.
  Disconnection events are of type :disconnect and have a stop code and string
  message.
  Error events are of type :error and have an exception.
  Message events are of type :message and have the string message."
  [buffer-size url event-ch]
  (let [client (WebSocketClient. (doto (SslContextFactory.)
                                   (.setEndpointIdentificationAlgorithm "HTTPS")))]
    (doto (.getPolicy client)
      (.setMaxTextMessageSize buffer-size)
      (.setMaxBinaryMessageSize buffer-size))
    (doto client
      (.setMaxTextMessageBufferSize buffer-size)
      (.setMaxBinaryMessageBufferSize buffer-size)
      (.start))
    (ws/connect
        url
      :client client
      :on-connect (fn [_]
                    (log/debug "Websocket connected")
                    (a/put! event-ch [:connect]))
      :on-close (fn [stop-code msg]
                  (log/debug (str "Websocket closed with code: " stop-code " and message: " msg))
                  (a/put! event-ch [:disconnect stop-code msg]))
      :on-error (fn [err]
                  (log/warn "Websocket errored" err)
                  (a/put! event-ch [:error err]))
      :on-receive (fn [msg]
                    (log/trace (str "Websocket recieved message: " msg))
                    (a/put! event-ch [:message msg])))))

(defmulti handle-shard-fx
  ""
  (fn [heartbeat-ch output-events url token shard [event-type & args]]
    event-type))

(defn connect-shard!
  "Creates a process which will handle a shard"
  [shard-id shard-count url token output-events]
  (let [event-ch (a/chan 100)
        communication-ch (a/chan 100)]
    (a/go-loop [shard {:id shard-id
                       :event-ch event-ch
                       :websocket (connect-websocket! buffer-size url event-ch)
                       :token token
                       :count shard-count}]
      (when shard
        (let [{:keys [event-ch websocket heartbeat-ch]
               :or {heartbeat-ch (a/chan)}} shard]
          (a/alt!
            event-ch ([event]
                      (let [{:keys [shard effects]} (handle-websocket-event shard event)
                            shard (reduce (partial handle-shard-fx heartbeat-ch output-events url token)
                                          shard effects)]
                        (recur shard)))
            heartbeat-ch (if (:ack shard)
                           (do (log/trace (str "Sending heartbeat payload on shard " shard-id))
                               (ws/send-msg websocket
                                            (json/write-str {:op 1
                                                             :d (:seq shard)}))
                               (recur (dissoc shard :ack)))
                           (let [event-ch (a/chan 100)]
                             (log/info (str "Reconnecting due to zombie heartbeat on shard " shard-id))
                             (a/close! heartbeat-ch)
                             (recur (assoc (dissoc shard :heartbeat-ch)
                                           :websocket (connect-websocket! buffer-size
                                                                          url
                                                                          event-ch)
                                           :event-ch event-ch))))
            communication-ch ([value]
                              (log/debug (str "Recieved communication value " value " on shard " shard-id))
                              (if-not (= value :disconnect)
                                (do
                                  ;; TODO(Joshua): Send a message over the websocket
                                  (log/trace "Sending a message over the websocket")
                                  (recur shard))
                                (do (when heartbeat-ch
                                      (a/close! heartbeat-ch))
                                    (ws/close websocket)
                                    (log/info (str "Disconnecting shard "
                                                   shard-id
                                                   " and closing connection")))))))))
    communication-ch))

(defn get-websocket-gateway!
  "Gets the shard count and websocket endpoint from Discord's API."
  [url token]
  (if-let [result
           (try
             (when-let [response (:body @(http/get url
                                                   {:headers
                                                    {"Authorization" token}}))]
               (when-let [json-body (clean-json-input (json/read-str response))]
                 {:url (:url json-body)
                  :shard-count (:shards json-body)
                  :session-start-limit (:session-start-limit json-body)}))
             (catch Exception e
               (log/error e "Failed to get websocket gateway")
               nil))]
    (when (:url result)
      result)))

;; TODO(Joshua): Change this to be creating a set of shards and then stepping
;; each of them in sequence
(defn connect-bot!
  [output-events token]
  (let [{:keys [shard-count session-start-limit url] :as gateway} (get-websocket-gateway! gateway-url token)]
    (log/info (str "Connecting bot to gateway " gateway))
    (when (> (:remaining session-start-limit) shard-count)
      (a/go
        (let [chs (vec
                   (for [id (range shard-count)]
                     (a/go
                       (a/<! (a/timeout (* 5000 id)))
                       ;; TODO(Joshua): Make sure that this doesn't keep connecting shards if
                       ;; we get an event which requires disconnecting all the shards (like a
                       ;; re-shard event)
                       (log/info (str "Starting shard " id))
                       (connect-shard! id shard-count url token output-events))))
              chs-vec (volatile! (transient []))]
          (doseq [ch chs]
            (vswap! chs-vec conj! (a/<! ch)))
          (persistent! @chs-vec))))))

(defmethod handle-shard-fx :start-heartbeat
  [heartbeat-ch output-events url token shard [_ heartbeat-interval]]
  (let [heartbeat-ch (a/chan (a/sliding-buffer 1))]
    (log/debug (str "Starting a heartbeat with interval " heartbeat-interval " on shard " (:id shard)))
    (a/put! heartbeat-ch :heartbeat)
    (a/go-loop []
      (a/<! (a/timeout heartbeat-interval))
      (when (a/>! heartbeat-ch :heartbeat)
        (log/trace (str "Requesting heartbeat on shard " (:id shard)))
        (recur)))
    (assoc shard
           :heartbeat-ch heartbeat-ch
           :ack true)))

(defmethod handle-shard-fx :send-heartbeat
  [heartbeat-ch output-events url token shard event]
  (when heartbeat-ch
    (log/trace "Responding to requested heartbeat signal")
    (a/put! heartbeat-ch :heartbeat))
  shard)

(defmethod handle-shard-fx :identify
  [heartbeat-ch output-events url token shard event]
  (log/debug (str "Sending identify payload for shard " (:id shard)))
  (ws/send-msg (:websocket shard)
               (json/write-str {:op 2
                                :d {:token (:token shard)
                                    :properties {"$os" "linux"
                                                 "$browser" "discljord"
                                                 "$device" "discljord"}
                                    :compress false
                                    :large_threshold 50
                                    :shard [(:id shard) (:count shard)]}}))
  shard)

(defmethod handle-shard-fx :resume
  [heartbeat-ch output-events url token shard event]
  (log/debug (str "Sending resume payload for shard " (:id shard)))
  (let [event-ch (a/chan 100)
        shard (assoc shard :websocket (connect-websocket! buffer-size url event-ch))]
    (ws/send-msg (:websocket shard)
                 (json/write-str {:op 6
                                  :d {:token (:token shard)
                                      :session_id (:session-id shard)
                                      :seq (:seq shard)}}))
    shard))

;; TODO(Joshua): Make this actually send an event to the controlling process and kill off this shard
(defmethod handle-shard-fx :reconnect
  [heartbeat-ch output-events url token shard event]
  (let [event-ch (a/chan 100)]
    (when (:invalid-session shard)
      (log/warn (str "Got invalid session payload, disconnecting shard " (:id shard))))
    (when (:stop-code shard)
      (log/debug (str "Shard " (:id shard)
                      " has disconnected with stop-code "
                      (:stop-code shard) " and message \"" (:disconnect-msg shard) "\"")))
    (log/debug (str "Reconnecting shard " (:id shard)))
    (assoc (dissoc shard
                   :invalid-session
                   :stop-code
                   :disconnect-msg)
           :websocket (connect-websocket! buffer-size url event-ch)
           :event-ch event-ch)))

;; TODO(Joshua): Kill off this shard and send an event to re-shard the entire process
(defmethod handle-shard-fx :re-shard
  [heartbeat-ch output-events url token shard event]
  nil)

(defmethod handle-shard-fx :error
  [heartbeat-ch output-events url token shard [_ err]]
  (log/error err (str "Error encountered on shard " (:id shard)))
  shard)

(defmethod handle-shard-fx :send-discord-event
  [heartbeat-ch url token shard [_ event-type event]]
  (log/trace (str "Shard " (:id shard) " recieved discord event: " event))
  (a/put! output-events event)
  shard)

(defonce ^{:private true
           :doc "A map from bot tokens to the bot that is active for that token"}
  bot-map (atom {}))

(comment
  (def communication-chan (a/chan 100))
  (def events-chan (a/chan 100))
  (a/go-loop []
    (let [event (a/<! events-chan)]
      (clojure.pprint/pprint event)
      (when-not (= event ::stop-events)
        (recur)))))
