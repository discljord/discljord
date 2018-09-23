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
  "Takes a string and converts it to a keyword.
  The resulting keyword will consist entirely of lower-case letters,
  and replaces underscores with dashes.

  Results in an unreadable keyword if the string contains spaces."
  [s]
  (keyword (str/replace (str/lower-case s) #"_" "-")))
(s/fdef json-keyword
  :args (s/cat :string string?)
  :ret keyword?)

(defn clean-json-input
  "Takes in arbitrary JSON data, from clojure.data.json/read-str or similar,
  and conforms it to a more idiomatic Clojure form.

  Strings and numbers simply return themselves.
  Objects have their keys and values recursively conformed.
  Objects with string keys are converted to maps with keyword keys.
  Arrays are converted to vectors with each element recursively conformed."
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
