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
  (:refer-clojure :rename {concat concat-seq}))

(defn concat
  "Takes a handler function and creates a middleware which concats the handlers.

  The events in the handler function passed are always run before the ones that
  are given to the middleware when it is applied."
  [handler]
  (fn [hnd]
    (fn [event-type event-data]
      (handler event-type event-data)
      (hnd event-type event-data))))
