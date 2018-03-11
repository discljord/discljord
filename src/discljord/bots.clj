(ns discljord.bots
  (:use [com.rpl.specter])
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [discljord.connections :as conn]
            [clojure.string :as str]))

(s/def ::state any?)

(s/def ::name string?)
(s/def ::id number?)

(s/def ::guild (s/keys :req-un [::id ::state]
                       :opt-un [::name]))
(s/def ::guilds (s/coll-of ::guild))

(s/def ::event-channel any?)

(s/def ::event-type keyword?)
(s/def ::event-data any?)
(s/def ::event-handler fn?)
(s/def ::event (s/keys :req-un [::event-type ::event-data]))
(s/def ::listener (s/keys :req-un [::event-channel ::event-type ::event-handler]))
(s/def ::listeners (s/coll-of ::listener))

(s/def ::token string?)
(s/def ::bot (s/keys :req-un [::token ::conn/shards ::event-channel ::listeners]
                     :opt-un [::state]))

(defn shard-id-from-guild
  "Gets the shard id that a given guild will interact with."
  [gateway guild]
  (mod (bit-shift-right (:id guild) 22) (:shard-count gateway)))
(s/fdef shard-id-from-guild
        :args (s/cat :gateway ::conn/gateway :guild ::guild)
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
        (do (println "Closing message proc")
            (doseq [{channel :event-channel} event-listeners]
              (a/>! channel event))))))
  nil)
(s/fdef start-message-proc!
        :args (s/cat :channel any? :listeners ::listeners)
        :ret nil?)

(defn start-listeners!
  "Takes a collection of listeners and starts each one such that they take values from their channels and perform their handler functions on the events."
  [{:keys [listeners] :as bot}]
  (doseq [{:keys [event-channel event-type event-handler] :as listener} listeners]
    (a/go-loop []
      (let [event (a/<! event-channel)]
        (if-not (= (:event-type event) :disconnect)
          (do (event-handler bot event)
              (recur))
          (println "Closed listener:" listener)))))
  nil)
(s/fdef start-listeners!
        :args (s/cat :listeners ::listeners)
        :ret nil?)

(defn init-shards
  [bot]
  (assoc bot :shards
         (let [gateway (conn/get-websocket-gateway! (conn/api-url "/gateway/bot") (:token bot))]
           (vec (doall (for [id (range 0 (if-let [result (:shard-count gateway)]
                                           result
                                           0))]
                         (conn/create-shard gateway id)))))))
(s/fdef init-shards
        :args (s/cat :bot ::bot)
        :ret ::bot)

(defn create-bot
  [{:keys [token default-listeners? listeners guilds init-state] :as params
    :or {token "" default-listeners? true init-state {}}}]
  (let [token (str "Bot " (str/trim token))
        event-channel (a/chan 1000)
        gateway nil
        shards []
        default-listeners (if default-listeners?
                            []
                            [])
        default-guilds guilds]
    {:token token :shards shards :state (atom (into init-state {::internal-state {:guilds default-guilds}}))
     :event-channel event-channel :listeners (into default-listeners listeners)}))
(s/fdef create-bot
        :args (s/cat :params (s/keys* :token ::token :default-listeners? boolean? :listeners ::listeners :guilds ::guilds))
        :ret ::bot)

;; ================================
;; State

(defn- get-key
  [state key]
  (key state))
(s/fdef get-key
        :args (s/cat :state ::state :key keyword?)
        :ret any?)

(defn- set-key
  [state key val]
  (assoc state key val))
(s/fdef set-key
        :args (s/cat :state ::state :key keyword? :val any?)
        :ret map?)

(defn state
  "Returns the global state map of a bot"
  [bot]
  (dissoc (deref (:state bot)) ::internal-state))
(s/fdef state
        :args (s/cat :bot ::bot)
        :ret map?)

(defn state+
  "Adds a key to the state in a bot, and returns the state map"
  [bot key val]
  (dissoc (swap! (:state bot) assoc key val) ::internal-state))
(s/fdef state+
        :args (s/cat :bot ::bot :key keyword? :val any?)
        :ret map?)

(defn state-
  "Removes a key from the state in a bot, and returns the state map"
  [bot key]
  (dissoc (swap! (:state bot) dissoc key) ::internal-state))
(s/fdef state-
        :args (s/cat :bot ::bot :key keyword?)
        :ret map?)

(defn update-state
  [bot key f]
  (dissoc (swap! (:state bot) update key f) ::internal-state))
(s/fdef update-state
        :args (s/cat :bot ::bot :key keyword? :f fn?)
        :ret map?)

(defn- same-id?
  [guild guild-id]
  (= (:id guild) guild-id))
(s/fdef same-id?
        :args (s/cat :guild ::guild :guild-id ::id)
        :ret boolean?)

;; All the guild state things need an update since they assume that a guild already exists for them to work

(defn guild-state
  [bot guild-id]
  (select-first [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state] bot))
(s/fdef guild-state
        :args (s/cat :bot ::bot :guild-id ::id)
        :ret map?)

(defn guild-state+
  [bot guild-id key val]
  (select-one [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state key]
                (setval [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state key] val bot)))
(s/fdef guild-state+
        :args (s/cat :bot ::bot :guild-id ::id :key keyword? :val any?)
        :ret map?)

(defn guild-state-
  [bot guild-id key]
  (select-first [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state]
                (transform [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state] #(dissoc % key) bot)))
(s/fdef guild-state-
        :args (s/cat :bot ::bot :guild-id ::id :key keyword?))

(defn update-guild-state
  [bot guild-id key f & args]
  (select-first [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state key]
                (transform [:state ATOM ::internal-state :guilds ALL #(same-id? % guild-id) :state key] #(apply f % args) bot)))
