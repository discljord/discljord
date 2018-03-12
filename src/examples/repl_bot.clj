(ns examples.repl-bot
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [discljord.messaging :as m]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp])
  (:import [org.eclipse.jetty websocket.client.WebSocketClient util.ssl.SslContextFactory])
  (:use com.rpl.specter))

;; Create a map of channelss to queues. Start different threads for each queue.
;; Each thread will take from its queue and run the code from it in a "sandbox"

(def token (str/trim (slurp "resources/token.txt")))
(def bot-owner-id (str/trim (slurp "resources/owner.txt")))

(defn disconnect
  [bot]
  (a/>!! (:event-channel bot) {:event-type :stop :event-data nil})
  (Thread/sleep 1000)
  (a/>!! (:event-channel bot) {:event-type :disconnect :event-data nil})
  (transform [:shards ALL :socket-state] #(when % (conn/disconnect-websocket %)) bot))

(defn repl-command
  [s]
  (let [return-result (atom nil)
        print-result (with-out-str
                       (binding [*err* *out*]
                         (try
                           (reset! return-result (eval (read-string s)))
                           (catch Exception e
                             (.printStackTrace e)))))]
    [@return-result (if (> (count print-result) 1500)
                      (subs print-result 0 1500)
                      print-result)]))

(defn proc-command
  [bot {event-type :event-type
        {:keys [channel-id content type mentions]
         {user-id :id :keys [username bot?] :as author} :author :as data} :event-data}]
  (let [command-type (subs content 0 (if-let [end (str/index-of content " ")]
                                       end
                                       (count content)))]
    (condp = command-type
      "repl>" (let [[return-result print-result]
                    (repl-command (subs content (inc (str/index-of content " "))))]
                (m/send-message bot channel-id (str "```\r\n"
                                                    print-result
                                                    "\r\n```"))
                (m/send-message bot channel-id (str "```\r\n; => "
                                                    (with-out-str (prn return-result))
                                                    "\r\n```")))
      "!disconnect" (disconnect bot)
      nil)))

(defn proc-stop
  [bot event]
  nil)

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}
                {:event-channel (a/chan 1)
                 :event-type :stop
                 :event-handler (fn [& args]
                                  (apply @#'proc-stop args))}])

(defonce basic-bot (atom (bots/create-bot {:token token
                                           :listeners listeners})))

(comment
  (def basic-bot (atom (bots/create-bot {:token token
                                         :listeners listeners}))))

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
                                   client))))
