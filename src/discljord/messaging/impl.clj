(ns discljord.messaging.impl
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.util :refer [bot-token]]
            [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]))

;; Make a channel that all incoming requests will come over
;; When a request needs to be made, send it over the channel,
;; which will check on the rate limit for that particular endpoint.

;; NOTE(Joshua): Ask the discord API chat if each endpoint that has a global
;;               rate limit all interact with the same rate limit, or if the global
;;               limit is just a default, and is still applied separately to each endpoint

;; Internally must handle rate limiting.

;; Must parse rate limit headers

;; Both endpoint specific rate limit, and global rate limit
;; If no endpoint specific rate limit is provided, use the global one

;; rate limits are separate for each endpoint, including each value for a major parameter
;; Current major parameters include channel_id guild_id and webhook_id

;; NOTE: Rate limits for emoji don't follow the same conventions, and are handled per-guild
;;       as a result, expect lots of 429's

;; Rate limit headers have these properties:
;; X-RateLimit-Global: (true) Returned only on a HTTP 429 response if the rate limit
;;                     headers returned are of the global rate limit (not per-route)
;; X-RateLimit-Limit: Number of requests that can be made
;; X-RateLimit-Remaining: Remaining number of requests than can be made between now and epoch time
;; X-RateLimit-Reset: Epoch time (seconds since 00:00:00 UTC on January 1, 1970) at
;;                    which the rate limit resets

;; If you exceed a rate limit, you'll get a json response body on an HTTP 429 response code
;; message: message saying you're getting rate limited
;; retry_after: number of milliseconds before trying again
;; global: whether or not you are being rate-limited globally.

(defn update-rate-limit
  "Takes a rate-limit and a response and returns an updated rate-limit.

  If a rate limit headers are included in the response, then the rate
  limit is updated to them, otherwise the existing rate limit is used,
  but the remaining limit is decremented."
  [rate-limit response]
  (let [headers (:headers response)
        rate (or (get headers "X-RateLimit-Limit")
                 (::ds/rate rate-limit))
        remaining (or (get headers "X-RateLimit-Remaining")
                      (dec (::ds/remaining rate-limit)))
        reset (or (get headers "X-RateLimit-Reset")
                  (::ds/reset rate-limit))
        global (or (get headers "X-RateLimit-Global")
                   (::ds/global rate-limit ::not-found))
        new-rate-limit {::ds/rate rate
                        ::ds/remaining remaining
                        ::ds/reset reset}
        new-rate-limit (if-not (= global ::not-found)
                         (assoc new-rate-limit ::ds/global global)
                         new-rate-limit)]
    new-rate-limit))
(s/fdef update-rate-limit
  :args (s/cat :rate-limit ::ds/rate-limit
               :response (s/keys :req-un [::headers])))

(defmulti dispatch-http
  "Takes a process and endpoint, and dispatches an http request.
  On a 429 response, add it back to the queue and update the rate limit."
  (fn [process endpoint data]
    (::ds/action endpoint)))
(s/fdef dispatch-http
  :args (s/cat :process ::ds/process
               :endpoint ::ds/endpoint
               :data (s/coll-of any?
                                :kind vector?)))

(defmethod dispatch-http :create-message
  [process endpoint [{:keys [user-agent] :as opts}]]
  (let [token (bot-token (::ds/token @process))
        channel (-> endpoint
                    ::ds/major-variable
                    ::ds/major-variable-value)
        response @(http/post (api-url (str "/channels/" channel "/messages"))
                             {:headers
                              {"Authorization" token
                               "User-Agent"
                               (str "DiscordBot ("
                                    "https://github.com/IGJoshua/discljord"
                                    ", "
                                    "0.1.0-SNAPSHOT"
                                    ") "
                                    user-agent)}})
        body (:body response)]
    (transform [ATOM
                ::ds/rate-limits
                ::ds/endpoint-specific-rate-limits
                endpoint]
               #(update-rate-limit % response)
               process)
    (when (= (:code body)
             429)
      ;; This shouldn't happen for anything but emoji stuff, so this shouldn't happen
      (log/error "Bot triggered rate limit response in create-message.")
      ;; Resend the event to dispatch, hopefully this time not brekaing the rate limit
      (a/>! (::ds/channel @process) [:create-message opts]))))

(defn rate-limited?
  "Takes a process and an endpoint and checks to see if the
  process is currently rate limited."
  [process endpoint]
  false)
(s/fdef rate-limited?
  :args (s/cat :process ::ds/process
               :endpoint ::ds/endpoint)
  :ret boolean?)

(defn start!
  "Starts the internal representation"
  [token]
  (let [process (atom {::ds/rate-limits {::ds/endpoint-specific-rate-limits {}}
                       ::ds/channel (a/chan 1000)
                       ::ds/running? true
                       ::ds/token token})]
    (a/go-loop []
      (let [[endpoint & event-data :as event] (a/<! (::ds/channel @process))]
        (if (rate-limited? process endpoint)
          (a/>! (::ds/channel @process) event)
          (dispatch-http process endpoint event-data)))
      (when (::ds/running? @process)
        (recur)))
    process))
(s/fdef start!
  :args (s/cat)
  :ret (ds/atom-of? ::ds/process))

(defn stop!
  "Stops the internal representation"
  [process]
  (setval [ATOM ::ds/running?] false process))
(s/fdef stop!
  :args (s/cat :process (ds/atom-of? ::ds/process))
  :ret nil?)
