(ns discljord.http
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(def rest-version
  "The Discord API version that discljord uses in HTTP requests."
  9)

(def gateway-version
  "The Discord API version that discljord uses for the gateway connection."
  9)

(defn api-url
  "Takes an endpoint from Discord's API (with a starting '/'), and returns
  a URL for that Discord endpoint.

  For example: (api-url (str \"/channels/\" channel-id))"
  [endpoint]
  (str "https://discord.com/api/v" rest-version endpoint))
(s/fdef api-url
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)

(def gateway-url
  "URL used to retrieve the gateway for the bot."
  (str "https://discord.com/api/gateway/bot?v=" gateway-version "&encoding=json"))
