(ns discljord.messaging
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.messaging.impl :as impl]
            [clojure.spec.alpha :as s]
            [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]))

;; Start a process which will send messages given to it over a channel
;; Have an api for communicating with that process, which will return futures
;; with the needed response

;; Internally must handle rate limiting.

;; Must parse rate limit headers

;; Both endpoint specific rate limit, and global rate limit
;; If no endpoint specific rate limit is provided, use the global one

;; rate limits are separate for each endpoint, including each value for a major parameter
;; Current major parameters include channel_id guild_id and webhook_id

;; NOTE: Rate limits for emoji don't follow the same conventions, and are handled per-guild

;; Rate limit headers have these properties:
;; X-RateLimit-Global: (true) Returned only on a HTTP 429 response if the rate limit headers returned are of the global rate limit (not per-route)
;; X-RateLimit-Limit: Number of requests that can be made
;; X-RateLimit-Remaining: Remaining number of requests than can be made between now and epoch time
;; X-RateLimit-Reset: Epoch time (seconds since 00:00:00 UTC on January 1, 1970) at which the rate limit resets

;; If you exceed a rate limit, you'll get a json response body on an HTTP 429 response code
;; message: message saying you're getting rate limited
;; retry_after: number of milliseconds before trying again
;; global: whether or not you are being rate-limited globally.

(defn register-connection!
  "Takes a token and starts a messaging process to allow sending messages
  and other events to discord."
  [token]
  nil)
(s/fdef register-connection!
  :args (s/cat :token ::ds/token)
  :ret nil?)

(defn stop-connection!
  [token]
  nil)
(s/fdef stop-connection!
  :args (s/cat :token ::ds/token)
  :ret nil?)

;; Put a request on the channel for this major endpoint, return a promise with the return value
(defn send-message!
  [token channel & {:keys [] :as opts}]
  )
(s/fdef send-message!
  :args (s/cat :token ::ds/token
               :channel ::ds/channel-id
               :options (s/keys :opt-un []))
  :ret nil?)

