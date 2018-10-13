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
            [midje.sweet :as m]
            [clojure.tools.logging :as log]))

(m/facts "about discord urls"
         (m/fact "giving append-api-suffix a url appends discord's api suffix"
                 (append-api-suffix ..url..)
                 => #"\?v=6&encoding=json$")
         (m/fact "giving an endpoint creates the appropriate string"
                 (api-url ..endpoint..)
                 => (str "https://discordapp.com/api"
                         (append-api-suffix ..endpoint..))))

(fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                      (fn [orig-fn opts callback]
                        (if (= (:headers opts) {"Authorization" "TEST_TOKEN"})
                          {:status 200 :body (json/write-str
                                              {"url" "wss://fake.gateway.api/" "shards" 1})}
                          {:status 401
                           :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
  (m/facts "about gateways"
    (m/fact "the websocket gateway requires an authorization header"
      (get-websocket-gateway! (api-url "/gateway/bot") "TEST_TOKEN")
      => {::ds/url "wss://fake.gateway.api/"
          ::cs/shard-count 1})
    (m/fact "the websocket gateway does not respond to invalid tokens"
      (get-websocket-gateway! (api-url "/gateway/bot") "INVALID_TOKEN") => nil)
    (m/fact "the websocket gateway does not respond to invalid endpoints"
      (get-websocket-gateway! (api-url "/invalid/endpoint") "TEST_TOKEN") => nil)))

(m/facts "about json conversions to keywords"
         (m/fact "strings are converted to keywords"
                 (json-keyword "ready")
                 => :ready)
         (m/fact "strings with underscores are converted to hyphens"
                 (json-keyword "test_key")
                 => :test-key)
         (m/fact "strings are downcased"
                 (json-keyword "TeSt")
                 => :test))

(m/facts "about json data cleaning"
         (m/fact "strings are cleaned to strings"
                 (clean-json-input "id")
                 => "id")
         (m/fact "objects are cleaned to maps"
                 (clean-json-input {12345 12345})
                 => {12345 12345}
                 (clean-json-input {1 {2 3}})
                 => {1 {2 3}})
         (m/fact "string keys in objects are changed to keywords"
                 (clean-json-input {"id" 12345})
                 => {:id 12345}
                 (clean-json-input {"player" {"id" 12345}})
                 => {:player {:id 12345}})
         (m/fact "vectors are cleaned recursively"
                 (clean-json-input [{"id" 12345}])
                 => [{:id 12345}]))

(declare ^:dynamic *recv*)

(defn- ws-srv
  [request]
  (with-channel request channel
    (send! channel (json/write-str {"op" 10 "d" {"heartbeat_interval" 100}}))
    (s/on-receive channel (partial *recv* channel))))

(def ^:private uri "ws://localhost:9009")

(m/facts "about websocket connections"
  (let [server (atom nil)
        success (atom 0)
        heartbeats (atom 0)
        t "VALID_TOKEN"]
    (m/with-state-changes [(m/before :facts
                                     (do
                                       (reset! server
                                               (run-server ws-srv
                                                           {:port 9009}))
                                       (reset! success 0)))
                           (m/after :facts
                                    (do (@server)
                                        (reset! server nil)))]
      (with-redefs [*recv* (fn [connection message]
                             (let [message (json/read-str message)
                                   op (get message "op")
                                   d (get message "d")]
                               (case op
                                 1 (do (swap! heartbeats inc)
                                       (send! connection (json/write-str {"op" 11})))
                                 2 (let [token (get d "token")
                                         [shard-id shard-count] (get d "shard")]
                                     (when (and (= token t)
                                                (= shard-id 0)
                                                (= shard-count 1))
                                       (swap! success inc)
                                       (send! connection (json/write-str
                                                          {"op" 0
                                                           "s" 0
                                                           "t" "READY"
                                                           "d" {"session_id" "session"}}))))
                                 nil)))]
        (m/fact "the bot connects with a websocket"
          (let [[conn shard-state] (connect-shard! uri t 0 1 (a/chan))
                result (do (Thread/sleep 50)
                           @success)]
            (swap! shard-state assoc :disconnect true)
            result)
          => 1)
        (m/fact "the bot sends heartbeats"
          (let [[conn shard-state] (connect-shard! uri t 0 1 (a/chan))
                result (do (Thread/sleep 50)
                           (> @heartbeats
                              1))]
            (swap! shard-state assoc :disconnect true)
            result)
          => true))
      ;; Add more tests for different websocket behaviors
      )))
