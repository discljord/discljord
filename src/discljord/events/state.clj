(ns discljord.events.state
  "Middleware to cache all the state that Discord sends."
  (:require
   [clojure.tools.logging :as log]
   [discljord.events :as e]
   [discljord.events.middleware :as mdw]))

(def ^:private caching-handlers
  "Handler map for all state-caching events."
  {})

(defn caching-middleware
  "Creates a middleware that caches all Discord event data in `state`.

  `state` must be an [[clojure.core/atom]]."
  [state]
  (mdw/concat
   #(e/dispatch-handlers #'caching-handlers %1 %2 state)))
