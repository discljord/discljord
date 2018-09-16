(ns discljord.util
  (:require [discljord.specs :as ds]
            [clojure.spec.alpha :as s]))

(defn bot-token
  "Takes a bot token, and returns the token value that can
  be used in the \"Authorization\" header for HTTP calls."
  [token]
  (str "Bot " token))
(s/fdef bot-token
  :args (s/cat :token ::ds/token)
  :ret ::ds/token)
