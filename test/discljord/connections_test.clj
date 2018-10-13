(ns discljord.connections-test
  (:require [discljord.connections :refer :all :as c]
            [discljord.http :refer :all]
            [discljord.util :refer :all]
            [discljord.specs :as ds]
            [discljord.connections.specs :as cs]
            [clojure.data.json :as json]
            [clojure.core.async :as a]
            [org.httpkit.fake :as fake]
            [org.httpkit.server :as s :refer [with-channel
                                              run-server
                                              send!
                                              close]]
            [gniazdo.core :as ws]
            [clojure.tools.logging :as log]
            [clojure.test :as t]))

(t/deftest urls
  (t/testing "Api URLs are created properly"
    (t/is (= "example?v=6&encoding=json"
             (append-api-suffix "example"))
          "Version and encoding information are appended to endpoint")
    (t/is (= (str "https://discordapp.com/api"
                  (append-api-suffix "/endpoint"))
             (api-url "/endpoint"))
          "URLs are to the correct Discord website")))

(t/deftest gateways
  (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                        (fn [orig-fn opts callback]
                          (if (= (:headers opts) {"Authorization" "TEST_TOKEN"})
                            {:status 200 :body (json/write-str
                                                {"url" "wss://fake.gateway.api/" "shards" 1})}
                            {:status 401
                             :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
    (t/testing "gateways require authorization"
      (t/is (= {::ds/url "wss://fake.gateway.api/"
                ::cs/shard-count 1}
               (get-websocket-gateway! (api-url "/gateway/bot") "TEST_TOKEN"))
            "Correct authorization and URL is valid")
      (t/is (not (get-websocket-gateway! (api-url "/gateway/bot") "INVALID_TOKEN"))
            "Invalid tokens will return nil")
      (t/is (not (get-websocket-gateway! (api-url "/invalid/endpoint") "TEST_TOKEN"))
            "Invalid endpoints will return nil"))))

(t/deftest json-conversion
  (t/testing "keywords are produced from strings"
    (t/is (= :ready
             (json-keyword "ready"))
          "Strings are converted to keywords")
    (t/is (= :test-key
             (json-keyword "test_key"))
          "Underscores are replaced with dashes")
    (t/is (= :test
             (json-keyword "TeSt"))
          "Strings are downcased before the conversion"))
  (t/testing "JSON data is converted to edn"
    (t/is (= "id"
             (clean-json-input "id"))
          "Strings return themselves")
    (t/is (= {12345 12345}
             (clean-json-input {12345 12345}))
          "Objects return equivalent maps")
    (t/is (= {1 {2 3}}
             (clean-json-input {1 {2 3}}))
          "Nested objects")
    (t/is (= {:id 12345}
             (clean-json-input {"id" 12345}))
          "String keys are converted to keywords")
    (t/is (= {:player {:id 12345}}
             (clean-json-input {"player" {"id" 12345}}))
          "String keys are converted to keywords recursively")
    (t/is (= [{:id 12345}]
             (clean-json-input [{"id" 12345}]))
          "Arrays are converted to vectors and recursively converted")))

(declare ^{:private :private :dynamic :dynamic} *recv*)

(defn- ws-srv
  [request]
  (with-channel request channel
    (send! channel (json/write-str {"op" 10 "d" {"heartbeat_interval" 100}}))
    (s/on-receive channel (partial *recv* channel))))

(def ^:private uri "ws://localhost:9009")

(t/deftest websockets
  (let [success (atom 0)
        heartbeats (atom 0)
        t "VALID_TOKEN"]
    (with-redefs [*recv*
                  (fn [connection message]
                    (let [message (json/read-str message)
                          op (get message "op")
                          d (get message "d")]
                      (case op
                        1 (do (swap! heartbeats inc)
                              (send! connection (json/write-str {"op" 11})))
                        2 (let [token (get d "token")
                                [shard-id shard-count] (get d "shard")]
                            (swap! success inc)
                            (send! connection (json/write-str
                                               {"op" 0
                                                "s" 0
                                                "t" "READY"
                                                "d" {"session_id" "session"}})))
                        nil)))]
      (let [[conn shard-state] (connect-shard! uri t 0 1 (a/chan 10))]
        (Thread/sleep 100)
        (swap! shard-state assoc :disconnect true)
        (t/is (< 0 @success) "Connection can be established")
        (t/is (< 0 @heartbeats) "Heartbeats are sent")))))

(defn server-fixture
  [f]
  (let [server (run-server ws-srv {:port 9009})]
    (f)
    (server)))

(defn test-ns-hook
  []
  (urls)
  (gateways)
  (json-conversion)
  (server-fixture websockets))
