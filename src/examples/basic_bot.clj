(ns examples.basic-bot
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [discljord.messaging :as m]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:use com.rpl.specter))

(def token (str/trim (slurp "resources/token.txt")))

(def bot-owner-id (str/trim (slurp "resources/owner.txt")))

(defn add-quote!
  [bot guild-id user q]
  #_(println "Quote added to user:" user "\n" q)
  (doall (map prn [bot guild-id user q]))
  (bots/update-guild-state bot guild-id :quotes assoc user
                           (if-let [user-quotes (get (:quotes (bots/guild-state bot guild-id)) user)]
                             (conj user-quotes q)
                             [q])))

(defn random-quote
  [bot guild-id user]
  (println "Random quote requested!" user)
  (if user
    (if-let [quote-list (get (:quotes (bots/guild-state bot guild-id)) user)]
      (str user ": " (rand-nth quote-list))
      (str "No quotes found for " user))
    (if (seq (:quotes (bots/guild-state bot guild-id)))
      (let [[user quotes] (rand-nth (seq (:quotes (bots/guild-state bot guild-id))))]
        (str user ": " (rand-nth quotes)))
      "No quotes currently in the database")))

(defn disconnect
  [bot]
  (a/>!! (:event-channel bot) {:event-type :disconnect :event-data nil})
  (transform [:shards ALL :socket-state] conn/disconnect-websocket bot))

(defn proc-command
  [bot {event-type :event-type
        {:keys [channel-id content type mentions]
         {user-id :id :keys [username bot?] :as author} :author :as data} :event-data}]
  (case type
    0 (do ;; A normal message has been sent
        ;; Check to seee what message is being sent specifically
        (cond
          (and (= 0 (str/index-of content (str (:prefix (bots/state bot)) "disconnect")))
               (= user-id bot-owner-id))
          (disconnect bot)
          (= 0 (str/index-of content (str (:prefix (bots/state bot)) "quote")))
          (if (> (count (str/split content #"\s")) 1)
            (let [[q command & args] (str/split content #"\s")]
              (case command
                "add" (when-let [guild (:guild-id (m/get-channel bot channel-id))]
                        (add-quote! bot (BigInteger. guild) (first args) (str/join " " (rest args)))
                        (m/send-message bot channel-id
                                        (str "Quote \"" (str/join " " (rest args))
                                             "\" added to user: " (first args))))
                (when-let [guild (:guild-id (m/get-channel bot channel-id))]
                  (m/send-message bot channel-id (random-quote bot (BigInteger. guild) command)))))
            (when-let [guild (:guild-id (m/get-channel bot channel-id))]
              (m/send-message bot channel-id (random-quote bot (BigInteger. guild) nil))))))))

(def quotes-file "resources/quotes.edn")

(defn proc-disconnect
  [bot {:keys [event-type event-data] :as event}]
  (spit quotes-file (prn-str (:guilds (::bots/internal-state (bots/state bot))))))

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}
                {:event-channel (a/chan 1)
                 :event-type :disconnect
                 :event-handler (fn [& args]
                                  (apply @#'proc-disconnect args))}])

(def initial-guild-state (let [init-state
                               (try (read-string (slurp "resources/quotes.edn"))
                                    (catch Exception e nil))]
                           (if (seq init-state)
                             init-state
                             [])))

(def initial-state {:prefix "!" :prepend-to-messages "\u200B"})

(defonce basic-bot (atom (bots/create-bot {:token token
                                           :listeners listeners
                                           :init-state initial-state
                                           :guilds initial-guild-state})))
(comment
  (def basic-bot (atom (bots/create-bot {:token token
                                         :listeners listeners
                                         :init-state initial-state
                                         :guilds initial-guild-state}))))

(defn connected?
  [bot]
  false)

(defn -main
  [& args]
  (when (connected? @basic-bot)
    (disconnect @basic-bot))
  (swap! basic-bot bots/init-shards)
  (bots/start-message-proc! (:event-channel @basic-bot) (:listeners @basic-bot))
  (bots/start-listeners! @basic-bot)
  (swap! (select-one [ATOM :shards FIRST :socket-state] basic-bot)
         assoc :socket
         (conn/connect-websocket (select-one [ATOM :shards FIRST :gateway] basic-bot)
                                 (:token @basic-bot)
                                 (select-one [ATOM :shards FIRST :shard-id] basic-bot)
                                 (:event-channel @basic-bot)
                                 (select-one [ATOM :shards FIRST :socket-state] basic-bot))))
