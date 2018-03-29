(ns discljord.connections
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.rpl.specter :refer :all]))

(s/def ::url string?)
(s/def ::shard-count int?)
(s/def ::gateway (s/nilable (s/keys :req-un [::url ::shard-count])))
(s/def ::shard-id int?)
(s/def ::socket-state any?)
(s/def ::shard (s/keys :req-un [::gateway ::shard-id ::socket-state]))
(s/def ::shards (s/coll-of ::shard))

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
        :args (s/cat :gateway string?)
        :ret ::url)

(defn get-websocket-gateway!
  [url token]
  (if-let [result (try (into {} (mapv (fn [[k v]] [(if (= k "shards")
                                                     :shard-count
                                                     (keyword k)) v])
                                      (vec (json/read-str (:body @(http/get url
                                                                    {:headers {"Authorization" token}}))))))
                       (catch Exception e
                         nil))]
    (when (:url result)
      result)))
(s/fdef get-websocket-gateway!
        :args (s/cat :url ::url :token string?)
        :ret ::gateway)

(defn create-shard
  [gateway shard-id]
  {:gateway gateway :shard-id shard-id :socket-state (atom {:ack? true :keep-alive true})})
(s/fdef create-shard
        :args (s/cat :gateway ::gateway :shard-id ::shard-id)
        :ret ::shard)

(defn json-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef json-keyword
        :args (s/cat :str string?)
        :ret keyword?)

(defn clean-json-input
  [j]
  (cond
    (map? j) (->> j
                  (transform [MAP-KEYS] clean-json-input)
                  (transform [MAP-VALS coll?] clean-json-input))
    (string? j) (json-keyword j)
    (vector? j) (mapv clean-json-input j)
    :else j))
(s/fdef clean-json-input
        :args any?
        :ret any?)

(defn heartbeat
  [socket s]
  (ws/send-msg socket (json/write-str {"op" 1 "d" s})))

(declare connect-websocket)

(defn disconnect-websocket
  [socket-state]
  (ws/close (:socket @socket-state))
  (swap! socket-state #(-> %
                           (dissoc :socket)
                           (assoc :keep-alive false))))

(defn reconnect-websocket
  [gateway token shard-id event-channel socket-state resume & causes]
  (do ;a/go-loop [timeout-amount 1000 retry-count 0]
    (println "Causes for reconnect:" causes)
    #_(println "Reconnection attempt:" retry-count)
    (println "Attempting resume?" resume)
    (swap! socket-state #(-> %
                             (assoc :keep-alive false)
                             (assoc :ack? true)
                             (assoc :resume resume)
                             (dissoc :socket)))
    (a/<!! (a/timeout 100))
    (println "Opening socket during reconnect")
    (try (swap! socket-state #(-> %
                                  (assoc :socket (connect-websocket
                                                  gateway token shard-id
                                                  event-channel socket-state))
                                  (assoc :keep-alive true)))
         (catch Exception e
           (let [error-message (with-out-str (binding [*err* *out*]
                                               (.printStackTrace e)))]
             (println error-message))))
    #_(a/<! (a/timeout timeout-amount))
    #_(when (and (not (:connected @socket-state)) (< timeout-amount 100000))
      (recur (* 2 timeout-amount) (inc retry-count)))))

