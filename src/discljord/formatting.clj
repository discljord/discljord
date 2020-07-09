(ns discljord.formatting
  "Contains utility functions to help with Discord message formatting.")

(defn- extract-id [entity]
  (cond-> entity (map? entity) :id))

(defn mention-user
  "Takes a user object or id and returns a mention of that user for use in messages."
  [user]
  (str "<@" (extract-id user) \>))

(defn mention-role
  "Takes a role object or id and returns a mention of that role for use in messages."
  [role]
  (str "<@&" (extract-id role) \>))

(defn mention-channel
  "Takes a text channel object or id and returns a mention of that channel for use in messages."
  [channel]
  (str "<#" (extract-id channel) \>))

(defn mention-emoji
  "Takes an emoji object or a custom emoji id and returns a mention of that emoji for use in messages.
  A provided emoji object may also represent a regular unicode emoji with just a name,
  in which case that name will be returned."
  [emoji]
  (if (map? emoji)
    (let [{:keys [animated name id]} emoji]
      (if id
        (str \< (if animated \a "") \: name \: id \>)
        name))
    (str "<:_:" emoji \>)))

(defn user-tag
  "Takes a user object and returns a string representing it as a tag, i.e. \"username#discriminator\"."
  [{:keys [username discriminator] :as user}]
  (str username \# discriminator))