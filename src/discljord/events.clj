(ns discljord.events
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

(defmulti handle-event
  "Handles an event sent from Discord's servers"
  (fn [event-type event-data event-channel]
    event-type))

(defmethod handle-event :default
  [event-type event-data event-channel]
  nil)

(defn default-message-pump
  "Pulls events off of the channel and calls handle-event with them,
  and stops when it sees a :disconnect event"
  [event-ch]
  (a/go-loop []
    (let [[event-type event-data] (a/<! event-ch)]
      (handle-event event-type event-data event-ch)
      (when-not (= event-type :disconnect)
        (recur)))))
