(ns discljord.connections
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::url string?)
(s/def ::shard-count int?)
(s/def ::gateway (s/keys :req-un [::url ::shard-count]))
(s/def ::shard-id int?)
(s/def ::socket-state any?)
(s/def ::shard (s/keys :req-un [::gateway ::shard-id ::socket-state]))
(s/def ::shards (s/coll-of ::shard))

(defn api-url
  [gateway]
  (str "https://discordapp.com/api" gateway "?v=6&encoding=json"))
(s/fdef api-url
        :args (s/cat :gateway string?)
        :ret string?)

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

(defn event-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef event-keyword
        :args (s/cat :str string?)
        :ret keyword?)

(defn heartbeat
  [socket s]
  (ws/send-msg socket (json/write-str {"op" 1 "d" s})))

(declare connect-websocket)

(defn disconnect-websocket
  [socket-state]
  (ws/close (:socket @socket-state))
  (swap! socket-state #(assoc (dissoc % :socket) :keep-alive false)))

(defn reconnect-websocket
  [gateway token shard-id event-channel socket-state resume]
  (a/go (ws/close (:socket @socket-state))
        (swap! socket-state #(dissoc
                              (assoc (assoc
                                      (assoc % :keep-alive false)
                                      :ack? true)
                                     :resume resume)
                              :socket))
        (a/<! (a/timeout 100))
        (swap! socket-state #(assoc (assoc % :socket (connect-websocket
                                                      gateway token shard-id
                                                      event-channel socket-state))
                                    :keep-alive true))))

(defn connect-websocket
  [gateway token shard-id event-channel socket-state]
  (ws/connect (:url gateway)
    :on-connect (fn [_]
                  (println "Connected!")
                  (println "Sending connection packet. Resume:" (:resume @socket-state))
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
                                                               "shard" [shard-id (:shards gateway)]
                                                               "presence"
                                                               (:presence @socket-state)}}))
                   (ws/send-msg (:socket @socket-state) (json/write-str
                                                         {"op" 6
                                                          "d" {"token" token
                                                               "session_id" (:session @socket-state)
                                                               "seq" (:seq @socket-state)}})))
                  (a/go-loop [continue true]
                    (when (and continue (:ack? @socket-state))
                      (if-let [interval (:hb-interval @socket-state)]
                        (do (heartbeat (:socket @socket-state) (:seq @socket-state))
                            (println "Sending heartbeat from usual route")
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
                           (swap! socket-state #(assoc (assoc % :hb-interval interval) :ack? true)))
                      ;; These payloads occur when the server requests a heartbeat
                      1 (do (println "Sending heartbeat from server response")
                            (heartbeat (:socket @socket-state) (:seq @socket-state)))
                      ;; This is the server's response to a heartbeat
                      11 (swap! socket-state assoc :ack? true)
                      ;; This is the payload that contains events to be responded to
                      0 (a/go (let [t (event-keyword (get msg "t"))
                                    d (get msg "d")
                                    s (get msg "s")]
                                (println "type" t "data" d "seq" s)
                                (if-let [session (get d "session_id")]
                                  (swap! socket-state #(assoc (assoc % :seq s) :session session))
                                  (swap! socket-state assoc :seq s))
                                (a/>! event-channel {:type t :data d})))
                      ;; This is the restart connection one
                      7 (reconnect-websocket gateway token shard-id event-channel socket-state true)
                      ;; This is the invalid session response
                      9 (a/go (if (get msg "d")
                                (reconnect-websocket gateway token
                                                     shard-id event-channel
                                                     socket-state true)
                                (reconnect-websocket gateway token
                                                     shard-id event-channel
                                                     socket-state false)))
                      ;; This is what happens if there was a unknown payload
                      (println "Unhandled response from server:" op))))
    :on-close (fn [stop-code msg]
                (println "Connection closed. code:" stop-code "\nmessage:" msg)
                (case stop-code
                  ;; Unknown error
                  4000 (reconnect-websocket gateway token
                                            shard-id event-channel
                                            socket-state true)
                  4001 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Invalid gateway opcode sent to server")))
                  4002 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Invalid payload send to server")))
                  4003 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Payload sent to server before Identify payload")))
                  4004 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Invalid token")))
                  4005 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Multiple Identify payloads sent")))
                  ;; Invalid seq
                  4007 (reconnect-websocket gateway token
                                            shard-id event-channel
                                            socket-state false)
                  4008 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Rate limit reached")))
                  ;; Session timed out
                  4009 (reconnect-websocket gateway token
                                            shard-id event-channel
                                            socket-state false)
                  4010 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Invalid shard sent")))
                  4011 (do (disconnect-websocket socket-state)
                           (throw (Exception. "Sharding required")))
                  (println "Unknown stop code"))
                (swap! socket-state dissoc :socket))))

