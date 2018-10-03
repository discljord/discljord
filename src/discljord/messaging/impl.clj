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

;; NOTE: Rate limits for emoji don't follow the same conventions, and are handled per-guild
;;       as a result, expect lots of 429's

(defmulti dispatch-http
  "Takes a process and endpoint, and dispatches an http request.
  Must return the response object from the call to allow the runtime
  to update the rate limit."
  (fn [process endpoint data]
    (::ds/action endpoint)))
(s/fdef dispatch-http
  :args (s/cat :process (ds/atom-of? ::ds/process)
               :endpoint ::ds/endpoint
               :data (s/coll-of any?)))

(defn auth-headers
  [token user-agent]
  {"Authorization" (bot-token token)
   "User-Agent"
   (str "DiscordBot ("
        "https://github.com/IGJoshua/discljord"
        ", "
        "0.1.0-SNAPSHOT"
        ") "
        user-agent)
   "Content-Type" "application/json"})

(defmethod dispatch-http :create-message
  [process endpoint [prom msg & {:keys [user-agent tts]
                                 :or {tts false}
                                 :as opts}]]
  (let [channel (-> endpoint
                    ::ds/major-variable
                    ::ds/major-variable-value)
        response @(http/post (api-url (str "/channels/" channel "/messages"))
                             {:headers (auth-headers (::ds/token @process) user-agent)
                              :body (json/write-str {:content msg
                                                     :tts tts})})
        json-msg (json/read-str (:body response))]
    (deliver prom (if json-msg
                    (clean-json-input json-msg)
                    nil))
    response))

(defmethod dispatch-http :get-guild-roles
  [process endpoint [prom guild-id & {:keys [user-agent]}]]
  (let [channel (-> endpoint
                    ::ds/major-variable
                    ::ds/major-variable-value)
        response @(http/get (api-url (str "/guilds/" guild-id "/roles"))
                            {:headers (auth-headers (::ds/token @process) user-agent)})
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
    new-rate-limit))
(s/fdef update-rate-limit
  :args (s/cat :rate-limit (s/nilable ::ds/rate-limit)
               :response (s/keys :req-un [::headers])))

(defn start!
  "Takes a token for a bot and returns a channel to communicate with the
  message sending process."
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
    (::ds/channel @process)))
(s/fdef start!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop!
  "Takes the channel returned from start! and stops the messaging process."
  [channel]
  (a/put! channel [:disconnect]))
(s/fdef stop!
  :args (s/cat :channel ::ds/channel)
  :ret nil?)
