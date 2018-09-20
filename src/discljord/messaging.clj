(ns discljord.messaging
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.messaging.impl :as impl]
            [clojure.spec.alpha :as s]
            [org.httpkit.client :as http]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]))

;; Start a process which will send messages given to it over a channel
;; Have an api for communicating with that process, which will return promises
;; with the needed response
