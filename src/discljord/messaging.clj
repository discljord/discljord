(ns discljord.messaging
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.messaging.impl :as impl]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as a]))

;; Start a process which will send messages given to it over a channel
;; Have an api for communicating with that process, which will return promises
;; with the needed response

(defn start-connection!
  [token]
  (impl/start! token))
(s/fdef start-connection!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop-connection!
  [conn]
  (impl/stop! conn))
(s/fdef stop-connection!
  :args (s/cat :conn ::ds/channel))

(defn send-message!
  [conn channel msg & {:keys [user-agent tts] :as opts}]
  (let [p (promise)]
    (a/put! conn [{::ds/action :create-message
                   ::ds/major-variable {::ds/major-variable-type ::ds/channel-id
                                        ::ds/major-variable-value channel}}
                  msg
                  p
                  {:user-agent user-agent}])
    p))
(s/fdef send-message!
  :args (s/cat :conn ::ds/channel
               :channel ::ds/channel-id
               :msg ::ds/message)
  :ret ::ds/promise)
