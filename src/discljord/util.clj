(ns discljord.util
  (:use com.rpl.specter)
  (:require [discljord.specs :as ds]
            [clojure.spec.alpha :as s]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn bot-token
  "Takes a bot token, and returns the token value that can
  be used in the \"Authorization\" header for HTTP calls."
  [token]
  (str "Bot " token))
(s/fdef bot-token
  :args (s/cat :token ::ds/token)
  :ret ::ds/token)

(defn json-keyword
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef json-keyword
  :args (s/cat :string string?)
  :ret keyword?)

(defn clean-json-input
  [j]
  (cond
    (map? j) (->> j
                  (transform [MAP-KEYS] #(if (string? %)
                                           (json-keyword %)
                                           (clean-json-input %)))
                  (transform [MAP-VALS coll?] clean-json-input))
    (vector? j) (mapv clean-json-input j)
    :else j))
(s/fdef clean-json-input
  :args (s/cat :json-data (s/or :string string?
                                :number number?
                                :array vector?
                                :object map?))
  :ret (s/or :string string?
             :number number?
             :array vector?
             :object map?
             :keyword keyword?))
