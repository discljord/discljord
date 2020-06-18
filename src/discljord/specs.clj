(ns discljord.specs
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]))

;; -------------------------------------------------
;; generic specs

(s/def ::url string?)
(s/def ::token string?)

(s/def ::future any?)
(s/def ::promise any?)
(s/def ::channel (partial satisfies? clojure.core.async.impl.protocols/Channel))

(defn atom-of?
  "Takes a spec, and returns a spec for a clojure.lang.Atom
  containing a value of that spec."
  [s]
  (fn [x]
    (and (instance? clojure.lang.Atom x)
         (s/valid? s @x))))

(defn agent-of?
  "Takes a spec, and returns a spec for a clojure.lang.Agent
  containing a value of that spec."
  [s]
  (fn [x]
    (and (instance? clojure.lang.Agent x)
         (s/valid? s @x))))

(s/def ::snowflake (partial re-matches #"\d+"))

(s/def ::id ::snowflake)
(s/def ::channel-id ::snowflake)
(s/def ::guild-id ::snowflake)
(s/def ::user-id ::snowflake)
