(ns examples.repl-bot
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [discljord.messaging :as m]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.main :refer [repl]])
  (:import [org.eclipse.jetty websocket.client.WebSocketClient util.ssl.SslContextFactory])
  (:use com.rpl.specter))

;; Create a map of channelss to queues. Start different threads for each queue.
;; Each thread will take from its queue and run the code from it in a "sandbox"

(def token (str/trim (slurp "resources/token.txt")))
(def bot-owner-id (str/trim (slurp "resources/owner.txt")))

(defn disconnect
  [bot]
  (transform [:shards ALL :socket-state] conn/disconnect-websocket bot))

(defn start-repl
  "Starts a repl that will send output to the given discord channel which gives
  no prompts, and takes no input directly from said channel. Instead it will
  take all input as strings from the core.async channel passed as read-chan.
  When the keyword :stop is put on read-chan, the repl will exit."
  [bot channel-id read-chan]
  (let [messages (atom [])]
    (a/go
      (repl :need-prompt (constantly true)
            :prompt #()
            :flush #(do
                      (doseq [message @messages]
                        (m/send-message bot channel-id message))
                      (reset! messages []))
            :read (fn [prompt exit]
                    (let [msg (a/<!! read-chan)]
                      (condp = msg
                        :stop exit
                        (read-string msg))))
            :eval #(let [return-result (atom nil)
                         print-result (with-out-str (reset! return-result (eval %)))
                         print-result (when-not (= print-result "")
                                        (str "```\r\n" print-result "\r\n```"))]
                     (when print-result
                       (swap! messages conj print-result))
                     @return-result)
            :print (fn [& more]
                     (let [more (map #(str "```\r\n" (if % % "nil") "\r\n```") more)]
                       (apply swap! messages conj more)))
            :caught (fn [throwable]
                      (let [exception-text (with-out-str
                                             (binding [*err* *out*]
                                               (.printStackTrace throwable)))
                            exception-text (if (> (count exception-text) 1500)
                                             (subs exception-text 0 1500)
                                             exception-text)]
                        (swap! messages
                               conj (str "Exception thrown:\r\n```\r\n"
                                         exception-text "\r\n```"))))))))

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

(def real-repl (atom false))
(def channel-readers (atom {}))

(defn proc-command
  [bot {event-type :event-type
        {:keys [channel-id content type mentions]
         {user-id :id :keys [username bot?] :as author} :author :as data} :event-data}]
  (let [command-type (subs content 0 (if-let [end (str/index-of content " ")]
                                       end
                                       (count content)))]
    (condp = command-type
      "repl>" (if @real-repl
                (do
                  (when-not (get @channel-readers channel-id)
                    (swap! channel-readers assoc channel-id (a/chan 100)))
                  (a/>!! (get @channel-readers channel-id) (subs content (inc (str/index-of content " ")))))
                (let [[return-result print-result]
                      (repl-command (subs content (inc (str/index-of content " "))))]
                  (when-not (= print-result "")
                    (m/send-message bot channel-id (str "```\r\n"
                                                        print-result
                                                        "\r\n```")))
                  (Thread/sleep 50)
                  (m/send-message bot channel-id (str "```\r\n; => "
                                                      (with-out-str (prn return-result))
                                                      "\r\n```"))))
      "!disconnect" (disconnect bot)
      nil)))

(defn proc-disconnect
  [bot event]
  nil)

(def listeners [{:event-channel (a/chan 100)
                 :event-type :message-create
                 :event-handler (fn [& args]
                                  (apply @#'proc-command args))}
                {:event-channel (a/chan 1)
                 :event-type :disconnect
                 :event-handler (fn [& args]
                                  (apply @#'proc-disconnect args))}])

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
