(ns discljord.connections-test
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.test :as t]
   [discljord.http :as ht]
   [discljord.connections :as c]
   [discljord.connections.specs :as cs]
   [discljord.specs :as ds]
   [discljord.util :refer [clean-json-input json-keyword]]
   [gniazdo.core :as ws]
   [org.httpkit.fake :as fake]
   [org.httpkit.server :as s :refer [with-channel
                                     run-server
                                     send!
                                     close]]
   [clojure.tools.logging :as log]))

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
      (fake/with-fake-http [(str "https://discord.com/api/gateway/bot?v=" ht/gateway-version "&encoding=json")
                            (fn [orig-fn opts callback]
                              (if (= (get (:headers opts) "Authorization")
                                     "Bot VALID_TOKEN")
                                {:status 200 :body (json/write-str
                                                    {"url" "ws://localhost:9009" "shards" 1
                                                     "session_start_limit" {"total" 1000
                                                                            "remaining" 1000
                                                                            "reset_after" 1000}})}
                                {:status 401
                                 :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
        (let [comm-chan (c/connect-bot! t (a/chan 10) :intents #{})]
          (Thread/sleep 1000)
          (a/put! comm-chan [:disconnect])
          (t/is (< 0 @success) "Connection can be established")
          (t/is (< 0 @heartbeats) "Heartbeats are sent"))))))

(defn server-fixture
  [f]
  (let [server (run-server ws-srv {:port 9009})]
    (f)
    (server)))

(defn test-ns-hook
  []
  (json-conversion)
  (server-fixture websockets))
