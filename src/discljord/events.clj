(ns discljord.events
  "Functions for getting events off of a queue and processing them."
  (:require
   [clojure.core.async :as a]
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
  :args (s/cat :channel ::ds/channel
               :handle-event ifn?)
  :ret nil?)

(defn normalize-handlers
  "Constructs a `handlers` map for [[dispatch-handlers]], allowing sets for keys.

  Keyword keys are kept as-is, but sets will be split into their members to
  allow keying the handler map off of a single event type. This is done in an
  undefined order, so duplicate keys have an undefined precidence."
  [handlers]
  (reduce-kv
   (fn [m k v]
     (reduce #(assoc %1 %2 v) m (if (keyword? k) #{k} k)))
   {}
   handlers))
(s/fdef normalize-handlers
  :args (s/cat :handlers (s/map-of (s/or :keyword keyword?
                                         :key-set (s/coll-of keyword? :kind set?))
                                   (s/coll-of ifn? :kind vector?)))
  :ret (s/map-of keyword? (s/coll-of ifn? :kind vector?)))

(defn dispatch-handlers
  "Calls event handlers from `handlers` with the event and `args`.

  The `handlers` argument is a map from a keyword event type to a vector of
  event handler functions (with additional arguments filled by `args`) to be run
  in sequence."
  [handlers event-type event-data & args]
  (doseq [f (handlers event-type)]
    (apply f event-type event-data args)))
(s/fdef dispatch-handlers
  :args (s/cat :handlers (s/map-of keyword? (s/coll-of ifn? :kind vector?))
               :event-type keyword?
               :event-data any?
               :args (s/* any?))
  :ret nil?)