(defn connect-websocket
  [gateway token shard-id event-channel socket-state & [client]]
  (ws/connect (:url gateway)
    :client client
    :on-connect (fn [_]
                  (println "Connected!")
                  (println "Sending connection packet. Resume:" (:resume @socket-state))
                  (swap! socket-state assoc :connected true)
                  (if-not (:resume @socket-state)
                    (ws/send-msg (:socket @socket-state) (json/write-str
                                                          {"op" 2
                                                           "d" {"token" token
                                                                "properties"
                                                                {"$os" "linux"
                                                                 "$browser" "discljord"
                                                                 "$device" "discljord"}
                                                                "compress" false
                                                                "large_threshold" 250
                                                                "shard" [shard-id (:shard-count gateway)]
                                                                "presence"
                                                                (:presence @socket-state)}}))
                    (ws/send-msg (:socket @socket-state) (json/write-str
                                                          {"op" 6
                                                           "d" {"token" token
                                                                "session_id" (:session @socket-state)
                                                                "seq" (:seq @socket-state)}})))
                  (a/go-loop [continue true]
                    (when (and continue (:ack? @socket-state) (:socket @socket-state))
                      (if-let [interval (:hb-interval @socket-state)]
                        (do (heartbeat (:socket @socket-state) (:seq @socket-state))
                            (swap! socket-state assoc :ack? false)
                            (a/<! (a/timeout interval))
                            (recur (:keep-alive @socket-state)))
                        (do (a/<! (a/timeout 100))
                            (recur (:keep-alive @socket-state)))))))
    :on-receive (fn [msg]
                  #_(println "Message recieved:" msg)
                  (let [msg (json/read-str msg)
                        op (get msg "op")]
                    (case op
                      ;; This is the initial payload that is sent, the "Hello" payload
                      10 (let [d (get msg "d")
                               interval (get d "heartbeat_interval")]
                           (swap! socket-state #(-> %
                                                    (assoc :hb-interval interval)
                                                    (assoc :ack? true))))
                      ;; These payloads occur when the server requests a heartbeat
                      1 (do (println "Sending heartbeat from server response")
                            (heartbeat (:socket @socket-state) (:seq @socket-state)))
                      ;; This is the server's response to a heartbeat
                      11 (swap! socket-state assoc :ack? true)
                      ;; This is the payload that contains events to be responded to
                      0 (a/go (let [t (json-keyword (get msg "t"))
                                    d (get msg "d")
                                    s (get msg "s")]
                                #_(println "type" t "data" d "seq" s)
                                (if-let [session (get d "session_id")]
                                  (swap! socket-state #(-> %
                                                           (assoc :seq s)
                                                           (assoc :session session)))
                                  (swap! socket-state assoc :seq s))
                                (a/>! event-channel {:event-type t :event-data (clean-json-input d)})))
                      ;; This is the restart connection one
                      7 (a/go
                          (disconnect-websocket socket-state)
                          (reconnect-websocket gateway token
                                               shard-id event-channel
                                               socket-state true
                                               "Reconnection message"))
                      ;; This is the invalid session response
                      9 (a/go
                          (disconnect-websocket socket-state)
                          (if (get msg "d")
                            (reconnect-websocket gateway token
                                                 shard-id event-channel
                                                 socket-state true "Invalid session, resume")
                            (reconnect-websocket gateway token
                                                 shard-id event-channel
                                                 socket-state false "Invalid session")))
                      ;; This is what happens if there was a unknown payload
                      (println "Unhandled response from server:" op))))
    :on-close (fn [stop-code msg]
                (println "Connection closed. code:" stop-code "\nmessage:" msg)
                (if (:connected @socket-state)
                  (case stop-code
                    ;; Unknown error
                    4000 (a/go (a/<! (a/timeout 100))
                               (reconnect-websocket gateway token
                                                    shard-id event-channel
                                                    socket-state true "Unknown error"))
                    4001 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Invalid gateway opcode sent to server")))
                    4002 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Invalid payload send to server")))
                    4003 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Payload sent to server before Identify payload")))
                    4004 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Invalid token")))
                    4005 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Multiple Identify payloads sent")))
                    ;; Invalid seq
                    4007 (a/go (a/<! (a/timeout 100))
                               (reconnect-websocket gateway token
                                                    shard-id event-channel
                                                    socket-state false "Ivalid seq"))
                    4008 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Rate limit reached")))
                    ;; Session timed out
                    4009 (a/go (a/<! (a/timeout 1000))
                               (reconnect-websocket gateway token
                                                    shard-id event-channel
                                                    socket-state false "Session timeout"))
                    4010 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Invalid shard sent")))
                    4011 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (throw (Exception. "Sharding required")))
                    1006 (a/go (println "Read an EOF, reconnecting.")
                               (a/<! (a/timeout 100))
                               (reconnect-websocket gateway token
                                                    shard-id event-channel
                                                    socket-state true
                                                    "End of file"))
                    1001 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (println "Stop code 1001, clean disconnect"))
                    1000 (a/go (a/>! event-channel {:event-type :disconnect :event-data nil})
                               (println "Clean disconnect"))
                    (a/go (println "Unknown stop code, reconnecting.")
                          (a/<! (a/timeout 100))
                          (reconnect-websocket gateway token
                                               shard-id event-channel
                                               socket-state false
                                               "Unknown stop code.")))
                  (a/go (a/<! (a/timeout 100))
                        (reconnect-websocket gateway token
                                             shard-id event-channel
                                             socket-state false
                                             "Connection failed, reconnecting.")))
                (swap! socket-state assoc :connected false)
                (swap! socket-state dissoc :socket))))

