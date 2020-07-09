(ns discljord.formatting
  "Contains utility functions to help with Discord message formatting."
  (:require [clojure.string :refer [split-lines join]]))

(def user-mention #"<@!?(?<id>\d+)>")

(def role-mention #"<@&(?<id>\d+)>")

(def channel-mention #"<#(?<id>\d+)>")

(def emoji-mention #"<(?<animated>a)?:(?<name>\w+):(?<id>\d+)>")

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

(defn embed-link
  "Creates an inline-style link with an optional title.
  I.e.: [text](url \"title\") or [text](url).
  Can only be used in embeds, not in regular messages."
  ([text url title] (str \[ text "](" url \space \" title \" \)))
  ([text url] (str \[ text "](" url \))))