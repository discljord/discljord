(ns examples.basic-bot
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [discljord.messaging :as m]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [org.eclipse.jetty websocket.client.WebSocketClient util.ssl.SslContextFactory])
  (:use com.rpl.specter))

(def token (str/trim (slurp "resources/token.txt")))

(def bot-owner-id (str/trim (slurp "resources/owner.txt")))

(defn add-quote!
  [bot guild-id user q]
  (println "Quote added to user:" user "\n" q)
  (bots/update-guild-state bot guild-id :quotes assoc user
                           (if-let [user-quotes (get
                                                 (:quotes
                                                  (bots/guild-state bot guild-id))
                                                 user)]
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

(defn- gen-first-match-body
  [finished ret-val clauses]
  (if (> (count clauses) 0)
    (if-not (= (first clauses) :default)
      (let [[test & body] (first clauses)]
        `(when-not @~finished
           (when-let ~test
             (reset! ~ret-val (do ~@body))
             (reset! ~finished true))
           ~(gen-first-match-body finished ret-val (rest clauses))))
      `(when-not @~finished
         (reset! ~ret-val (do ~@(rest clauses)))))))

(defmacro first-match
  [& body]
  (let [finished (gensym)
        ret-val (gensym)]
    `(let [~finished (atom nil)
           ~ret-val (atom nil)]
       ~(gen-first-match-body finished ret-val body)
       @~ret-val)))

(defn proc-command
  [bot {event-type :event-type
        {:keys [channel-id content type mentions]
         {user-id :id :keys [username bot?] :as author} :author :as data} :event-data}]
  (when-not (= event-type :disconnect)
    (case type
      0 (first-match
         ([_ (re-find (re-pattern
                       (str "^"
                            (:prefix (bots/state bot))
                            "disconnect[\\s\\r\\n]*$"))
                      content)]
          (if (= user-id bot-owner-id)
            (do
              (m/send-message bot channel-id "Goodbye!")
              (disconnect bot))
            (do
              (m/send-message bot channel-id "Non-owning users cannot deactivate this bot."))))
         ([[_ user quote]
           (re-find (re-pattern
                     (str "^"
                          (:prefix (bots/state bot))
                          "quote\\s+add\\s+([^\\s\\r\\n]+)\\s+((.|\\r|\\n)*)"))
                    content)]
          (when-let [guild (:guild-id (m/get-channel bot channel-id))]
            (add-quote! bot guild user quote)
            (m/send-message bot channel-id
                            (str "Quote \"" quote
                                 "\" added to user: " user))))
         ([[_ _ user] (re-find (re-pattern
                                (str "^"
                                     (:prefix (bots/state bot))
                                     "quote(\\s*|\\s+([^\\s\\r\\n]*)\\s*)$"))
                               content)]
          (when-let [guild (:guild-id (m/get-channel bot channel-id))]
            (m/send-message bot channel-id (random-quote bot guild user))))))))

(def quotes-file "resources/quotes.edn")

(defn save-quotes
  [bot]
  (let [s (prn-str (:guilds (::bots/internal-state (bots/state bot))))]
    (println "Saving out the quotes database...")
    (spit quotes-file s)))

(def stop-channel (a/chan 1))

(defn proc-disconnect
  [bot {:keys [event-type event-data] :as event}]
  (save-quotes bot)
  (a/>!! stop-channel :stop))

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}
                {:event-channel (a/chan 1)
                 :event-type :disconnect
                 :event-handler (fn [& args]
                                  (apply @#'proc-disconnect args))}])

(def initial-guild-state (let [init-state
                               (try (read-string (slurp quotes-file))
                                    (catch Exception e nil))]
                           (if (seq init-state)
                             init-state
                             [])))

(def init-state {:prefix "!"})

(defonce basic-bot (atom (bots/create-bot {:token token
                                           :listeners listeners
                                           :init-state init-state
                                           :guilds initial-guild-state})))
(comment
  (def basic-bot (atom (bots/create-bot {:token token
                                         :listeners listeners
                                         :init-state init-state
                                         :guilds initial-guild-state}))))

(defn connected?
  [bot]
  false)

(def max-message-size 100000)

(defn -main
  [& args]
  (when (connected? @basic-bot)
    (disconnect @basic-bot))
  (swap! basic-bot bots/init-shards)
  (bots/start-message-proc! (:event-channel @basic-bot) (:listeners @basic-bot))
  (bots/start-listeners! @basic-bot)
  (let [client (WebSocketClient. (SslContextFactory.))]
    (.setMaxTextMessageSize (.getPolicy client) max-message-size)
    (.start client)
    (swap! (select-one [ATOM :shards FIRST :socket-state] basic-bot)
           assoc :socket
           (conn/connect-websocket (select-one [ATOM :shards FIRST :gateway] basic-bot)
                                   (:token @basic-bot)
                                   (select-one [ATOM :shards FIRST :shard-id] basic-bot)
                                   (:event-channel @basic-bot)
                                   (select-one [ATOM :shards FIRST :socket-state] basic-bot)
                                   client)))
  (a/go-loop []
    (let [[message port] (a/alts! [(a/timeout 30000) stop-channel])]
      (println "Autosave time. Next autosave in 5 minutes.")
      (save-quotes @basic-bot)
      (if-not (= port stop-channel)
        (recur)
        (println "Closing autosave loop.")))))
