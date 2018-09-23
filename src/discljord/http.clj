(ns discljord.http
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(defn append-api-suffix
  "Takes a url and appends GET parameters for version of the Discord
  API, and the default encoding."
  [url]
  (str url "?v=6&encoding=json"))
(s/fdef append-api-suffix
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)

(defn api-url
  "Takes an endpoint from Discord's API (with a starting '/'), and returns
  a URL for that Discord endpoint.

  For example: (api-url (str \"/channels/\" channel-id))"
  [gateway]
  (append-api-suffix (str "https://discordapp.com/api" gateway)))
(s/fdef api-url
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)
