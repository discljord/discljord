(ns discljord-functional.connections-test
  (:require [discljord-functional.connections :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async :as a]
            [org.httpkit.fake :as fake]
            [org.httpkit.server :as s :refer [with-channel
                                              on-receive
                                              run-server
                                              send!]]
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
        (t/is (= {:url "wss://fake.gateway.api/" :shards 1}
                 (get-websocket-gateway! (api-url "/gateway/bot") "TEST_TOKEN")))))))

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
  (t/testing "Does the websocket handshake the server?\n"
    (let [t "VALID_TOKEN"
          success (atom 0)]
      (with-redefs [*recv* (fn [_ conn msg]
                             (let [msg (json/read-str msg)
                                   op (get msg "op")
                                   d (get msg "d")
                                   token (get d "token")
                                   [shard-id shard-count] (get d "shard")]
                               (if (and (= op 2) (= token t)
                                        (= shard-id 0) (= shard-count 1))
                                 (swap! success inc)
                                 (if (and (= op 1) (= d nil))
                                   (swap! success inc)))))]
        (t/is (= @success 0))
        (let [socket-state (atom {:keep-alive true})]
          (swap! socket-state assoc :socket (connect-websocket {:url uri :shards 1} t [0 1] socket-state))
          (Thread/sleep 100)
          ;; FIXME: Sometimes this test fails beacuse of timings in the heartbeats
          (t/is (= @success 1))
          (t/testing "\tDoes the websocket perform heartbeats?\n"
            (t/is (= (:hb-interval @socket-state) 1000))
            (Thread/sleep 1000)
            (t/is (= @success 2)))
          (t/testing "Does the hearbeat stop when the connection is closed?"))))))
