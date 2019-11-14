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
  (fn [heartbeat-ch url token shard [event-type & args]]
    event-type))

(defn step-shard!
  "Takes a shard and steps it through a single event, either a heartbeat, a
  websocket event, or a communication event. Any shard-specific side-effects
  which need to happen will be performed as a part of this method, including
  sending heartbeats, reconnecting the websocket, sending messages over the
  socket, etc.

  Takes a shard, the url to connect the websocket to, the token for the bot, and
  a channel for communicating with the shard.

  Returns a channel which will have the next state of the shard and any bot-wide
  effects put onto it after completing the step in the form of a map with the
  keys :shard and :effects."
  [{:keys [event-ch websocket heartbeat-ch communication-ch] :or {heartbeat-ch (a/chan)} :as shard}
   url token]
  (a/go
    (a/alt!
      heartbeat-ch (if (:ack shard)
                     (do (log/trace (str "Sending heartbeat payload on shard " (:id shard)))
                         (ws/send-msg websocket
                                      (json/write-str {:op 1
                                                       :d (:seq shard)}))
                         {:shard (dissoc shard :ack)
                          :effects []})
                     (let [event-ch (a/chan 100)]
                       (try
                         (when websocket
                           (ws/close websocket))
                         (catch Exception e
                           (log/debug "Websocket failed to close during reconnect" e)))
                       (log/info (str "Reconnecting due to zombie heartbeat on shard " (:id shard)))
                       (a/close! heartbeat-ch)
                       {:shard (assoc (dissoc shard :heartbeat-ch)
                                      :websocket (connect-websocket! buffer-size url event-ch)
                                      :event-ch event-ch)
                        :effects []}))
      communication-ch ([[event-type & event-data]]
                        (log/debug (str "Recieved communication value " value " on shard " (:id shard)))
                        (case event-type
                          :connect (let [event-ch (a/chan 100)]
                                     (log/info (str "Connecting shard " (:id shard)))
                                     (a/close! heartbeat-ch)
                                     {:shard (assoc (dissoc shard :heartbeat-ch)
                                                    :websocket (connect-websocket! buffer-size url event-ch)
                                                    :event-ch event-ch)
                                      :effects []})
                          :disconnect (do
                                        (when heartbeat-ch
                                          (a/close! heartbeat-ch))
                                        (a/close! communication-ch)
                                        (ws/close websocket)
                                        (log/info (str "Disconnecting shard "
                                                       (:id shard)
                                                       " and closing connection"))
                                        {:shard nil
                                         :effects [[:disconnect]]})
                          (do
                            ;; TODO(Joshua): Send a message over the websocket
                            (log/trace "Sending a message over the websocket")
                            {:shard shard
                             :effects []}))))))
      event-ch ([event]
                (let [{:keys [shard effects]} (handle-websocket-event shard event)
                      shard-map (reduce
                                 (fn [{:keys [shard effects]} new-effect]
                                   (let [old-effects effects
                                         {:keys [shard effects]}
                                         (handle-shard-fx heartbeat-ch url token shard new-effect)
                                         new-effects (vec (concat old-effects effects))]
                                     {:shard shard
                                      :effects new-effects}))
                                 {:shard shard
                                  :effects []}
                                 effects)]
                  shard-map))

(defn connect-shard!
  "Creates a process which will handle a shard.

  Takes the id of the shard, the number of shards which are being started, a url
  to connect the websocket to, the token of the bot, and a channel on which to
  output events.

  Returns a channel on which to communicate with the shard."
  [shard-id shard-count url token]
  (let [event-ch (a/chan 100)
        communication-ch (a/chan 100)]
    (a/go-loop [shard {:id shard-id
                       :event-ch event-ch
                       :websocket (connect-websocket! buffer-size url event-ch)
                       :token token
                       :count shard-count
                       :communication-ch communication-ch}]
      (when shard
        (let [{:keys [shard effects]}
              (a/<! (step-shard! shard url token))]
          (doseq [effect effects]
            (prn effect))
          (recur shard))))
    communication-ch))

