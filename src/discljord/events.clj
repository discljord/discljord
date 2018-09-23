(ns discljord.events
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

(defn message-pump!
  "Pulls events off of the channel and calls handle-event with them,
  and stops when it sees a :disconnect event.

  The handle-event function takes the keyword event type, the event
  data, and a state value.
  The value returned by handle-event will be used as the new state
  for the next event."
  [event-ch handle-event init-state]
  (a/go-loop [state init-state]
    (let [[event-type event-data] (a/<! event-ch)
          new-state (handle-event event-type event-data state)]
      (when-not (= event-type :disconnect)
        (recur new-state))))
  nil)
(s/fdef message-pump!
  :args (s/cat :channel any?
               :handle-event (s/fspec :args (s/cat :event-type keyword?
                                                   :event-data any?
                                                   :handler-state any?)
                                      :ret any?)
               :init-state any?)
  :ret nil?)
