(ns discljord.core
  (:require [discljord.bots :as b]
            [discljord.connections :as c]
            [discljord.messaging :as m]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

(defn send-message
  [channel message]
  nil)
(s/fdef send-message
        :args (s/cat :channel number? :message string?))

(defn mention
  [user]
  nil)
(s/fdef mention
        :args (s/cat :user string?)
        :ret string?)

(defn create-bot
  [{:keys [token] :as params}]
  (b/create-bot params))
(s/fdef create-bot
        :args (s/cat :params (s/keys* :req-un [::b/token]))
        :ret ::b/bot)

(defn connect-bot!
  [bot]
  (let [new-bot nil]
    new-bot))
(s/fdef connect-bot
        :args (s/cat :bot ::b/bot)
        :ret ::b/bot)

(defn disconnect-bot!
  [bot]
  bot)
(s/fdef disconnect-bot
        :args (s/cat :bot ::b/bot)
        :ret ::b/bot)

(defmacro defbot
  [bot & params]
  (let [params (if (map? (first params))
                 (first params)
                 (into {} (map vec (partition 2 params))))
        #_{:keys [token prefix commands listeners]
         :or {token ""
              prefix "!"
              commands {}
              listeners []}} #_params]
    nil))
