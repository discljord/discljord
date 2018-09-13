(ns discljord.events
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

(defn message-pump
  "Pulls events off of the channel and calls handle-event with them,
  and stops when it sees a :disconnect event"
  [event-ch handle-event]
  (a/go-loop []
    (let [[event-type event-data] (a/<! event-ch)]
      (handle-event event-type event-data event-ch)
      (when-not (= event-type :disconnect)
        (recur))))
  nil)
(s/fdef message-pump
  :args (s/cat :channel any?
               :handle-event (s/fspec :args (s/cat :event-type keyword?
                                                   :event-data any?
                                                   :event-channel any?)
                                      :ret any?))
  :ret nil?)
