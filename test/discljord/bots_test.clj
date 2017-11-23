(ns discljord.bots-test
  (:require [discljord.bots :refer :all]
            [clojure.test :as t]
            [org.httpkit.fake :as fake]
            [clojure.core.async :as a]
            [org.httpkit.server :as s :refer [with-channel
                                              on-receive
                                              run-server
                                              send!]]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as spec]))

(t/deftest message-processing
  (t/testing "Are messages conveyed through the message process?"
    (let [ch (a/chan 10)
          val {:event-type :test :event-data {:s "Value!"}}
          listener {:event-type :test :event-channel (a/chan 10)}]
      (a/>!! ch val)
      (start-message-proc ch [listener])
      (let [result (a/alts!! [(:event-channel listener) (a/timeout 100)])]
        (t/is (and (not (nil? result))
                   (= val
                      (first result))))))))

(t/deftest bot-creation
  (t/testing "Are bots created properly?"
    (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                          (fn [orig-fn opts callback]
                            (if (= (:headers opts) {"Authorization" "Bot TEST_TOKEN"})
                              {:status 200 :body (json/write-str
                                                  {"url" "wss://fake.gateway.api/" "shards" 0})}
                              {:status 401
                               :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
      (t/is (spec/valid? :discljord.bots/bot (create-bot {:token "TEST_TOKEN"}))))))

(declare ^:dynamic *recv*)

(defn- ws-srv
  [req]
  (with-channel req conn
    (send! conn (json/write-str {"op" 10 "d" {"heartbeat_interval" 1000}}))
    (on-receive conn (partial *recv* req conn))))

(t/use-fixtures
  :each
  (fn [f]
    (let [srv (run-server ws-srv {:port 9009})]
      (try
        (f)
        (finally
          (srv))))))

(def ^:private uri "ws://localhost:9009/")

(t/deftest bot-socket-connection
  (let [t "VALID_TOKEN"
        success (atom 0)
        heartbeats (atom 0)
        reconnects (atom 0)]
    (with-redefs [*recv* (fn [_ conn msg]
                           (let [msg (json/read-str msg)
                                 op (get msg "op")
                                 d (get msg "d")
                                 token (get d "token")
                                 [shard-id shard-count] (get d "shard")]
                             (println "op code" op "data" d)
                             (if (and (= op 2) (= token t)
                                      (= shard-id 0) (= shard-count 1))
                               (swap! success inc)
                               (if (and (= op 1) (or (= d nil) (= d 5)))
                                 (do (swap! heartbeats inc)
                                     (send! conn (json/write-str {"op" 11})))
                                 (if (= op 6)
                                   (swap! reconnects inc)
                                   (if (= op 50)
                                     (send! conn (json/write-str {"op" 1 "d" 5}))
                                     (if (= op 51)
                                       (send! conn (json/write-str {"op" 0 "d" {"v" 6
                                                                                "session_id" 1}
                                                                    "s" 5 "t" "READY"}))
                                       (if (= op 52)
                                         (send! conn (json/write-str {"op" 7}))
                                         (if (= op 53)
                                           (send! conn (json/write-str {"op" 9 "d" false}))
                                           (if (= op 54)
                                             (send! conn (json/write-str {"op" 9 "d" true}))))))))))))]
      )))
