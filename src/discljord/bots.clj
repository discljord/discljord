(ns discljord.bots
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [discljord.connections :as conn]))

(s/def ::state any?)

(s/def ::name string?)
(s/def ::id number?)

(s/def ::channel (s/keys :req-un [::name ::id ::state]))
(s/def ::channels (s/coll-of ::channel))

(s/def ::event-channel any?)

(s/def ::event-type keyword?)
(s/def ::listener (s/keys :req-un [::event-channel ::event-type]))
(s/def ::listeners (s/coll-of ::listener))

(s/def ::token string?)
(s/def ::bot (s/keys :req-un [::token ::conn/shards ::event-channel]
                     :opt-un [::state ::channels]))

(s/def ::event-type keyword?)
(s/def ::event-data any?)
(s/def ::event (s/keys :req-un [::event-type ::event-data]))

(defn start-message-proc
  "Starts the messaging procedure for a set of event listeners associated with a specific message channel."
  [event-channel event-listeners]
  (a/go-loop []
    (let [event (a/<! event-channel)]
      (doseq [{channel :event-channel} (filter #(= (:event-type event) (:event-type %)) event-listeners)]
        (a/>! channel event)))
    (recur)))
(s/fdef start-message-proc
        :args (s/cat :channel any? :listeners ::listeners))

(defn create-bot
  [{:keys [token] :as params}]
  (let [gateway (conn/get-websocket-gateway! (conn/api-url "/gateway/bot") token)
        event-channel (a/chan 1000)
        shards (vec (doall (for [id (range 0 (if-let [result (:shard-count gateway)]
                                               result
                                               0))]
                             (let [shard (conn/create-shard gateway id)]
                               (conn/connect-websocket gateway token id event-channel
                                                       (:socket-state shard))))))
        ]
    {:token token :shards shards :state (atom {}) :channels [] :event-channel event-channel}))
(s/fdef create-bot
        :args (s/cat :params (s/keys* :token ::token))
        :ret ::bot)
