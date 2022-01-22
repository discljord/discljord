(ns discljord.connections.impl
  "Implementation of websocket connections to Discord."
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [discljord.http :refer [gateway-url gateway-version]]
   [discljord.util :refer [json-keyword clean-json-input]]
   [gniazdo.core :as ws]
   [org.httpkit.client :as http])
  (:import
   (java.io
    ByteArrayOutputStream)
   (java.util.zip
    Inflater)
   (org.eclipse.jetty.websocket.client
    WebSocketClient)
   (org.eclipse.jetty.util.ssl
    SslContextFactory)))

(def buffer-size
  "The maximum size of the websocket buffer"
  (int Integer/MAX_VALUE))

(def byte-array-buffer-size
  "The size of the byte array to allocate for the decompression buffer"
  (int (* 1024 1024 1)))

(defmulti handle-websocket-event
  "Updates a `shard` based on shard events.
  Takes a `shard` and a shard event vector and returns a map of the new state of
  the shard and zero or more events to process."
  (fn [shard [event-type & args]]
    event-type))

(def new-session-stop-code?
  "Set of stop codes after which a resume isn't possible."
  #{4003 4004 4007 4009})

(defn should-resume?
  "Returns if a shard should try to resume."
  [shard]
  (log/trace "Testing if shard" (:id shard) "should resume:" shard)
  (and (not (new-session-stop-code? (:stop-code shard)))
       (:seq shard)
       (:session-id shard)
       (not (:unresumable shard))))

(defmethod handle-websocket-event :connect
  [shard [_]]
  {:shard shard
   :effects [(if (should-resume? shard)
               [:resume]
               [:identify])]})

(def ^:dynamic *stop-on-fatal-code*
  "Bind to to true to disconnect the entire bot after a fatal stop code."
  false)

(def fatal-code?
  "Set of stop codes which after recieving, discljord will disconnect all shards."
  #{4001 4002 4003 4004 4005 4008 4010})

(def user-error-code?
  "Set of stop codes which can only be received if there was user error."
  #{4013 4014})

(def re-shard-stop-code?
  "Stop codes which Discord will send when the bot needs to be re-sharded."
  #{4011})

(defmethod handle-websocket-event :disconnect
  [{:keys [websocket] :as shard} [_ stop-code msg]]
  (if shard
    {:shard (if stop-code
              (do
                (when websocket
                  (log/debug "Websocket was not closed during disconnect event, now closing")
                  (ws/close websocket 4000 "Closing before reconnect"))
                (assoc (dissoc shard :websocket)
                       :stop-code stop-code
                       :disconnect-msg msg))
              shard)
     :effects [(cond
                 (re-shard-stop-code? stop-code) [:re-shard]
                 (and *stop-on-fatal-code*
                      (fatal-code? stop-code))   [:disconnect-all]
                 (user-error-code? stop-code)  (do
                                                 (log/fatal (str "Received stop code " stop-code
                                                                 " which can only occur on user error."
                                                                 " Disconecting bot."))
                                                 [:disconnect-all])
                 :otherwise                      [:reconnect])]}
    {:shard nil
     :effects []}))

(defmethod handle-websocket-event :error
  [shard [_ err]]
  {:shard shard
   :effects [[:error err]]})

(defmethod handle-websocket-event :send-debug-effect
  [shard [_ & effects]]
  {:shard shard
   :effects (vec effects)})

(def ^:private payload-id->payload-key
  "Map from payload type ids to the payload keyword."
  {0 :event-dispatch
   1 :heartbeat
   7 :reconnect
   9 :invalid-session
   10 :hello
   11 :heartbeat-ack})

(defmulti handle-payload
  "Update a `shard` based on a message.
  Takes a `shard` and `msg` and returns a map with a :shard and an :effects
  vector."
  (fn [shard msg]
    (payload-id->payload-key (:op msg))))

(defmethod handle-websocket-event :message
  [shard [_ msg]]
  (handle-payload shard (clean-json-input (json/read-str msg))))

(defmulti handle-discord-event
  "Handles discord events for a specific shard, specifying effects."
  (fn [shard event-type event]
    event-type))

(defmethod handle-discord-event :default
  [shard event-type event]
  {:shard shard
   :effects [[:send-discord-event event-type event]]})

(defmethod handle-discord-event :ready
  [shard event-type event]
  {:shard (assoc (dissoc shard
                         :retries
                         :stop-code
                         :disconnect-msg
                         :invalid-session
                         :unresumable)
                 :session-id (:session-id event)
                 :ready true)
   :effects [[:send-discord-event event-type event]]})

(defmethod handle-discord-event :resumed
  [shard event-type event]
  {:shard (dissoc shard
                  :retries
                  :stop-code
                  :disconnect-msg
                  :invalid-session
                  :unresumable)
   :effects [[:send-discord-event event-type event]]})

(defmethod handle-payload :event-dispatch
  [shard {:keys [d t s] :as msg}]
  (handle-discord-event (assoc shard :seq s) (json-keyword t) d))

(defmethod handle-payload :heartbeat
  [shard msg]
  {:shard shard
   :effects [[:send-heartbeat]]})

(defmethod handle-payload :reconnect
  [shard {d :d}]
  {:shard shard
   :effects [[:disconnect]]})

(defmethod handle-payload :invalid-session
  [shard {d :d}]
  {:shard (assoc (dissoc shard
                         :session-id
                         :seq
                         :ready)
                 :invalid-session true
                 :unresumable (not d))
   :effects [[:disconnect]]})

(defmethod handle-payload :hello
  [shard {{:keys [heartbeat-interval]} :d}]
  {:shard shard
   :effects [[:start-heartbeat heartbeat-interval]]})

(defmethod handle-payload :heartbeat-ack
  [shard msg]
  {:shard (assoc shard :ack true)
   :effects []})

(defn connect-websocket!
  "Connect a websocket to the `url` that puts all events onto the `event-ch`.
  Events are represented as vectors with a keyword for the event type and then
  event data as the rest of the vector based on the type of event.

  | Type          | Data |
  |---------------+------|
  | `:connect`    | None.
  | `:disconnect` | Stop code, string message.
  | `:error`      | Error value.
  | `:message`    | String message."
  [buffer-size url event-ch compress]
  (log/debug "Starting websocket of size" buffer-size "at url" url)
  (let [url (str url
                 (when-not (str/ends-with? url "/") "/")
                 "?v=" gateway-version
                 "&encoding=json"
                 (when compress
                   "&compress=zlib-stream"))
        client (WebSocketClient. (doto (SslContextFactory.)
                                   (.setEndpointIdentificationAlgorithm "HTTPS")))
        inflater (Inflater.)
        out-buffer (byte-array byte-array-buffer-size)]
    (doto (.getPolicy client)
      (.setMaxTextMessageSize buffer-size)
      (.setMaxBinaryMessageSize buffer-size))
    (doto client
      (.start))
    (try (ws/connect
          url
          :client client
          ::ws/cleanup #(.stop client)
          :on-connect (fn [_]
                        (log/trace "Websocket connected")
                        (a/put! event-ch [:connect]))
          :on-close (fn [stop-code msg]
                      (log/debug "Websocket closed with code:" stop-code "and message:" msg)
                      (a/put! event-ch [:disconnect stop-code msg]))
          :on-error (fn [err]
                      (log/warn "Websocket errored" err)
                      (a/put! event-ch [:error err]))
          :on-receive (fn [msg]
                        (log/trace "Websocket received message:" msg)
                        (a/put! event-ch [:message msg]))
          :on-binary (fn [buf start len]
                       (.setInput inflater buf start len)
                       (let [acc (ByteArrayOutputStream.)
                             msg (loop [off start
                                        rem len]
                                   (if (pos? rem)
                                     (let [bytes-read (.inflate inflater out-buffer 0 byte-array-buffer-size)]
                                       (.write acc out-buffer 0 bytes-read)
                                       (recur (mod (+ off bytes-read)
                                                   (count buf))
                                              (- rem bytes-read)))
                                     (String. (.toByteArray acc) "UTF-8")))]
                         (log/trace "Websocket received binary message:" msg)
                         (a/put! event-ch [:message msg]))))
         (catch Exception e
           (.stop client)
           (throw (ex-info "Failed to connect a websocket" {} e))))))

(defmulti handle-shard-fx!
  "Processes an `event` on a given `shard` for side effects.
  Returns a map with the new :shard and bot-level :effects to process."
  (fn [heartbeat-ch url token shard event]
    (first event)))

(defmulti handle-shard-communication!
  "Processes a communication `event` on the given `shard` for side effects.
  Returns a map with the new :shard and bot-evel :effects to process."
  (fn [shard url event]
    (first event)))

(defmethod handle-shard-communication! :default
  [shard url event]
  (log/warn "Unknown communication event recieved on a shard" event)
  {:shard shard
   :effects []})

(defmethod handle-shard-communication! :guild-request-members
  [{:keys [heartbeat-ch event-ch] :as shard}
   url [_ & {:keys [guild-id query limit] :or {query "" limit 0}}]]
  (when guild-id
    (let [msg (json/write-str {:op 8
                               :d {"guild_id" guild-id
                                   "query" query
                                   "limit" limit}})]
      (if-not (> (count msg) 4096)
        (do
          (log/trace "Sending message to retrieve guild members from guild"
                     guild-id "over shard" (:id shard)
                     "with query" query)
          (ws/send-msg (:websocket shard)
                       msg))
        (log/error "Message for guild-request-members was too large on shard" (:id shard)
                   "Check to make sure that your query is of a reasonable size."))))
  {:shard shard
   :effects []})

(defmethod handle-shard-communication! :status-update
  [{:keys [heartbeat-ch event-ch] :as shard}
   url [_ & {:keys [idle-since activity status afk]
             :or {afk false
                  status "online"}}]]
  (let [msg (json/write-str {:op 3
                              :d {"since" idle-since
                                  "game" activity
                                  "status" status
                                  "afk" afk}})]
    (if-not (> (count msg) 4096)
      (do
        (log/trace "Sending status update over shard" (:id shard))
        (ws/send-msg (:websocket shard)
                     msg))
      (log/error "Message for status-update was too large."
                 "Use create-activity to create a valid activity"
                 "and select a reasonably-sized status message.")))
  {:shard shard
   :effects []})

(defmethod handle-shard-communication! :voice-state-update
  [{:keys [heartbeat-ch event-ch] :as shard}
   url [_ & {:keys [guild-id channel-id mute deaf]
             :or {mute false
                  deaf false}}]]
  (let [msg (json/write-str {:op 4
                             :d {"guild_id" guild-id
                                 "channel_id" channel-id
                                 "self_mute" mute
                                 "self_deaf" deaf}})]
    (if-not (> (count msg) 4096)
      (do
        (log/trace "Sending voice-state-update over shard" (:id shard))
        (ws/send-msg (:websocket shard)
                     msg))
      (log/error "Message for voice-state-update was too large."
                 "This should not occur if you are using valid types for the keys.")))
  {:shard shard
   :effects []})

(defmulti handle-connection-event!
  "Handles events which connect or disconnect the shard, returning effects."
  (fn [shard url [event-type & event-data]]
    event-type))

(defmethod handle-connection-event! :disconnect
  [{:keys [heartbeat-ch communication-ch websocket id] :as shard} _ [_ & {:keys [stop-code reason]}]]
  (when heartbeat-ch
    (a/close! heartbeat-ch))
  (a/close! communication-ch)
  (if websocket
    (if stop-code
      (ws/close websocket stop-code reason)
      (ws/close websocket))
    (log/debug "Websocket for shard" (:id shard)
               "already closed at time of disconnection"))
  (log/info "Disconnecting shard"
            id
            "and closing connection")
  {:shard nil
   :effects []})

(defmethod handle-connection-event! :connect
  [{:keys [heartbeat-ch event-ch compress] :as shard} url _]
  (log/info "Connecting shard" (:id shard))
  (when heartbeat-ch
    (a/close! heartbeat-ch))
  (let [event-ch (a/chan 100)
        websocket (try (connect-websocket! buffer-size url event-ch compress)
                       (catch Exception err
                         (log/warn "Failed to connect a websocket" err)
                         nil))]
    (when-not websocket
      (a/put! event-ch [:disconnect nil "Failed to connect"]))
    {:shard (assoc (dissoc shard
                           :heartbeat-ch)
                   :websocket websocket
                   :event-ch event-ch)
     :effects []}))

(defn step-shard!
  "Starts a process to step a `shard`, handling side-effects.
  Returns a channel which will have a map with the new `:shard` and a vector of
  `:effects` for the entire bot to respond to placed on it after the next item
  the socket may respond to occurs."
  [shard url token]
  (log/trace "Stepping shard" (:id shard) shard)
  (let [{:keys [event-ch websocket heartbeat-ch communication-ch connections-ch] :or {heartbeat-ch (a/chan)}} shard
        connections-fn (fn [event]
                         (log/debug "Received connection event on shard" (:id shard))
                         (handle-connection-event! shard url event))
        communication-fn (fn [[event-type event-data :as value]]
                           (log/debug "Recieved communication value" value "on shard" (:id shard))
                           (handle-shard-communication! shard url value))
        heartbeat-fn (fn []
                       (if (:ack shard)
                         (try (log/trace "Sending heartbeat payload on shard" (:id shard))
                              (ws/send-msg websocket
                                           (json/write-str {:op 1
                                                            :d (:seq shard)}))
                              {:shard (dissoc shard :ack)
                               :effects []}
                              (catch java.util.concurrent.ExecutionException e
                                (if (instance? java.nio.channels.ClosedChannelException (.getCause e))
                                  (do
                                    (log/warn e "Race condition hit, ran a heartbeat on a closed websocket.")
                                    {:shard shard
                                     :effects []})
                                  (throw e))))
                         (do
                           (if websocket
                             (ws/close websocket 4000 "Zombie Heartbeat")
                             (log/debug "Websocket for shard" (:id shard)
                                        "already closed during zombie heartbeat check"))
                           (log/info "Reconnecting due to zombie heartbeat on shard" (:id shard))
                           (a/close! heartbeat-ch)
                           (a/put! connections-ch [:connect])
                           {:shard (dissoc shard
                                           :heartbeat-ch
                                           :ready
                                           :websocket)
                            :effects []})))
        event-fn (fn [event]
                   (let [{:keys [shard effects]} (handle-websocket-event shard event)
                         shard-map (reduce
                                    (fn [{:keys [shard effects]} new-effect]
                                      (let [old-effects effects
                                            {:keys [shard effects]}
                                            (handle-shard-fx! heartbeat-ch url token shard new-effect)
                                            new-effects (vec (concat old-effects effects))]
                                        {:shard shard
                                         :effects new-effects}))
                                    {:shard shard
                                     :effects []}
                                    effects)]
                     shard-map))]
    (a/go
      (if (:websocket shard)
        (if (:ready shard)
          (a/alt!
            event-ch ([event] (event-fn event))
            connections-ch ([event] (connections-fn event))
            communication-ch ([args] (communication-fn args))
            heartbeat-ch (heartbeat-fn)
            :priority true)
          (a/alt!
            event-ch ([event] (event-fn event))
            connections-ch ([event] (connections-fn event))
            heartbeat-ch (heartbeat-fn)
            :priority true))
        (a/alt!
          event-ch ([event] (event-fn event))
          connections-ch ([event] (connections-fn event))
          :priority true)))))

(defn get-websocket-gateway
  "Gets the shard count and websocket endpoint from Discord's API.

  Takes the `url` of the gateway and the `token` of the bot.

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
  "Creates a new shard with the given `id` and `shard-count`."
  [intents id shard-count compress]
  {:id id
   :count shard-count
   :intents intents
   :event-ch (a/chan 100)
   :communication-ch (a/chan 100)
   :connections-ch (a/chan 1)
   :compress compress})

(defn after-timeout!
  "Calls a function of no arguments after the given `timeout`.
  Returns a channel which will have the return value of the function put on it."
  [f timeout]
  (a/go (a/<! (a/timeout timeout))
        (f)))

(defmethod handle-shard-fx! :start-heartbeat
  [heartbeat-ch url token shard [_ heartbeat-interval]]
  (let [heartbeat-ch (a/chan (a/sliding-buffer 1))]
    (log/debug "Starting a heartbeat with interval" heartbeat-interval "on shard" (:id shard))
    (a/put! heartbeat-ch :heartbeat)
    (a/go-loop []
      (a/<! (a/timeout heartbeat-interval))
      (when (a/>! heartbeat-ch :heartbeat)
        (log/trace "Requesting heartbeat on shard" (:id shard))
        (recur)))
    {:shard (assoc shard
                   :heartbeat-ch heartbeat-ch
                   :ack true)
     :effects []}))

(defmethod handle-shard-fx! :send-heartbeat
  [heartbeat-ch url token shard event]
  (when heartbeat-ch
    (log/trace "Responding to requested heartbeat signal")
    (a/put! heartbeat-ch :heartbeat))
  {:shard shard
   :effects []})

(def ^:dynamic *identify-limiter* nil)
(defn run-on-agent-with-limit
  "Runs the given function on the agent, then other actions wait `millis`."
  [a f millis]
  (send-off a (fn [_]
                (f)
                (a/<!! (a/timeout millis))
                nil)))

(def intent->intent-int
  {:guilds (bit-shift-left 1 0)
   :guild-members (bit-shift-left 1 1)
   :guild-bans (bit-shift-left 1 2)
   :guild-emojis (bit-shift-left 1 3)
   :guild-integrations (bit-shift-left 1 4)
   :guild-webhooks (bit-shift-left 1 5)
   :guild-invites (bit-shift-left 1 6)
   :guild-voice-states (bit-shift-left 1 7)
   :guild-presences (bit-shift-left 1 8)
   :guild-messages (bit-shift-left 1 9)
   :guild-message-reactions (bit-shift-left 1 10)
   :guild-message-typing (bit-shift-left 1 11)
   :direct-messages (bit-shift-left 1 12)
   :direct-message-reactions (bit-shift-left 1 13)
   :direct-message-typing (bit-shift-left 1 14)
   :guild-scheduled-events (bit-shift-left 1 16)})
(defn- intents->intent-int
  "Takes a set of intents and creates an intents-int to represent that set."
  [intents]
  (reduce #(bit-or %1 (intent->intent-int %2))
          0
          intents))

(def ^:dynamic *identify-when*
  "Function that returns a channel that yields when it's time to identify."
  nil)

(defn make-identify-fn
  [token shard]
  (fn []
    (log/debug "Sending identify payload for shard" (:id shard))
    (let [payload {"token" token
                   "properties" {"$os" "linux"
                                 "$browser" "discljord"
                                 "$device" "discljord"}
                   "compress" false
                   "large_threshold" 50
                   "shard" [(:id shard) (:count shard)]}
          payload (if-let [intents (:intents shard)]
                    (assoc payload "intents" (intents->intent-int intents))
                    payload)]
      (log/trace "Identify payload:" (assoc payload "token" "REDACTED"))
      (ws/send-msg (:websocket shard)
                   (json/write-str {:op 2
                                    :d payload})))))

(defmethod handle-shard-fx! :identify
  [heartbeat-ch url token shard event]
  (run-on-agent-with-limit
   *identify-limiter*
   (let [f (make-identify-fn token shard)
         identify-when *identify-when*]
     (if-not identify-when
       f
       (fn []
         (a/<!! (identify-when token))
         (f))))
   5100)
  {:shard shard
   :effects []})

(defmethod handle-shard-fx! :resume
  [heartbeat-ch url token shard event]
  (log/debug "Sending resume payload for shard" (:id shard)
             "with session" (:session-id shard) "and seq" (:seq shard))
  (ws/send-msg (:websocket shard)
               (json/write-str {:op 6
                                :d {"token" token
                                    "session_id" (:session-id shard)
                                    "seq" (:seq shard)}}))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx! :reconnect
  [heartbeat-ch url token {:keys [websocket] :as shard} event]
  (when (:invalid-session shard)
    (log/warn "Got invalid session payload, reconnecting shard" (:id shard)))
  (when (:heartbeat-ch shard)
    (a/close! (:heartbeat-ch shard)))
  (if websocket
    (ws/close websocket 4000 "Reconnection requested")
    (log/debug "Websocket for shard" (:id shard)
               "already closed at the time of a reconnect"))
  (let [retries (or (:retries shard) 0)
        retry-wait (min (* 5100 (* retries retries)) (* 15 60 1000))]
    (log/debug "Will try to connect in" (int (/ retry-wait 1000)) "seconds")
    (after-timeout! (fn []
                      (log/debug "Sending reconnect signal to shard" (:id shard))
                      (a/put! (:connections-ch shard) [:connect]))
                    retry-wait)
    (let [shard (if (:invalid-session shard)
                  (dissoc shard :session-id)
                  shard)]
      {:shard (assoc (dissoc shard
                             :heartbeat-ch
                             :ready
                             :websocket)
                     :retries (inc retries))
       :effects []})))

(defmethod handle-shard-fx! :re-shard
  [heartbeat-ch url token shard event]
  {:shard shard
   :effects [[:re-shard]]})

(defmethod handle-shard-fx! :error
  [heartbeat-ch url token {:keys [websocket] :as shard} [_ err]]
  (log/error err "Error encountered on shard" (:id shard))
  (if websocket
    (ws/close websocket 4000 "Error encountered on the shard")
    (log/debug "Websocket for shard" (:id shard)
               "already closed at the time of an error"))
  {:shard (dissoc shard :websocket)
   :effects []})

(defmethod handle-shard-fx! :send-discord-event
  [heartbeat-ch url token shard [_ event-type event]]
  (log/trace "Shard" (:id shard) "recieved discord event:" event)
  {:shard shard
   :effects [[:discord-event event-type event]]})

(defmethod handle-shard-fx! :disconnect-all
  [heartbeat-ch url token shard _]
  {:shard shard
   :effects [[:disconnect]]})

(defmethod handle-shard-fx! :disconnect
  [heartbeat-ch url token {:keys [websocket] :as shard} _]
  (if websocket
    (ws/close websocket)
    (log/debug "Websocket for shard" (:id shard)
               "already closed at the time of a disconnect effect"))
  {:shard (dissoc shard :websocket)
   :effects []})

(defmulti handle-bot-fx!
  "Handles a bot-level side effect triggered by a shard.
  This method should never block, and should not do intense computation. Takes a
  place to output events to the library user, the url to connect sockets, the
  bot's token, the vector of shards, a vector of channels which resolve to the
  shard's next state, the index of the shard the effect is from, and the effect.
  Returns a vector of the vector of shards and the vector of shard channels."
  (fn [output-ch url token shards shard-chs shard-idx effect]
    (first effect)))

(defmulti handle-communication!
  "Handles communicating to the `shards`.
  Takes an `event` vector, a vector of `shards`, and a vector of channels which
  resolve to each shard's next state, and returns a vector of the vector of
  shards and the vector of channels."
  (fn [shards shard-chs event]
    (first event)))

(defn- index-of
  "Fetches the index of the first occurent of `elt` in `coll`.
  Returns nil if it's not found."
  [elt coll]
  (first (first (filter (comp #{elt} second) (map-indexed vector coll)))))

(defn connect-shards!
  "Connects a set of shards with the given `shard-ids`.
  Returns nil."
  [output-ch communication-ch url token intents shard-count shard-ids compress]
  (let [shards (mapv #(make-shard intents % shard-count compress) shard-ids)]
    (a/go-loop [shards shards
                shard-chs (mapv #(step-shard! % url token) shards)]
      (if (some identity shard-chs)
        (do (log/trace "Waiting for a shard to complete a step")
            (let [[v p] (a/alts! (conj (remove nil? shard-chs)
                                       communication-ch))]
              (if (= communication-ch p)
                (let [[shards shard-chs & [effects]] (handle-communication! shards shard-chs v)]
                  (if (seq effects)
                    (let [[shards shard-chs] (reduce (fn [[shards shard-chs] effect]
                                                       (handle-bot-fx! output-ch
                                                                       url token
                                                                       shards shard-chs
                                                                       nil effect))
                                                     [shards shard-chs]
                                                     effects)]
                      (recur shards shard-chs))
                    (recur shards shard-chs)))
                (let [idx (index-of p shard-chs)
                      effects (:effects v)
                      shards (assoc shards idx (:shard v))
                      shard-chs (assoc shard-chs idx (when (:shard v)
                                                       (step-shard! (:shard v) url token)))
                      [shards shard-chs] (reduce (fn [[shards shard-chs] effect]
                                                   (handle-bot-fx! output-ch
                                                                   url token
                                                                   shards shard-chs
                                                                   idx effect))
                                                 [shards shard-chs]
                                                 effects)]
                  (recur shards shard-chs)))))
        (do (log/info "Exiting the shard loop")
            (a/put! output-ch [:disconnect]))))
    (doseq [[idx shard] (map-indexed vector shards)]
      (a/put! (:connections-ch shard) [:connect]))
    (after-timeout! #(a/put! output-ch [:connected-all-shards]) (+ (* (dec (count shard-ids)) 5100)
                                                                   100))
    nil))

(defmethod handle-bot-fx! :discord-event
  [output-ch url token shards shard-chs shard-idx [_ event-type event]]
  (a/put! output-ch [event-type event])
  [shards shard-chs])

(def ^:dynamic *handle-re-shard*
  "Determines if the bot will re-shard on its own, or require user coordination.
  If bound to true and a re-shard occurs, the bot will make a request to discord
  for the new number of shards to connect with and then connect them. If bound
  to false, then a :re-shard event will be sent to the user library and all
  shards will be disconnected."
  true)

(defmethod handle-bot-fx! :re-shard
  [output-ch url token shards shard-chs shard-idx [_ event-type event]]
  (log/info "Stopping all current shards to prepare for re-shard.")
  (a/put! output-ch [:re-shard])
  (run! #(a/put! (:connections-ch %) [:disconnect]) shards)
  (run! #(a/<!! (step-shard! % url token))
        (remove nil?
                (map (comp :shard a/<!!) shard-chs)))
  (if *handle-re-shard*
    (let [{:keys [url shard-count session-start-limit]} (get-websocket-gateway gateway-url token)]
      (when (> shard-count (:remaining session-start-limit))
        (log/fatal "Asked to re-shard client, but too few session starts remain.")
        (throw (ex-info "Unable to re-shard client, too few session starts remaining."
                        {:token token
                         :shards-requested shard-count
                         :remaining-starts (:remaining session-start-limit)
                         :reset-after (:reset-after session-start-limit)})))
      (let [shards (mapv #(make-shard (:intents (nth shards shard-idx)) % shard-count
                                      (:compress (nth shards shard-idx)))
                         (range shard-count))
            shard-chs (mapv #(step-shard! % url token) shards)]
        (doseq [[idx shard] (map-indexed vector shards)]
          (after-timeout! #(a/put! (:connections-ch shard) [:connect]) (* idx 5100)))
        (after-timeout! #(a/put! output-ch [:connected-all-shards]) (+ (* (dec shard-count) 5100)
                                                                       100))
        [shards shard-chs]))
    [nil nil]))

(defmethod handle-bot-fx! :disconnect
  [output-ch url token shards shard-chs shard-idx _]
  (log/info
   (if shard-idx
     (str "Full disconnect triggered from shard" shard-idx)
     "Full disconnect triggered from input"))
  (a/put! output-ch [:disconnect])
  (run! #(a/put! (:connections-ch %) [:disconnect]) shards)
  (run! #(a/<!! (step-shard! % url token))
        (remove nil?
                (map (comp :shard a/<!!) shard-chs)))
  [nil nil])

(defmethod handle-bot-fx! :connect-shards
  [output-ch url token shards shard-chs shard-idx [_ new-shards intents disable-compression :as event]]
  (let [new-shards (map #(merge (make-shard intents (:id %) (:count %)
                                            (not disable-compression))
                                %)
                        new-shards)
        new-shard-chs (map #(step-shard! % url token) new-shards)]
    (doseq [[idx shard] (map-indexed vector new-shards)]
      (after-timeout! #(a/put! (:connections-ch shard) [:connect]) (* idx 5100)))
    [(vec (concat shards new-shards)) (vec (concat shard-chs new-shard-chs))]))

(defn remove-shards
  "Removes shards fitting a predicate from the vector of shards and channels."
  [pred shards shard-chs]
  (vec (reduce (fn [acc [s c]]
                 (if-not (pred s)
                   [(conj (first acc) s)
                    (conj (last acc) c)]
                   acc))
               [[] []]
               (map vector shards shard-chs))))

(defn shard-matches?
  "Returns true if all keys in `match` are equal to the ones in `shard`."
  [shard match]
  (= (select-keys shard (keys match)) match))

(defn shard-matches-any?
  "Returns true if the shard matches against any of the passed matchers."
  [match-any shard]
  (some (partial shard-matches? shard) match-any))

(defmethod handle-bot-fx! :disconnect-shards
  [output-ch url token shards shard-chs shard-idx [_ to-disconnect]]
  (let [matches-any? (partial shard-matches-any? to-disconnect)]
    (let [[shards shard-chs] (remove-shards (comp not matches-any?) shards shard-chs)]
      (run! #(a/put! (:connections-ch %) [:disconnect :stop-code 4000 :reason "Migrating Shard"])
            shards)
      (run! #(a/<!! (step-shard! % url token))
            (keep (comp :shard a/<!!) shard-chs)))
    (remove-shards matches-any? shards shard-chs)))

(defmethod handle-communication! :disconnect
  [shards shard-chs _]
  (run! #(a/put! (:connections-ch %) [:disconnect]) shards)
  [shards shard-chs [[:disconnect]]])

(defmethod handle-communication! :send-debug-event
  [shards shard-chs [_ shard-id event]]
  (a/put! (:event-ch (get shards shard-id)) event)
  [shards shard-chs])

(defn get-shard-from-guild
  [guild-id guild-count]
  (mod (bit-shift-right (Long. ^String guild-id) 22) guild-count))

(defmethod handle-communication! :guild-request-members
  [shards shard-chs [_ & {:keys [guild-id]} :as event]]
  (when guild-id
    (let [shard-id (get-shard-from-guild guild-id (:count (first (remove nil? shards))))
          shard (first (filter (comp #{shard-id} :id) shards))]
      (if shard
        (a/put! (:communication-ch shard) event)
        (when (seq (remove nil? shards))
          (log/error "Attempted to request guild members for a guild with no"
                     "matching shard in this process.")))))
  [shards shard-chs])

(defmethod handle-communication! :status-update
  [shards shard-chs event]
  (let [destination-shards (:shards (apply hash-map (rest event)))]
    (doseq [shard (cond->> shards
                   (set? destination-shards) (filter (comp destination-shards :id)))]
      (a/put! (:communication-ch shard) event)))
  [shards shard-chs])

(defmethod handle-communication! :voice-state-update
  [shards shard-chs [_ & {:keys [guild-id]}
                     :as event]]
  (when guild-id
    (let [shard-id (get-shard-from-guild guild-id (:count (first (remove nil? shards))))
          shard (first (filter (comp #{shard-id} :id) shards))]
      (if shard
        (a/put! (:communication-ch shard) event)
        (when (seq (remove nil? shards))
          (log/error "Attempted to send voice-state-update for a guild with no"
                     "matching shard in this process.")))))
  [shards shard-chs])

(defmethod handle-communication! :get-shard-state
  [shards shard-chs [_ to-fetch prom]]
  (a/put! prom (let [shards (map #(select-keys % #{:session-id :id :count :seq}) shards)]
                 (if to-fetch
                   (filter (partial shard-matches-any? to-fetch) shards)
                   shards)))
  [shards shard-chs])

(defmethod handle-communication! :connect-shards
  [shards shard-chs [_ new-shards intents disable-compression :as event]]
  [shards shard-chs [event]])

(defmethod handle-communication! :disconnect-shards
  [shards shard-chs [_ to-disconnect]]
  [shards shard-chs [[:disconnect-shards  to-disconnect]]])
