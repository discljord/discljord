(ns discljord.events.middleware
  "Contains functions for constructing middleware, and some default middleware.

  Middleware for discljord event handlers allow the modification and filtration
  of events to be sent along to event handlers. Following is an example of an
  identity middleware, and can be used as an example of what middleware in
  discljord look like.

  ```clojure
  (defn identity-middleware
    \"Middleware that passes through events unchanged.\"
    [handler]
    (fn [event-type event-data]
      (handler event-type event-data)))
  ```"
  (:require
   [clojure.tools.logging :as log])
  (:refer-clojure :rename {concat concat-seq
                           filter filter-seq
                           map map-seq
                           transduce transduce-seq}))

(defn concat
  "Takes a handler function and creates a middleware which concats the handlers.

  The events in the handler function passed are always run before the ones that
  are given to the middleware when it is applied."
  [handler]
  (fn [hnd]
    (fn [event-type event-data]
      (handler event-type event-data)
      (hnd event-type event-data))))

(defn log-when
  "Takes a predicate and if it returns true, logs the event before passing it on.

  The predicate must take the event-type and the event-data, and return a truthy
  value if it should log. If the value is a valid level at which to log, that
  logging level will be used."
  [filter]
  (fn [handler]
    (fn [event-type event-data]
      (when-let [logging-level (filter event-type event-data)]
        (if (#{:trace :debug :info :warn :error :fatal} logging-level)
          (log/log logging-level (pr-str event-type event-data))
          (log/debug event-type event-data)))
      (handler event-type event-data))))

(defn filter
  "Makes middleware that only calls the handler if `pred` returns truthy.

  `pred` is a predicate expected to take the event-type and event-data."
  [pred]
  (fn [handler]
    (fn [event-type event-data]
      (when (pred event-type event-data)
        (handler event-type event-data)))))

(defn data-mapping
  "Makes a transform function for use with [[map]] that operates on event-data.

  The resulting function is from a vector of event-type and event-data to a
  vector of event-type and event-data. The event-type is passed through without
  change, and event-data is transformed by `f`."
  [f]
  (fn [[event-type event-data]]
    [event-type (f event-data)]))

(defn map
  "Makes a middleware which runs `f` over events before passing to the handler.

  `f` is a function of a vector of event-type and event-data to a vector of
  event-type and event-data which are then passed to the handler."
  [f]
  (fn [handler]
    (fn [event-type event-data]
      (let [[event-type event-data] (f [event-type event-data])]
        (handler event-type event-data)))))

(defn transduce
  "Makes a middleware which takes a transducer and runs it over event-data."
  [xf]
  (let [reduced (volatile! false)
        reducer (fn [send-event [event-type event-data]]
                  (send-event event-type event-data))
        reducer (xf reducer)]
    (fn [handler]
      (fn [event-type event-data]
        (when-not @reduced
          (let [res (reducer handler [event-type event-data])]
            (when (reduced? res)
              (vreset! reduced true))
            res))))))

;; =============================================================================
;; Default middleware

(def ignore-bot-messages
  "Middleware which won't call the handler if the event is a message from a bot."
  (filter
   (fn [event-type event-data]
     (if (#{:message-create :message-update} event-type)
       (when-not (:bot (:author event-data))
         true)
       true))))
