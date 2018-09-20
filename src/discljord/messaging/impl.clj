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

(defn start!
  "Starts the internal representation"
  []
  (let [process (atom nil)]
    process)
  ;; TODO: Create the go loop which will handle taking things off the channel
  ;;       and then dispatch the http request
  )
(s/fdef start!
  :args (s/cat)
  :ret (ds/atom-of? ::ds/process))

(defn stop!
  "Stops the internal representation"
  [process]
  nil)
(s/fdef stop!
  :args (s/cat :process (ds/atom-of? ::ds/process))
  :ret nil?)

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
  "Dispatches a call to Discord's HTTP servers,
  handling rate limiting if needed.

  Takes the current process state, the action being
  performed, and the data required to perform that action.
  Returns a new process state."
  (fn [process endpoint & data]
    endpoint))

(defmethod dispatch-http :create-message
  [process endpoint & [token channel & {:keys [user-agent] :as opts}]]
  ;; Check rate limit state
  ;; If it's limited, put it back on the end of the queue
  ;; Otherwise, do the call
  (let [token (bot-token token)
        response @(http/post (api-url (str "/channels/"
                                           channel
                                           "/messages"))
                             {:headers {"Authorization" token
                                        "User-Agent" (str "DiscordBot ("
                                                          "https://github.com/IGJoshua/discljord"
                                                          ", "
                                                          "0.1.0-SNAPSHOT"
                                                          ") "
                                                          user-agent)}})
        endpoint-map {::ds/action endpoint
                      ::ds/major-variable channel}
        process (transform [::ds/rate-limits
                            ::ds/endpoint-specific-rate-limits
                            endpoint-map]
                           #(update-rate-limit % response)
                           process)
        body (:body response)]
    (when (= (:code body)
           429)
      ;; This shouldn't happen for anything but emoji stuff, so this shouldn't happen
      (log/error "Bot triggered rate limit response in create-message.")
      ;; Resend the event to dispatch, hopefully this time not brekaing the rate limit
      (a/put! (::ds/channel process) [:create-message token channel opts]))
    process))
