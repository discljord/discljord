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
  ```")
