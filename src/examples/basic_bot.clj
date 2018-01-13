(ns examples.basic-bot
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.java.io :as io])
  (:use com.rpl.specter))

(def token (str/trim (slurp (io/resource "token.txt"))))

(defn disconnect
  [bot]
  (transform [:shards ALL :socket-state] conn/disconnect-websocket bot)
  (a/>!! (:event-channel bot) {:event-type :disconnect :event-data nil}))

(defn proc-command
  [bot {event-type :event-type
        {:keys [channel-id content type mentions]
         author :author :as data} :event-data}]
  (println "COMMAND GOT PROCESSED")
  (case type
    0 (do ;; A normal message has been sent
        (cond
          (= 0 (str/index-of content (str (:prefix (bots/state bot)) "disconnect")))
          (a/>!! (:event-channel bot) {:event-type :disconnect :event-data nil}))
        )
    7 (do ;; A user join message has been sent
        )))

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}])

(def initial-state (merge {:prefix "!" :prepend-to-messages "\u200B"}
                          (let [init-state
                                (try (read-string (slurp (io/resource "quotes.edn")))
                                     (catch Exception e nil))]
                            (if (seq init-state)
                              init-state
                              {}))))

(defonce basic-bot (atom (bots/create-bot {:token token
                                           :listeners listeners
                                           :init-state initial-state})))

(defn -main
  [& args]
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
