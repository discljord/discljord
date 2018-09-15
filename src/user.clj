(ns user
  (:require [discljord.connections :as c]
            [discljord.events :as e]
            [discljord.messaging :as m]
            [discljord.specs :as ds]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [midje.repl :refer :all]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(def bot-token (str/trim (slurp (io/resource "token.txt"))))

(def bot-events (atom nil))
(def bot-communicate (atom nil))

(defmulti handle-event
  "Handles an event sent from Discord's servers"
  (fn [event-type event-data state]
    event-type))

(defmethod handle-event :default
  [event-type event-data state]
  state)

(defmethod handle-event :connect
  [event-type event-data state]
  (println "Connected to Discord!"))

(defmethod handle-event :message-create
  [event-type event-data state]
  (prn event-data)
  state)

(defmethod handle-event :disconnect
  [event-type event-data state]
  (println "Disconnected from Discord!"))

(defn start-bot
  []
  (let [ch (a/chan 100)
        bot (c/connect-bot! bot-token ch)]
    (reset! bot-communicate bot)
    (reset! bot-events ch)
    (e/message-pump! ch handle-event nil)))

(defn stop-bot
  []
  (a/>!! @bot-communicate [:disconnect]))
