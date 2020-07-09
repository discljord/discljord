(ns discljord.formatting)

(defn- extract-id [entity]
  (cond-> entity (map? entity) :id))

(defn mention-user [user]
  (str "<@" (extract-id user) \>))

(defn mention-role [role]
  (str "<@&" (extract-id role) \>))

(defn mention-channel [channel]
  (str "<#" (extract-id channel) \>))

(defn mention-emoji [emoji]
  (if (map? emoji)
    (let [{:keys [animated name id]} emoji]
      (if id
        (str \< (if animated \a "") \: name \: id \>)
        name))
    (str "<:_:" emoji \>)))

(defn user-tag [{:keys [username discriminator] :as user}]
  (str username \# discriminator))