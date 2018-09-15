(ns discljord.http
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(defn append-api-suffix
  [url]
  (str url "?v=6&encoding=json"))
(s/fdef append-api-suffix
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)

(defn api-url
  [gateway]
  (append-api-suffix (str "https://discordapp.com/api" gateway)))
(s/fdef api-url
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)
