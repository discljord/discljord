(ns discljord.connections
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]))

(defn api-url
  [gateway]
  (str "https://discordapp.com/api" gateway "?v=6&encoding=json"))

(defn get-websocket-gateway!
  [gateway token]
  (if-let [result (try (into {} (mapv (fn [[k v]] [(keyword k) v])
                                      (vec (json/read-str (:body @(http/get gateway
                                                                    {:headers {"Authorization" token}}))))))
                       (catch Exception e
                         nil))]
    (when (:url result)
      result)))

(defn heartbeat
  [socket s]
  (ws/send-msg socket (json/write-str {"op" 1 "d" s})))

(defn connect-websocket
  [gateway token [shard-id shard-count] socket-state]
  (ws/connect (:url gateway)
    :on-connect (fn [_] ;; TODO: Start sending heartbeats
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
                  (let [msg (json/read-str msg)
                        op (get msg "op")]
                    (case op
                      10 (let [d (get msg "d")
                               interval (get d "heartbeat_interval")]
                           (ws/send-msg (:socket @socket-state) (json/write-str
                                                                 {"op" 2
                                                                  "d" {"token" token
                                                                       "properties"
                                                                       {"$os" "linux"
                                                                        "$browser" "discljord"
                                                                        "$device" "discljord"}
                                                                       "compress" false
                                                                       "large_threshold" 250
                                                                       "shard" [shard-id shard-count]
                                                                       "presence"
                                                                       (:presence @socket-state)}}))
                           (swap! socket-state #(assoc (assoc % :hb-interval interval) :ack? true)))
                      11 (swap! socket-state assoc :ack? true)
                      1 (do (println "Sending heartbeat from server response")
                            (swap! socket-state assoc :ack? false)
                            (heartbeat (:socket @socket-state) (:seq @socket-state)))
                      (println "Unhandled response from server:" op))))
    :on-close (fn [stop-code msg])))
