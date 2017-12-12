(ns examples.echo-bot
  (:require [discljord.core :as discord :refer [defbot]]
            [clojure.core.async :as a]
            [clojure.java.io :as io]))

(def token (slurp (io/resource "token.txt")))

(defbot echo-bot
  :token token
  :prefix "!"
  :commands {"echo"
             {:help-text "Sends back what was messaged to this bot!"
              :callback (fn [{:keys [channel user message] :as params}]
                          (discord/send-message channel
                                                (str "User "
                                                     (discord/mention user)
                                                     " said:\n" message)))}
             "disconnect"
             {:help-text "Disconnects the bot from Discord."
              :callback (fn [{:keys [event-queue] :as params}]
                          (a/>!! event-queue {:event-type :disconnect :event-data nil}))}}
  :listeners [{:event-type :channel-create
               :event-handler
               (fn [{:keys [event-type]
                     {:keys [id type name] :as event-data} :event-data
                     :as event}]
                 (if (and (not= type 2) (not= type 4))
                   (discord/send-message id (if-not (nil? name)
                                              (str "Hello " name "!")
                                              "Hello new channel!"))))}])

(defn -main
  []
  #_(discord/connect-bot! echo-bot))
