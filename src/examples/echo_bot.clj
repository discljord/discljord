(ns examples.echo-bot
  (:require [discljord.core :as discord :refer [defcommands]]
            [clojure.core.async :as a]
            [clojure.java.io :as io]))

(def token (slurp (io/resource "token.txt")))

(defcommands echo-commands
   {:keys [channel user message event-queue] :as params}
   {"echo" {:help-text "Sends back what was messaged to this bot!"
            :callback (discord/send-message channel
                                            (str "User "
                                                 (discord/mention user)
                                                 " said:\n" message))}
    "disconnect" {:help-text "Disconnects the bot from Discord."
                  :callback (a/>!! event-queue {:event-type :disconnect :event-data nil})}})

(def echo-bot nil #_(discord/create-bot {:token token :listeners echo-commands}))

(defn -main
  []
  (discord/connect-bot! echo-bot))
