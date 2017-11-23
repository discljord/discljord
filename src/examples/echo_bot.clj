(ns examples.echo-bot
  (:require [discljord.core :as discord]
            [clojure.java.io :as io]))

(def token (slurp (io/resource "token.txt")))
(def main-bot (atom (create-bot {:token token :trigger-type :prefix})))

(defcommands main-bot
  {:keys [channel user message] :as params}
  {"echo" {:help-text "Sends back what was messaged to this bot!"
           :callback (discord/send-message channel (str "User " (discord/mention user) " said:\n" message))}
   "disconnect" {:help-text "Disconnects the bot from Discord."
                 :callback (discord/disconnect-bot @main-bot)}})

(defn -main
  []
  (swap! main-bot discord/connect-bot))
