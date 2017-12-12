(ns discljord.bots
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [discljord.connections :as conn]
            [clojure.string :as str]))

(s/def ::state any?)

(s/def ::name string?)
(s/def ::id number?)

(s/def ::channel (s/keys :req-un [::name ::id ::state]))
(s/def ::channels (s/coll-of ::channel))

(s/def ::event-channel any?)

(s/def ::event-type keyword?)
(s/def ::event-data any?)
(s/def ::event-handler fn?)
(s/def ::event (s/keys :req-un [::event-type ::event-data]))
(s/def ::listener (s/keys :req-un [::event-channel ::event-type ::event-handler]))
(s/def ::listeners (s/coll-of ::listener))

(s/def ::token string?)
(s/def ::bot (s/keys :req-un [::token ::conn/shards ::event-channel ::listeners]
                     :opt-un [::state ::channels]))

(defn shard-id-from-channel
  [gateway channel]
  (mod (bit-shift-right (:id channel) 22) (:shard-count gateway)))
(s/fdef shard-id-from-channel
        :args (s/cat :gateway ::conn/gateway :channel ::channel)
        :ret number?)

(defn start-message-proc!
  "Starts the messaging procedure for a set of event listeners associated with a specific message channel."
  [event-channel event-listeners]
  (a/go-loop []
    (let [event (a/<! event-channel)]
      (doseq [{channel :event-channel} (filterv #(= (:event-type event) (:event-type %)) event-listeners)]
        (a/>! channel event))
      (if-not (= (:event-type event) :disconnect)
        (recur)
        (doseq [{channel :event-channel} event-listeners]
          (a/>! channel event)))))
  nil)
(s/fdef start-message-proc!
        :args (s/cat :channel any? :listeners ::listeners)
        :ret nil?)

(defn start-listeners!
  [listeners]
  (doseq [{:keys [event-channel event-type event-handler] :as listener} listeners]
    (a/go-loop []
      (let [event (a/<! event-channel)]
        (if-not (= (:event-type event) :disconnect)
          (do (event-handler event)
              (recur))
          (a/close! event-channel)))))
  nil)
(s/fdef start-listeners!
        :args (s/cat :listeners ::listeners)
        :ret nil?)

(defn create-bot
  [{:keys [token default-listeners? listeners] :as params
    :or {token "" default-listeners? true}}]
  (let [token (str "Bot " (str/trim token))
        gateway (conn/get-websocket-gateway! (conn/api-url "/gateway/bot") token)
        event-channel (a/chan 1000)
        shards (vec (doall (for [id (range 0 (if-let [result (:shard-count gateway)]
                                               result
                                               0))]
                             (conn/create-shard gateway id))))
        default-listeners (if default-listeners?
                            []
                            [])]
    {:token token :shards shards :state (atom {}) :channels []
     :event-channel event-channel :listeners (into default-listeners listeners)}))
(s/fdef create-bot
        :args (s/cat :params (s/keys* :token ::token :listener-defaults boolean?))
        :ret ::bot)
