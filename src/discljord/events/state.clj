(ns discljord.events.state
  "Middleware to cache all the state that Discord sends."
  (:require
   [clojure.tools.logging :as log]
   [discljord.events :as e]
   [discljord.events.middleware :as mdw]
   [clojure.set :as sets]))

(defn- vector->map
  "Turns a vector into a map from an item of each value to the value.

  `kf` is the function used to generate a key from a value, default is `:id`
  `vf` is run on the value before it is put in the map, default is [[identity]]

  If multiple items return the same key, only the first one will be used."
  ([coll] (vector->map :id coll))
  ([kf coll] (vector->map kf identity coll))
  ([kf vf coll]
   (zipmap (map kf coll) (map vf coll))))

(defn prepare-thread [{:keys [member] :as thread}]
  (cond-> (update thread :members (partial vector->map :user-id))
    member  (assoc-in [:members (:user-id member)] member)))

(defn thread-member-update [_ {:keys [guild-id id user-id] :as member} state]
  (swap! state assoc-in [::guilds guild-id :threads id :members user-id] member))

(defn thread-members-update [_ {:keys [added-members removed-member-ids id guild-id  member-count :as event]} state]
  (swap! state update-in
         [::guilds guild-id :threads id]
         (fn [thread]
           (-> thread
               (update :members #(reduce dissoc % removed-member-ids))
               (update :members into (map (juxt :user-id #(select-keys % [:user-id :join-timestamp :id :flags])) added-members))
               (assoc :member-count member-count)))))

(defn thread-list-sync [_ {:keys [guild-id channel-ids threads members]} state]
  (let [channels-to-clear (sets/difference (set channel-ids) (set (map :parent-id threads)))]
    (swap! state update-in [::guilds guild-id threads]
           (fn [threads]
             (as-> threads $
                 (merge $ (vector->map :id prepare-thread threads))
                 (reduce dissoc $ channels-to-clear)
                 (reduce (fn [threads {:keys [user-id id] :as thread-member}]
                           (assoc-in threads [id :members user-id] thread-member))
                         $ members))))))

(defn thread-delete [_ {:keys [guild-id id]} state]
  (swap! state update-in [::guilds guild-id :threads] dissoc id))

(defn thread-update [_ {:keys [guild-id id] {:keys [archived]} :thread-metadata :as thread} state]
  (if archived
    (thread-delete nil thread state)
    (swap! state update-in [::guilds guild-id :threads id] merge (prepare-thread thread))))

(defn prepare-guild
  "Takes a guild and prepares it for storing in the cache.

  The roles vector will be transformed to a map keyed off if, members
  transformed to a map from user id to member object with `:user` key replaced
  by the user id, channels will be changed to a map from id to channel object."
  [guild]
  (assoc guild
         :roles (vector->map (:roles guild))
         :channels (vector->map (:channels guild))
         :threads (vector->map :id prepare-thread (:threads guild))
         :members (vector->map (comp :id :user) #(update % :user :id) (:members guild))
         :presences (vector->map (comp :id :user) #(update % :user :id) (:presences guild))))

(defn- get-users-from-guild
  "Takes a guild and returns a map from user id to user objects."
  [guild]
  (vector->map (map :user (:members guild))))

(defn ready
  "Stores the user and guilds into the state."
  [_ {:keys [user guilds]} state]
  (swap! state
         (fn [state]
           (assoc state
                  ::bot user
                  ::guilds (merge (::guilds state)
                                  (into {} (map vector (map :id guilds) (map prepare-guild guilds))))
                  ::users (apply merge-with merge (::users state) (map get-users-from-guild guilds))))))

(defn guild-update
  "Stores the guild into the state."
  [_ guild state]
  (swap! state
         (fn [state]
           (update (update-in state [::guilds (:id guild)] merge (prepare-guild guild))
                   ::users (partial merge-with merge) (get-users-from-guild guild)))))

(defn channel-update
  [_ channel state]
  (swap! state assoc-in
         (if (:guild-id channel)
           [::guilds (:guild-id channel) :channels (:id channel)]
           [::private-channels (:id channel)])
         channel))

(defn channel-delete
  [_ channel state]
  (swap! state update-in
         (if (:guild-id channel)
           [::guilds (:guild-id channel) :channels]
           [::private-channels])
         dissoc (:id channel)))

(defn channel-pins-update
  [_ {:keys [guild-id channel-id last-pin-timestamp]} state]
  (swap! state assoc-in
         (if guild-id
           [::guilds guild-id :channels channel-id :last-pin-timestamp]
           [::private-channels channel-id :last-pin-timestamp])
         last-pin-timestamp))

(defn guild-emojis-update
  [_ {:keys [guild-id emojis]} state]
  (swap! state assoc-in [::guilds guild-id :emojis] emojis))

(defn guild-member-update
  [_ {:keys [guild-id user] :as member} state]
  (swap! state
         (fn [state]
           (update-in
            (update-in state [::users (:id user)]
                       merge user)
            [::guilds guild-id :members (:id user)]
            merge (assoc (dissoc member :guild-id)
                         :user (:id user))))))

(defn guild-member-remove
  [_ {:keys [guild-id user]} state]
  (swap! state update-in [::guilds guild-id :members]
         dissoc (:id user)))

(defn guild-members-chunk
  [_ {:keys [guild-id members]} state]
  (swap! state
         (fn [state]
           (update-in
            (update-in state [::users] (partial merge-with merge) (vector->map (map :user members)))
            [::guilds guild-id :members]
            merge (vector->map (comp :id :user) #(assoc % :user (:id (:user %)))
                               members)))))

(defn guild-role-update
  [_ {:keys [guild-id role]} state]
  (swap! state update-in [::guilds guild-id :roles (:id role)]
         merge role))

(defn guild-role-delete
  [_ {:keys [guild-id role-id]} state]
  (swap! state update-in [::guilds guild-id :roles]
         dissoc role-id))

(defn message-create
  [_ {:keys [guild-id channel-id id]} state]
  (swap! state
         (fn [state]
           (if guild-id
             (let [guild (get-in state [::guilds guild-id])]
               (assoc-in state [::guilds guild-id (if ((:channels guild) channel-id) :channels :threads) channel-id :last-message-id] id))
             (assoc-in state [::private-channels channel-id :last-message-id] id)))))

(defn presence-update
  [_ {:keys [user guild-id activities status client-status] :as presence} state]
  (swap! state
         (fn [state]
           (update-in state [::users (:id user)]
                      merge (assoc user
                                   :activities activities
                                   :status status
                                   :client-status client-status)))))

(defn voice-state-update
  [_ {:keys [user-id] :as voice} state]
  (swap! state assoc-in [::users user-id :voice]
         (dissoc voice :member)))

(defn user-update
  [_ user state]
  (swap! state update ::bot merge user))

(def caching-handlers
  "Handler map for all state-caching events.

  The state saved is of the following form:
  ```clojure
  {:discljord.events.state/bot <current-user>
   :discljord.events.state/guilds {<guild-id> <guild-object>}
   :discljord.events.state/users {<user-id> <user-object>}
   :discljord.events.state/private-channels {<channel-id> <channel-object>}}
  ```

  Guild objects are modified in a few ways. Roles, members, presences, threads and
  channels are all stored as maps from id to object, and members' and presence's
  user keys are the id of the user which is stored under the state's
  `:discljord.events.state/users` key. Any information received from
  `:presence-update` events is also merged into the user object, and a voice
  state object is stored under `:voice`.

  Threads also include a map of user ids to
  [thread member objects](https://discord.com/developers/docs/resources/channel#thread-member-object) in their `:member` key.

  Private channels are channels which lack a guild, including direct messages
  and group messages."
  {:ready [#'ready]
   :guild-create [#'guild-update]
   :guild-update [#'guild-update]
   :guild-delete [#'guild-update]
   :channel-create [#'channel-update]
   :channel-update [#'channel-update]
   :channel-delete [#'channel-delete]
   :channel-pins-update [#'channel-pins-update]
   :guild-emojis-update [#'guild-emojis-update]
   :guild-member-add [#'guild-member-update]
   :guild-member-update [#'guild-member-update]
   :guild-member-remove [#'guild-member-remove]
   :guild-members-chunk [#'guild-members-chunk]
   :guild-role-create [#'guild-role-update]
   :guild-role-update [#'guild-role-update]
   :guild-role-delete [#'guild-role-delete]
   :thread-create [#'thread-update]
   :thread-update [#'thread-update]
   :thread-member-update [#'thread-member-update]
   :thread-members-update [#'thread-members-update]
   :thread-delete [#'thread-delete]
   :thread-list-sync [#'thread-list-sync]
   :message-create [#'message-create]
   :presence-update [#'presence-update]
   :voice-state-update [#'voice-state-update]
   :user-update [#'user-update]})

(defn caching-middleware
  "Creates a middleware that caches Discord event data in `state`.

  `state` must be an [[clojure.core/atom]] containing a map.
  `caching-handlers`, if provided, must be a map of event keyword -> sequence of caching handlers.
  Each caching handler is a function that takes the event type, data and the state atom and updates the atom where applicable.

  If this parameter is not provided, the default [[caching-handlers]] are used.
  See its docs for more information on the default cache layout and behavior."
  ([state]
   (caching-middleware state #'caching-handlers))
  ([state caching-handlers]
   (mdw/concat
    #(e/dispatch-handlers caching-handlers %1 %2 state))))

(defn caching-transducer
  "Creates a transducer which caches event data and passes on all events.

  Values on the transducer are expected to be tuples of event-type and
  event-data.
  `state` must be an [[clojure.core/atom]] containing a map.
  `caching-handlers`, if provided, must be a map of event keyword -> sequence of caching handlers.
  Each caching handler is a function that takes the event type, data and the state atom and updates the atom where applicable.

  If this parameter is not provided, the default [[caching-handlers]] are used.
  See its docs for more information on the default cache layout and behavior."
  ([state]
   (caching-transducer state #'caching-handlers))
  ([state caching-handlers]
   (map (fn [[event-type event-data :as event]]
          (e/dispatch-handlers caching-handlers event-type event-data state)
          event))))
