(ns discljord.connections-test
  (:require [discljord.connections :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async :as a]
            [org.httpkit.fake :as fake]
            [org.httpkit.server :as s :refer [with-channel
                                              on-receive
                                              run-server
                                              send!
                                              close]]
            [clojure.spec.alpha :as spec]
            [gniazdo.core :as ws]
            [clojure.test :as t]))

(t/deftest gateway
  (t/testing "Is a gateway properly created?"
    (t/is (= "https://discordapp.com/api/gateway?v=6&encoding=json"
             (api-url "/gateway"))))
  (t/testing "Is a request made with the proper headers?\n"
    (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                          (fn [orig-fn opts callback]
                            (if (= (:headers opts) {"Authorization" "TEST_TOKEN"})
                              {:status 200 :body (json/write-str
                                                  {"url" "wss://fake.gateway.api/" "shards" 1})}
                              {:status 401
                               :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
      (t/is (= "wss://fake.gateway.api/"
               (:url (get-websocket-gateway! (api-url "/gateway/bot") "TEST_TOKEN"))))
      (t/testing "Are invalid endpoints caught properly?"
        (t/is (= nil
                 (get-websocket-gateway! (api-url "/invalid") "TEST_TOKEN"))))
      (t/testing "Are tokens properly taken into account?"
        (t/is (= nil
                 (get-websocket-gateway! (api-url "/gateway/bot") "UNAUTHORIZED"))))
      (t/testing "Are shards properly returned?"
        (t/is (= {:url "wss://fake.gateway.api/" :shard-count 1}
                 (get-websocket-gateway! (api-url "/gateway/bot") "TEST_TOKEN")))))))

(t/deftest clean-json
  (t/testing "Are event keywords created properly?"
    (t/is (= (json-keyword "READY")
             :ready))
    (t/is (= (json-keyword "GUILD_CREATE")
             :guild-create)))
  (t/testing "Are json objects cleaned correctly?"
    (t/is (= (clean-json-input "id")
             :id))
    (t/is (= (clean-json-input {"id" 12345})
             {:id 12345}))
    (t/is (= (clean-json-input {"id" "string"})
             {:id "string"}))
    (t/is (= (clean-json-input [{"id" "string"}])
             [{:id "string"}]))))

(t/deftest shards
  (t/testing "Are shards properly created?"
    (t/is (spec/valid? :discljord.connections/shard
                       (create-shard {:url "wss://fake.gateway.api/" :shard-count 1} 0)))
    (t/is (spec/valid? :discljord.connections/shards
                       (for [id (range 0 2)]
                         (create-shard {:url "wss://fake.gateway.api/" :shard-count 2} id))))))

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

(t/deftest websockets
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
                                       (do
                                         (binding [*out* *err*]
                                           (println "Sending ready packet"))
                                         (send! conn (json/write-str {"op" 0 "d" {"v" 6
                                                                                 "session_id" 1}
                                                                     "s" 5 "t" "READY"})))
                                       (if (= op 52)
                                         (send! conn (json/write-str {"op" 7}))
                                         (if (= op 53)
                                           (send! conn (json/write-str {"op" 9 "d" false}))
                                           (if (= op 54)
                                             (send! conn (json/write-str {"op" 9 "d" true}))
                                             (if (= op 55)
                                               (do (println "Closing connection!")
                                                   (close conn)))))))))))))]
      (t/is (= 0 @success))
      (let [socket-state (atom {:keep-alive true :ack? true})
            event-channel (a/chan)]
        (swap! socket-state assoc :socket
               (connect-websocket {:url uri :shard-count 1} t 0 event-channel socket-state))
        (Thread/sleep 100)
        (t/is (= 1 @success))
        (t/testing "\tDoes the websocket perform heartbeats?\n"
          (t/is (= (:hb-interval @socket-state) 1000))
          (Thread/sleep 1100)
          (t/is (>= 2 @heartbeats)))
        ;; TODO: figure out why I can't send-msg from here
        (t/testing "\tDoes the websocket send heartbeats back when prompted?\n"
          (let [beats @heartbeats]
            (ws/send-msg (:socket @socket-state) (json/write-str {"op" 50}))
            (Thread/sleep 10)
            (t/is (= (inc beats) @heartbeats))))
        (t/testing "\tDoes the websocket push events onto its channel?"
          (ws/send-msg (:socket @socket-state) (json/write-str {"op" 51}))
          (let [[result port] (a/alts!! [event-channel (a/timeout 1000)])]
            (t/is (= :ready
                     (:event-type result)))))
        (t/testing "\tDoes the websocket reconnect when sent an op 7 payload?"
          (t/is (= 0 @reconnects))
          (ws/send-msg (:socket @socket-state) (json/write-str {"op" 52}))
          (Thread/sleep 200)
          (t/is (= 1 @reconnects)))
        (t/testing "\tDoes the websocket properly respond to invalid session payloads?"
          (ws/send-msg (:socket @socket-state) (json/write-str {"op" 53}))
          (Thread/sleep 200)
          (t/is (= 2 @success))
          (ws/send-msg (:socket @socket-state) (json/write-str {"op" 54}))
          (Thread/sleep 200)
          (t/is (= 2 @reconnects)))
        #_(t/testing "\tDoes the websocket reconnect when sent and EOF?"
          (t/is (= 2 @reconnects))
          ;; TODO Figure out how to send EOF
          (ws/send-msg (:socket @socket-state) (json/write-str {"op" 55}))
          (Thread/sleep 200)
          (t/is (= 3 @reconnects)))
        #_(t/testing "Does the hearbeat stop when the connection is closed?"
          (t/is (not= nil (:socket @socket-state)))
          (let [beats @heartbeats]
            (swap! socket-state assoc :keep-alive false)
            (ws/close (:socket @socket-state))
            (Thread/sleep 1100)
            (t/is (= beats @heartbeats))))))))
