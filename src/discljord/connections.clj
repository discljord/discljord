(ns discljord.connections
  (:use com.rpl.specter)
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
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

(defmulti handle-event
  "Handles events sent from discord over the websocket.
  Takes a vector of event type and event data, the websocket connection, and the events channel."
  (fn [[event-type event-data] conn ch]
    event-type))

(defmethod handle-event :connect
  [[_ token] conn ch]
  (ws/send-msg @conn
               (json/write-str {"op" 2
                                "d" {"token" token
                                     ;; TODO: Add default presence and shard
                                     "properties" {"$os" "linux"
                                                   "$browser" "discljord"
                                                   "$device" "discljord"}}})))

(defmethod handle-event :reconnect
  [[_ [token session-id seq]] conn ch]
  (ws/send-msg @conn
               (json/write-str {"op" 6
                                "d" {"token" token
                                     "session_id" session-id
                                     "seq" seq}})))

(defmethod handle-event :disconnect
  [[_ [url token stop-code msg]] conn ch]
  ;; TODO: Make this worry about stop codes
  ;; This will trigger a reconnect, but only do this if you need to
  (reset! conn (ws/connect url
                 :on-connect (fn [_]
                               ;; Put a connection event on the channel
                               (a/go (a/>! ch [:reconnect [token 0 0]])))
                 :on-receive (fn [msg]
                               ;; Put a recieve event on the channel
                               (a/go (a/>! ch [:recieve msg])))
                 :on-close (fn [stop-code msg]
                             ;; Put a disconnect event on the channel
                             (a/go (a/>! ch [:disconnect [url
                                                          token
                                                          stop-code
                                                          msg]]))))))

(defmethod handle-event :recieve
  [[_ msg] conn ch]
  ;; TODO: Make this dispatch messages based on what kind of messages handler we want
  ;;       This will have default handlers for many types of messages, like opcode 7
  )

(defn start-event-loop
  "Starts a go loop which takes events from the channel and dispatches them
  via multimethod."
  [ch conn]
  (a/go-loop []
    (try (handle-event (a/<! ch) conn ch)
         (catch Exception e
           nil))))

(defn connect-shard
  "Takes a gateway URL and a bot token, creates a websocket connected to
  Discord's servers, and returns it."
  [url token]
  (let [event-ch (a/chan 100)
        conn (atom (ws/connect url
                     :on-connect (fn [_]
                                   ;; Put a connection event on the channel
                                   (a/go (a/>! event-ch [:connect token])))
                     :on-receive (fn [msg]
                                   ;; Put a recieve event on the channel
                                   (a/go (a/>! event-ch [:recieve msg])))
                     :on-close (fn [stop-code msg]
                                 ;; Put a disconnect event on the channel
                                 (a/go (a/>! event-ch [:disconnect [url
                                                                    token
                                                                    stop-code
                                                                    msg]])))))]
    ;; Start an event loop with event-ch
    (start-event-loop event-ch conn)
    nil))

(defn connect-bot
  "Creates a connection process which will handle the services granted by
  Discord which interact over websocket.

  Creates a connection to Discord's servers using the given token, and sends
  all events over the provided channel.

  Returns a channel used to communicate with the process and send packets to
  Discord."
  [token ch]
  )
