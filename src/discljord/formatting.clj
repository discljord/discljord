(ns discljord.formatting
  "Contains utility functions to help with Discord message formatting."
  (:require [clojure.string :refer [split-lines join]]))

(def user-mention
  "Regex pattern that matches user or member mentions.

  Captures the user id in its first capture group labelled \"id\"."
  #"<@!?(?<id>\d+)>")

(def role-mention
  "Regex pattern that matches role mentions.

  Captures the role id in its first capture group labelled \"id\"."
  #"<@&(?<id>\d+)>")

(def channel-mention
  "Regex pattern that matches text channel mentions.

  Captures the channel id in its first capture group labelled \"id\"."
  #"<#(?<id>\d+)>")

(def emoji-mention
  "Regex pattern that matches custom emoji mentions.

  (Optionally) captures the `a` prefix in its first capture group labelled \"animated\"
  to indicate if the emoji is animated; Captures the emoji name in its second capture group labelled
  \"name\" and the id in a last capture group labelled \"id\"."
  #"<(?<animated>a)?:(?<name>\w+):(?<id>\d+)>")

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

(defn code
  "Wraps the given text in an `inline code block`."
  [text]
  (str \` text \`))

(defn code-block
  "Puts the given text in a codeblock with corresponding syntax highlighting, if a language is given.

  I.e.:
  ```lang
  text
  ```"
  ([lang text] (str "```" lang \newline text "\n```"))
  ([text] (code-block "" text)))

(defn italics
  "Returns the given text as *italics*."
  [text]
  (str \* text \*))

(defn bold
  "Returns the given text in **bold**."
  [text]
  (str "**" text "**"))

(defn underline
  "Returns the given text __underlined__."
  [text]
  (str "__" text "__"))

(defn strike-through
  "Returns the given text with ~~strikethrough~~."
  [text]
  (str "~~" text "~~"))

(defn block-quote
  "Returns the given text in a blockquote, with new lines pre- and appended.

  I.e.:
  > text"
  [text]
  (str "\n> " (join "\n> " (split-lines text)) \newline))

(def full-block-quote
  "The full block quote (`>>>`).

  Everything that follows this separator is shown as a block quote in Discord messages."
  "\n>>> ")

(defn embed-link
  "Creates an inline-style link with an optional title.

  I.e.: [text](url \"title\") or [text](url).
  Can only be used in embeds, not in regular messages."
  ([text url title] (str \[ text "](" url \space \" title \" \)))
  ([text url] (str \[ text "](" url \))))

(def timestamp-styles
  "The available timestamp display styles."
  {:short-time \t
   :long-time \T
   :short-date \d
   :long-date \D
   :short-date-time \f
   :long-date-time \F
   :relative-time \R})

(defn timestamp
  "Creates a timestamp that will be displayed according to each user's locale.

  An optional style (one of [[timestamp-styles]]) can be set. The default is `:short-date-time`.
  The timestamp is a UNIX timestamp (seconds)."
  ([unix-timestamp]
   (str "<t:" unix-timestamp \>))
  ([unix-timestamp style]
   (str "<t:" unix-timestamp \: (timestamp-styles style) \>)))
