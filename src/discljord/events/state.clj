(ns discljord.events.state
  "Middleware to cache all the state that Discord sends."
  (:require
   [clojure.tools.logging :as log]
   [discljord.events :as e]
   [discljord.events.middleware :as mdw]))

(defn- vector->map
  "Turns a vector into a map from an item of each value to the value.

  `kf` is the function used to generate a key from a value, default is `:id`
  `vf` is run on the value before it is put in the map, default is [[identity]]

  If multiple items return the same key, only the first one will be used."
  ([coll] (vector->map :id coll))
  ([kf coll] (vector->map kf identity coll))
  ([kf vf coll]
   (into {} (map (fn [[k v]] [k (vf (first v))]))
         (group-by kf coll))))

(defn- prepare-guild
  "Takes a guild and prepares it for storing in the cache.

  The roles vector will be transformed to a map keyed off if, members
  transformed to a map from user id to member object with `:user` key replaced
  by the user id, channels will be changed to a map from id to channel object."
  [guild]
  (assoc guild
         :roles (vector->map (:roles guild))
         :channels (vector->map (:channels guild))
         :members (vector->map (comp :id :user) #(assoc % :user (:id (:user %))) (:members guild))))

(defn- get-users-from-guild
  "Takes a guild and returns a map from user id to user objects."
  [guild]
  (vector->map (map :user (:members guild))))

(defn ready
  "Stores the user and guilds into the state."
  [_ {:keys [user guilds]} state]
  (swap! state assoc
         :bot user
         :guilds (merge (:guilds state)
                        (into {} (map vector (map :id guilds) (map prepare-guild guilds))))
         :users (apply merge-with merge (:users state) (map get-users-from-guild guilds))))

(defn guild-update
  "Stores the guild into the state."
  [_ guild state]
  (swap! state
         (fn [state]
           (update (update-in state [:guilds (:id guild)] merge (prepare-guild guild))
                   :users (partial merge-with merge) (get-users-from-guild guild)))))

(defn channel-update
  [_ channel state]
  (swap! state assoc-in
         (if (:guild-id channel)
           [:guilds (:guild-id channel) :channels (:id channel)]
           [:private-channels (:id channel)])
         channel))

(defn channel-delete
  [_ channel state]
  (swap! state update-in
         (if (:guild-id channel)
           [:guilds (:guild-id channel) :channels]
           [:private-channels])
         dissoc (:id channel)))

(defn channel-pins-update
  [_ {:keys [guild-id channel-id last-pin-timestamp]} state]
  (swap! state assoc-in
         (if guild-id
           [:guilds guild-id :channels channel-id :last-pin-timestamp]
           [:private-channels channel-id :last-pin-timestamp])
         last-pin-timestamp))

(defn guild-emojis-update
  [_ {:keys [guild-id emojis]} state]
  (swap! state assoc-in [:guilds guild-id :emojis] emojis))

(defn guild-member-update
  [_ {:keys [guild-id user] :as member} state]
  (swap! state update-in [:guilds guild-id :members (:id user)]
         merge (assoc (dissoc member :guild-id)
                      :user (:id user))))

(defn guild-member-remove
  [_ {:keys [guild-id user]} state]
  (swap! state update-in [:guilds guild-id :members]
         dissoc (:id user)))

(defn guild-members-chunk
  [_ {:keys [guild-id members]} state]
  (swap! state update-in [:guilds guild-id :members]
         merge-with merge (vector->map (comp :id :user) #(assoc % :user (:id (:user %)))
                                       members)))

(defn guild-role-update
  [_ {:keys [guild-id role]} state]
  (swap! state update-in [:guilds guild-id :roles (:id role)]
         merge role))

(defn guild-role-delete
  [_ {:keys [guild-id role-id]} state]
  (swap! state update-in [:guilds guild-id :roles]
         dissoc role-id))

(defn message-create
  [_ {:keys [guild-id channel-id id]} state]
  (swap! state assoc-in
         (if guild-id
           [:guilds guild-id :channels channel-id :last-message-id]
           [:private-channels channel-id :last-message-id])
         id))

(defn presence-update
  [_ {:keys [user guild-id roles nick] :as presence} state]
  (swap! state
         (fn [state]
           (update-in
            (update-in state [:users (:id user)]
                       merge (dissoc presence :user :roles :nick :guild-id))
            [:guilds guild-id :members (:id user)]
            assoc
            :roles roles
            :nick nick))))

(defn voice-state-update
  [_ {:keys [user-id] :as voice} state]
  (swap! state assoc-in [:users user-id :voice]
         (dissoc voice :member)))

(def ^:private caching-handlers
  "Handler map for all state-caching events."
  {})

(defn caching-middleware
  "Creates a middleware that caches all Discord event data in `state`.

  `state` must be an [[clojure.core/atom]]."
  [state]
  (mdw/concat
   #(e/dispatch-handlers #'caching-handlers %1 %2 state)))

(defn caching-transducer
  "Creates a transducer which caches event data and passes on all events.

  Values on the transducer are expected to be tuples of event-type and
  event-data.
  `state` must be an [[clojure.core/atom]]."
  [state]
  (map (fn [[event-type event-data :as event]]
         (e/dispatch-handlers #'caching-handlers event-type event-data state)
         event)))
