(ns user
  (:use com.rpl.specter)
  (:require [discljord.connections :as c]
            [discljord.events :as e]
            [discljord.messaging :as m]
            [discljord.messaging.impl :as impl]
            [discljord.specs :as ds]
            [discljord.http :as h]
            [discljord.util :as u]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [midje.repl :refer :all]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(defonce bot-token (str/trim (slurp (io/resource "token.txt"))))

(defonce bot-events (atom nil))
(defonce bot-communicate (atom nil))
(defonce bot-message (atom nil))

(defmulti handle-event
  "Handles an event sent from Discord's servers"
  (fn [event-type event-data]
    event-type))

(defmethod handle-event :default
  [event-type event-data]
  nil)

(defmethod handle-event :connect
  [event-type event-data]
  (println "Connected to Discord!"))

(defmethod handle-event :message-create
  [event-type {{:keys [bot] :as author} :author :keys [channel-id content] :as event-data}]
  (prn event-data)
  (when-not bot
    (m/send-message! @bot-message channel-id content)))

(defmethod handle-event :disconnect
  [event-type event-data]
  (println "Disconnected from Discord!"))

(defn start-bot
  []
  (let [ch (a/chan 100)
        bot (c/connect-bot! bot-token ch)
        msg (m/start-connection! bot-token)]
    (reset! bot-communicate bot)
    (reset! bot-events ch)
    (reset! bot-message msg)
    (e/message-pump! ch handle-event)))

(defn stop-bot
  []
  (when @bot-communicate
    (a/>!! @bot-communicate [:disconnect]))
  (when @bot-message
    (a/>!! @bot-message [:disconnect])))

(defn get-limited
  [msg channel]
  (doall (repeatedly 5 #(m/send-message! @bot-message channel msg))))
