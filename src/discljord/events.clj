(ns discljord.events
  "Functions for getting events off of a queue and processing them."
  (:require
   [clojure.core.async.impl.protocols :refer [ReadPort]]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [discljord.specs :as ds])
  (:import
   (clojure.lang IDeref)))

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

(defn event-grinder!
  "Starts a process to handle each event as it arrives from discord, each in its own go block.

  The `context` is a map with a `:queue` key that represents the operations
  which must happen next, and an `:fx` key to represent actions which must be
  taken.

  When each event is received from Discord, it is added to the context under the
  keys `:event-type` and `:event-data`, and then `stepper` is called with the
  context and each element of the `:queue` in order. The return value from the
  stepper is the next context, and any alterations to the queue will affect what
  the stepper will be called next with. After each step, the `:fx` key will be
  taken and the `fx` function will be called with the context and each element
  of the collection in `:fx`, in parallel. `fx` should not block, and the return
  value is ignored. When the `:queue` is empty, the event has been fully
  processed and the modified context will be discarded. The stepper may also
  return a channel or implementation of [[IDeref]] (although a channel requires
  fewer threads) that resolves to the next context."
  [event-ch context stepper fx]
  (loop []
    (let [[event-type event-data] (a/<!! event-ch)]
      (a/go
        (loop [ctx (assoc context
                          :event-type event-type
                          :event-data event-data)]
          (when-some [[step & next-queue] (seq (:queue ctx))]
            (let [ctx (try
                        (stepper (assoc ctx :queue next-queue) step)
                        (catch Exception e
                          (log/error e "Exception occurred in context stepper.")))
                  ctx (try
                        (cond
                          (satisfies? ReadPort ctx) (a/<! ctx)
                          (instance? IDeref ctx)
                          (a/<! (a/thread
                                  (try @ctx
                                       (catch Exception e
                                         (log/error e "Exception while dereferencing an async context.")))))
                          :otherwise ctx)
                        (catch Exception e
                          (log/error e "Exception while dereferencing an async context.")))]
              (when ctx
                (run! (let [ctx (dissoc ctx :fx)]
                        (fn [v]
                          (a/go
                            (try (fx ctx v)
                                 (catch Exception e
                                   (log/error e "Exception while realizing effect."))))))
                      (:fx ctx))
                (recur (dissoc ctx :fx)))))))
      (when-not (= event-type :disconnect)
        (recur))))
  nil)
(s/fdef event-grinder!
  :args (s/cat :event-ch ::ds/channel
               :context map?
               :stepper ifn?
               :fx ifn?)
  :ret nil?)
