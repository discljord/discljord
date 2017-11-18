(ns discljord-functional.connections
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]))

(defn api-url
  [gateway]
  (str "https://discordapp.com/api" gateway "?v=6&encoding=json"))

(defn get-websocket-gateway!
  [gateway token]
  (if-let [result (try (into {} (mapv (fn [[k v]] [(keyword k) v])
                                      (vec (json/read-str (:body @(http/get gateway
                                                                    {:headers {"Authorization" token}}))))))
                       (catch Exception e
                         nil))]
    (when (:url result)
      result)))

(defn connect-websocket
  [gateway shard-id socket-state]
  (ws/connect (:url gateway)
    :on-connect (fn [_] ;; TODO: Start the heartbeat
                  )))
