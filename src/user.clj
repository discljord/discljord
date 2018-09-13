(ns user
  (:require [discljord.connections :as c]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.fake :as fake]
            [org.httpkit.server :as s :refer [with-channel
                                              run-server
                                              send!
                                              close]]
            [gniazdo.core :as ws]
            [midje.repl :refer :all]))

(defmulti server-handle-message
  "Takes an op code and information about the bot, and handles it appropriately."
  (fn [conn opcode data]
    opcode))

(defn message-handler
  [channel message]
  (let [message (json/read-str message)
        op (get message "op")
        d (c/clean-json-input (get message "d"))]
    (server-handle-message channel op d)))

;; Identify packet
(defmethod server-handle-message 2
  [conn opcode data]
  (println "recieved identify!"))

(defn connect-handler
  [request]
  (println "Starting server")
  (with-channel request channel
    (send! channel (json/write-str {"op" 10 "d" {"heartbeat_interval" 10}}))
    (s/on-receive channel (partial message-handler channel))))

(def uri "ws://localhost:9090")

(def server-stop (atom nil))

(defn start-server
  []
  (reset! server-stop (run-server connect-handler {:port 9090})))

(defn stop-server
  []
  (when @server-stop
    (@server-stop)
    (reset! server-stop nil)))
