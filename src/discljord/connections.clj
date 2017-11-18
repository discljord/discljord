(ns discljord.connections
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.string :as str]))

(defn api-url
  [gateway]
  (str "https://discordapp.com/api" gateway "?v=6&encoding=json"))

(defn get-websocket-gateway!
  [gateway token]
  (if-let [result (try (into {} (mapv (fn [[k v]] [(keyword k) v])
                                      (vec (json/read-str (:body @(http/get gateway
                                                                    {:headers {"Authorization" token}}))))))
                       (catch Exception e
                         (println e)
                         nil))]
    (when (:url result)
      result)))

(defn event-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))

(defn heartbeat
  [socket s]
  (ws/send-msg socket (json/write-str {"op" 1 "d" s})))

(defn connect-websocket
  [gateway token shard-id event-channel socket-state]
  (ws/connect (:url gateway)
    :on-connect (fn [_] ;; TODO: Start sending heartbeats
                  (println "Connected!")
                  (println "Sending connection packet")
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
    :on-receive (fn [msg] ;; TODO: respond to messages
                  (println "Message recieved:" msg)
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
                                (swap! socket-state assoc :seq s)
                                (a/>! event-channel {:type t :data d})))
                      ;; This is what happens if there was a unknown payload
                      (println "Unhandled response from server:" op))))
    :on-close (fn [stop-code msg]
                (println "Connection closed"))))
