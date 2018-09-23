(ns discljord.messaging.impl
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.util :refer [bot-token clean-json-input]]
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

(defmulti dispatch-http
  "Takes a process and endpoint, and dispatches an http request.
  Must return the response object from the call to allow the runtime
  to update the rate limit."
  (fn [process endpoint data]
    (::ds/action endpoint)))
(s/fdef dispatch-http
  :args (s/cat :process ::ds/process
               :endpoint ::ds/endpoint
               :data (s/coll-of any?
                                :kind vector?)))

(defmethod dispatch-http :create-message
  [process endpoint [msg prom {:keys [user-agent tts] :as opts :or {tts false
                                                                    user-agent nil}}]]
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
                                    user-agent)
                               "Content-Type" "application/json"}
                              :body (json/write-str {:content msg
                                                     :tts tts})})
        json-msg (json/read-str (:body response))]
    (deliver prom (if json-msg
                    (clean-json-input json-msg)
                    nil))
    response))

(defn rate-limited?
  "Takes a process and an endpoint and checks to see if the
  process is currently rate limited."
  [process endpoint]
  (let [specific-limit (select-first [::ds/rate-limits
                                      ::ds/endpoint-specific-rate-limits
                                      (keypath endpoint)]
                                     process)
        global-limit (select-first [::ds/rate-limits
                                    ::ds/global-rate-limit]
                                   process)
        remaining (or (::ds/remaining specific-limit)
                      (::ds/remaining global-limit)
                      1)
        reset (or (::ds/reset specific-limit)
                  (::ds/reset global-limit)
                  (long (/ (System/currentTimeMillis) 1000.0)))
        time (long (/ (System/currentTimeMillis) 1000.0))]
    (and (<= remaining 0)
         (< time reset))))
(s/fdef rate-limited?
  :args (s/cat :process ::ds/process
               :endpoint ::ds/endpoint)
  :ret boolean?)

(defn update-rate-limit
  "Takes a rate-limit and a response and returns an updated rate-limit.

  If a rate limit headers are included in the response, then the rate
  limit is updated to them, otherwise the existing rate limit is used,
  but the remaining limit is decremented."
  [rate-limit response]
  (let [headers (:headers response)
        rate (or (Long/parseLong (:x-ratelimit-limit headers))
                 (::ds/rate rate-limit))
        remaining (or (Long/parseLong (:x-ratelimit-remaining headers))
                      (dec (::ds/remaining rate-limit)))
        reset (or (Long/parseLong (:x-ratelimit-reset headers))
                  (::ds/reset rate-limit))
        global-str (:x-ratelimit-global headers)
        global (or (when global-str
                     (Long/parseLong global-str))
                   (::ds/global rate-limit ::not-found))
        new-rate-limit {::ds/rate rate
                        ::ds/remaining remaining
                        ::ds/reset reset}
        new-rate-limit (if-not (= global ::not-found)
                         (assoc new-rate-limit ::ds/global global)
                         new-rate-limit)]
    (reset! user/response [rate-limit new-rate-limit])
    new-rate-limit))
(s/fdef update-rate-limit
  :args (s/cat :rate-limit (s/nilable ::ds/rate-limit)
               :response (s/keys :req-un [::headers])))

(defn start!
  "Starts the internal representation"
  [token]
  (let [process (atom {::ds/rate-limits {::ds/endpoint-specific-rate-limits {}}
                       ::ds/channel (a/chan 1000)
                       ::ds/token token})]
    (a/go-loop []
      (let [[endpoint & event-data :as event] (a/<! (::ds/channel @process))]
        (when-not (= endpoint :disconnect)
          (if (rate-limited? @process endpoint)
            (a/>! (::ds/channel @process) event)
            (let [response (a/<! (a/thread (dispatch-http process endpoint event-data)))]
              (transform [ATOM
                          ::ds/rate-limits
                          ::ds/endpoint-specific-rate-limits
                          (keypath endpoint)]
                         #(update-rate-limit % response)
                         process)
              (when (= (:status response)
                       429)
                ;; This shouldn't happen for anything but emoji stuff, so this shouldn't happen
                (log/error "Bot triggered rate limit response.")
                ;; Resend the event to dispatch, hopefully this time not brekaing the rate limit
                (a/>! (::ds/channel @process) event))))
          (recur))))
    (alter-var-root #'user/process (fn [_] process))
    (::ds/channel @process)))
(s/fdef start!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop!
  "Stops the internal representation"
  [channel]
  (a/put! channel [:disconnect]))
(s/fdef stop!
  :args (s/cat :channel ::ds/channel)
  :ret nil?)
