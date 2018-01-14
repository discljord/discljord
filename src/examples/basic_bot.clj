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

(defonce quotes (atom {}))

(defn add-quote!
  [user q]
  (println "Quote added to user:" user "\n" q)
  (swap! quotes #(assoc % user (conj (if-let [user-quotes (get % user)]
                                       user-quotes
                                       []) q))))

(defn random-quote
  [user]
  (println "Random quote requested!" user)
  (if user
    (if-let [quote-list (get @quotes user)]
      (str user ": " (rand-nth quote-list))
      (str "No quotes found for " user))
    (if (seq @quotes)
      (let [[user quotes] (rand-nth (seq @quotes))]
        (str user ": " (rand-nth quotes)))
      "No quotes currently in the database")))

(defn disconnect
  [bot]
  (transform [:shards ALL :socket-state] conn/disconnect-websocket bot)
  (a/>!! (:event-channel bot) {:event-type :disconnect :event-data nil}))

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
                "add" (do (add-quote! (first args) (str/join " " (rest args)))
                          (m/send-message bot channel-id (str "Quote \"" (str/join " " (rest args)) "\" added to user: " (first args))))
                (m/send-message bot channel-id (random-quote command))))
            (m/send-message bot channel-id (random-quote nil)))))))

(def quotes-file "resources/quotes.edn")

(defn proc-disconnect
  [bot {:keys [event-type event-data] :as event}]
  (spit quotes-file (prn-str @quotes)))

(defn get-quotes
  []
  (edn/read-string (slurp quotes-file)))

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}
                {:event-channel (a/chan 1)
                 :event-type :disconnect
                 :event-handler (fn [& args]
                                  (apply @#'proc-disconnect args))}])

(def initial-state (merge {:prefix "!" :prepend-to-messages "\u200B"}
                          (let [init-state
                                (try (read-string (slurp "resources/quotes.edn"))
                                     (catch Exception e nil))]
                            (if (seq init-state)
                              init-state
                              {}))))

(defonce basic-bot (atom (bots/create-bot {:token token
                                           :listeners listeners
                                           :init-state initial-state})))

(defn -main
  [& args]
  (reset! quotes (get-quotes))
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
