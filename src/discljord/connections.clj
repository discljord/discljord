(ns discljord.connections
  (:use com.rpl.specter)
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::url string?)
(s/def ::token string?)

(s/def ::shard-count pos-int?)
(s/def ::gateway (s/keys :req [::url ::shard-count]))

(defn append-api-suffix
  [url]
  (str url "?v=6&encoding=json"))
(s/fdef append-api-suffix
  :args (s/cat :url ::url)
  :ret ::url)

(defn api-url
  [gateway]
  (append-api-suffix (str "https://discordapp.com/api" gateway)))
(s/fdef api-url
  :args (s/cat :url ::url)
  :ret ::url)

(defn get-websocket-gateway!
  [url token]
  (if-let [result
           (try
             (let [response (json/read-str
                             (:body
                              @(http/get
                                url
                                {:headers {"Authorization" token}})))]
               {::url (response "url")
                ::shard-count (response "shards")})
                (catch Exception e
                  nil))]
    (when (::url result)
      result)))
(s/fdef get-websocket-gateway!
  :args (s/cat :url ::url :token ::token)
  :ret ::gateway)

(defn json-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef json-keyword
  :args (s/cat :string string?)
  :ret keyword?)

(defn clean-json-input
  [j]
  (cond
    (map? j) (->> j
                  (transform [MAP-KEYS] #(if (string? %)
                                           (json-keyword %)
                                           (clean-json-input %)))
                  (transform [MAP-VALS coll?] clean-json-input))
    (vector? j) (mapv clean-json-input j)
    :else j))

(defn on-connect
  ([ws token]
   (on-connect ws token 0))
  ([ws token retries]
   (if @ws
     (ws/send-msg @ws (json/write-str {"op" 2
                                       "d" {"token" token
                                            "properties" {"$os" "linux"
                                                          "$browser" "discljord"
                                                          "$device" "discljord"}
                                            ;; Add default presence and shard
                                            }}))
     (if (< retries 100)
       (do (Thread/sleep 10)
           (recur ws token (inc retries)))
       (throw (Exception. "Unable to create connection with Discord Server."))))))

(defn on-receive
  [ws msg]
  )

(defn on-close
  [ws stop-code msg]
  )

(defn connect-websocket
  [url token]
  (let [ws (atom nil)]
    (println "connecting websocket!")
    (reset! ws (ws/connect url
                 :on-connect (fn [_]
                               (on-connect ws token))
                 :on-receive (fn [msg]
                               (on-receive ws msg))
                 :on-close (fn [stop-code msg]
                             (on-close ws stop-code msg))))
    @ws))

;; High level function which takes a token and returns a channel for events
;; This creates the "process" from the redesign document
(defn connect-bot
  [token]
  )
