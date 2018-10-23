(ns discljord.messaging
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [discljord.http :refer [api-url]]
            [discljord.messaging.impl :as impl]
            [clojure.spec.alpha :as s]
            [discljord.messaging.specs :as ms]
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

  Keyword Arguments:
  :user-agent changes the User-Agent header sent to Discord.
  :tts is a boolean, defaulting to false, which tells Discord to read
       your message out loud."
  [conn channel msg & {:keys [user-agent tts] :as opts}]
  (let [p (promise)]
    (a/put! conn [{::ms/action :create-message
                   ::ms/major-variable {::ms/major-variable-type ::ds/channel-id
                                        ::ms/major-variable-value channel}}
                  p
                  msg
                  :user-agent user-agent])
    p))
(s/fdef send-message!
  :args (s/cat :conn ::ds/channel
               :channel ::ds/channel-id
               :msg ::ms/message
               :keyword-args (s/keys* :opt-un [::ms/user-agent
                                               ::ms/tts]))
  :ret ::ds/promise)

(defn get-guild-roles!
  [conn guild-id & {:keys [user-agent]}]
  (let [p (promise)]
    (a/put! conn [{::ms/action :get-guild-roles
                   ::ms/major-variable {::ms/major-variable-type ::ds/guild-id
                                        ::ms/major-variable-value guild-id}}
                  p
                  guild-id
                  :user-agent user-agent])
    p))
(s/fdef get-guild-roles!
  :args (s/cat :conn ::ds/channel
               :guild-id ::ds/guild-id
               :keyword-args (s/keys* :opt-un [::ms/user-agent])))
