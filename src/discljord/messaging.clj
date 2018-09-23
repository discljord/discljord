(ns discljord.messaging
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.messaging.impl :as impl]
            [clojure.spec.alpha :as s]
            [clojure.core.async :as a]))

(defn start-connection!
  "Takes a token for a bot, and returns a channel which is passed
  to the various messaging functions."
  [token]
  (impl/start! token))
(s/fdef start-connection!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop-connection!
  "Takes a channel returned by start-connection! and stops the associated
  connection."
  [conn]
  (impl/stop! conn))
(s/fdef stop-connection!
  :args (s/cat :conn ::ds/channel))

(defn send-message!
  "Takes a core.async channel returned by start-connection!, a Discord
  channel id as a string, and the message you want to send to Discord.

  Also allows keyword arguments for the following:
  :user-agent changes the User-Agent header sent to Discord.
  :tts is a boolean, defaulting to false, which tells Discord to read
       your message out loud."
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
