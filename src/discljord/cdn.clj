(ns discljord.cdn
  "Namespace with functions to create [cdn urls](https://discord.com/developers/docs/reference#image-formatting) to image data from API entities such as users or guilds."
  (:require
    [clojure.string :refer [starts-with?]]
    [discljord.util :refer [parse-if-str]]))

(def base-url "https://cdn.discordapp.com")

(defn custom-emoji
  "Takes a custom emoji object or id and returns a url to the image data.

  Note that an id alone is not enough to determine whether the emoji is animated or not,
  so if you pass an id you will always get a url to a png."
  [emoji]
  (str base-url "/emojis/"
       (cond-> emoji (map? emoji) :id) \. (if (:animated emoji) "gif" "png")))

(defn animated?
  "Takes an image data hash and returns whether it represents an animated image (gif) or not."
  [hash]
  (starts-with? hash "a_"))

(defn file-name
  "Takes an image data hash and returns the file name it represents with the correct extension."
  [hash]
  (str hash \. (if (animated? hash) "gif" "png")))

(defn image-url-generator
  "Returns a function that, given an object with an id and an image data hash, returns a url to that image."
  [path hash-key]
  (fn [{:keys [id] hash hash-key}]
    (when hash
      (str base-url \/ path \/ id \/ (file-name hash)))))

(def guild-icon
  "Takes a guild and returns a url to the guild's icon or `nil` if the guild does not have an icon."
  (image-url-generator "icons" :icon))

(def guild-splash
  "Takes a guild and returns a url to the guild's splash or `nil` if the guild does not have a splash."
  (image-url-generator "splashes" :splash))

(def guild-discovery-splash
  "Takes a guild and returns a url to the guild's discovery splash or `nil` if the guild does not have a discovery splash."
  (image-url-generator "discovery-splashes" :discovery-splash))

(def guild-banner
  "Takes a guild and returns a url to the guild's banner or `nil` if the guild does not have a banner."
  (image-url-generator "banners" :banner))

(defn default-user-avatar
  "Takes a user object or a discriminator and returns a url to the default avatar image of that user/discriminator."
  [user-or-discrim]
  (str base-url "/embed/avatars/" (mod (cond-> user-or-discrim (map? user-or-discrim) :discriminator true parse-if-str) 5) ".png"))

(def user-avatar
  "Takes a user object and returns a url to the avatar of that user or `nil` if the user does not have an avatar."
  (image-url-generator "avatars" :avatar))

(defn effective-user-avatar
  "Takes a user object and returns a url to the effective avatar of that user.

  I.e., if the user has a custom avatar, it returns that, otherwise it returns the default avatar for that user."
  [user]
  (or (user-avatar user) (default-user-avatar user)))

(def application-icon
  "Takes an OAuth2 application info object and returns a url to its icon.

  The current application info is obtainable via [[discljord.messaging/get-current-application-information!]]."
  (image-url-generator "app-icons" :icon))

(defn resize
  "Adds a size query parameter to the given image url, resulting in a resize of the image.

  Any power of 2 between 16 and 4096 is a valid size."
  [url size]
  (str url "?size=" size))