(defn get-websocket-gateway!
  "Gets the shard count and websocket endpoint from Discord's API.

  Takes the url of the gateway and the token of the bot.

  Returns a map with the keys :url, :shard-count, and :session-start limit, or
  nil in the case of an error."
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

(defn make-shard
  "Creates a new shard with the given id, shard count, and token."
  [shard-id shard-count token]
  {:id shard-id
   :count shard-count
   :event-ch (a/chan 100)
   :token token
   :communication-ch (a/chan 100)})

(defn after-timeout
  "Calls the function of no arguments after the given timeout. Returns a channel
  which will have the return value of the function put on it."
  [f timeout]
  (a/go (a/<! (a/timeout timeout))
        (f)))

(defmulti handle-bot-fx
  "Handles a bot-level side effect triggered by a shard. This may be a re-sharding
  event, a discord event sent to the user, a disconnect event, etc.

  Takes a place to output events to the library user, the channels with which to
  communicate with the shards, the shard this effect came from, and the effect."
  (fn [output-ch communication-chs shard [effect-type & effect-data]]
    effect-type))

(comment
  ;; NOTE(Joshua): So it seems like potentially the best structure for connect
  ;; bot is to have it have a loop in which it does alts to respond to events
  ;; off of whichever shard yields one first.

  ;; NOTE(Joshua): The create-shard! function should create a shard, and then
  ;; when the time is up send a message on the communcation-ch to actually make
  ;; the connection. The step-shard! will make sure that if the shard hasn't
  ;; connected yet it will connect when that message is sent.

  ;; Create a number of shards, each with a different amount of time before it
  ;; starts
  (let [shards (mapv #(make-shard % shard-count token) (range shard-count))
        ;; Fetch the communication channels from each
        communication-chs (map :communication-ch shards)]
    ;; For each shard, tell it to start after a given amount of time
    (doseq [{:keys [id communication-ch]} shards]
      (after-timeout #(a/put! communication-ch [:connect]) (* id 5000)))
    (a/go-loop [shards shards]
      ;; Wait for one of the shards to finish its step
      (let [[{:keys [shard effects]} _] (a/alts! shards)]
        (a/go
          ;; Do any effects here so that they won't keep any of the shards waiting
          (reduce (partial handle-bot-fx communication-chs) shard effects)
          )
        ;; Start again with the next step on that shard
        (recur (assoc shards (:id shard)
                      (step-shard! shard url token))))))
  )

;; TODO(Joshua): Change this to be creating a set of shards and then stepping
;; each of them in sequence
(defn connect-bot!
  ""
  [output-events token]
  (let [{:keys [shard-count session-start-limit url] :as gateway} (get-websocket-gateway! gateway-url token)]
    (log/info (str "Connecting bot to gateway " gateway))
    (when (and gateway (> (:remaining session-start-limit) shard-count))
      (a/go
        (let [chs (vec
                   (for [id (range shard-count)]
                     (a/go
                       (a/<! (a/timeout (* 5000 id)))
                       ;; TODO(Joshua): Make sure that this doesn't keep connecting shards if
                       ;; we get an event which requires disconnecting all the shards (like a
                       ;; re-shard event)
                       (log/info (str "Starting shard " id))
                       (connect-shard! id shard-count url token))))
              chs-vec (volatile! (transient []))]
          (doseq [ch chs]
            (vswap! chs-vec conj! (a/<! ch)))
          (persistent! @chs-vec))))))

(defmethod handle-shard-fx :start-heartbeat
  [heartbeat-ch url token shard [_ heartbeat-interval]]
  (let [heartbeat-ch (a/chan (a/sliding-buffer 1))]
    (log/debug (str "Starting a heartbeat with interval " heartbeat-interval " on shard " (:id shard)))
    (a/put! heartbeat-ch :heartbeat)
    (a/go-loop []
      (a/<! (a/timeout heartbeat-interval))
      (when (a/>! heartbeat-ch :heartbeat)
        (log/trace (str "Requesting heartbeat on shard " (:id shard)))
        (recur)))
    {:shard (assoc shard
                   :heartbeat-ch heartbeat-ch
                   :ack true)
     :effects []}))

(defmethod handle-shard-fx :send-heartbeat
  [heartbeat-ch url token shard event]
  (when heartbeat-ch
    (log/trace "Responding to requested heartbeat signal")
    (a/put! heartbeat-ch :heartbeat))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx :identify
  [heartbeat-ch url token shard event]
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
  {:shard shard
   :effects []})

(defmethod handle-shard-fx :resume
  [heartbeat-ch url token shard event]
  (log/debug (str "Sending resume payload for shard " (:id shard)))
  (let [event-ch (a/chan 100)
        shard (assoc shard :websocket (connect-websocket! buffer-size url event-ch))]
    (ws/send-msg (:websocket shard)
                 (json/write-str {:op 6
                                  :d {:token (:token shard)
                                      :session_id (:session-id shard)
                                      :seq (:seq shard)}}))
    {:shard shard
     :effects []}))

;; TODO(Joshua): Make this actually send an event to the controlling process and kill off this shard
(defmethod handle-shard-fx :reconnect
  [heartbeat-ch url token shard event]
  (let [event-ch (a/chan 100)]
    (when (:invalid-session shard)
      (log/warn (str "Got invalid session payload, disconnecting shard " (:id shard))))
    (when (:stop-code shard)
      (log/debug (str "Shard " (:id shard)
                      " has disconnected with stop-code "
                      (:stop-code shard) " and message \"" (:disconnect-msg shard) "\"")))
    (log/debug (str "Reconnecting shard " (:id shard)))
    {:shard (assoc (dissoc shard
                           :invalid-session
                           :stop-code
                           :disconnect-msg)
                   :websocket (connect-websocket! buffer-size url event-ch)
                   :event-ch event-ch)
     :effects []}))

;; TODO(Joshua): Kill off this shard and send an event to re-shard the entire process
(defmethod handle-shard-fx :re-shard
  [heartbeat-ch url token shard event]
  (ws/close (:websocket shard))
  {:shard nil
   :effects [[:re-shard]]})

(defmethod handle-shard-fx :error
  [heartbeat-ch url token shard [_ err]]
  (log/error err (str "Error encountered on shard " (:id shard)))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx :send-discord-event
  [heartbeat-ch url token shard [_ event-type event]]
  (log/trace (str "Shard " (:id shard) " recieved discord event: " event))
  {:shard shard
   :effects [[:discord-event event-type event]]})

;; (defonce ^{:private true
;;            :doc "A map from bot tokens to the bot that is active for that token"}
;;   bot-map (atom {}))
