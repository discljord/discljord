(ns discljord.events
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]))

(defn message-pump!
  "Starts a process which pulls events off of the channel and calls
  handle-event with them, and stops when it sees a :disconnect event.
  This takes control of the current thread.

  The handle-event function takes the keyword event type, and the event
  data."
  [event-ch handle-event]
  (loop []
    (let [[event-type event-data] (a/<!! event-ch)]
      (try (handle-event event-type event-data)
           (catch Exception e
             (log/error e "Exception occurred in event handler.")))
      (when-not (= event-type :disconnect)
        (recur))))
  nil)
(s/fdef message-pump!
  :args (s/cat :channel any?
               :handle-event ifn?)
  :ret nil?)
