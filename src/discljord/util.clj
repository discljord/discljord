(ns discljord.util
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.impl.protocols :as a.proto]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [discljord.specs :as ds]
   [clojure.tools.logging :as log])
  (:import
   (java.io Writer)))

(def ^:dynamic ^:deprecated *enable-logging*
  "Dynamic var to allow you to disable logging entirely.
  Set in a binding form around the calls to start-connection! and
  connect-bot!"
  true)

(s/def ::logging-level #{:trace :debug :info :warn :error :fatal :report})

(defn ^:deprecated set-logging-level!
  "Sets the logging level for discljord through tambre.
  Levels are :trace, :debug, :info, :warn, :error, :fatal, and :report"
  [logging-level])

(s/fdef set-logging-level
  :args (s/cat :logging-level ::logging-level))

(defn bot-token
  "Takes a bot token, and returns the token value that can
  be used in the \"Authorization\" header for HTTP calls."
  [token]
  (str "Bot " (str/trim token)))
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
  Objects with string keys are converted to maps with keyword keys, except for snowflakes.
  Arrays are converted to vectors with each element recursively conformed."
  [j]
  (cond
    (map? j) (into {}
                   (map (fn [[key val]] [(if (and (string? key)
                                                  (not (Character/isDigit ^Character (first key))))
                                           (json-keyword key)
                                           (clean-json-input key))
                                         (clean-json-input val)]))
                   j)
    (vector? j) (mapv clean-json-input j)
    :else j))
(s/fdef clean-json-input
  :args (s/cat :json-data (s/or :string string?
                                :number number?
                                :bool boolean?
                                :array vector?
                                :object map?
                                :null nil?))
  :ret (s/or :string string?
             :number number?
             :array vector?
             :object map?
             :keyword keyword?
             :boolean boolean?
             :nil nil?))

(defn parse-if-str [input]
  (cond
    (number? input) input
    (string? input) (let [parsed (edn/read-string input)]
                      (if (number? parsed)
                        parsed
                        (throw (IllegalArgumentException. "String must represent a valid number"))))
    :else (throw (IllegalArgumentException. "Argument must be string or number"))))

(deftype DerefablePromiseChannel [ch ^:unsynchronized-mutable realized?]
  clojure.lang.IDeref
  (deref [_]
    (let [res (a/<!! ch)]
      (set! realized? true)
      res))

  clojure.lang.IBlockingDeref
  (deref [_ timeout timeout-val]
    (let [res (a/alt!!
                ch ([v] v)
                (a/timeout timeout) timeout-val)]
      (set! realized? true)
      res))

  clojure.lang.IPending
  (isRealized [_] realized?)

  a.proto/Channel
  (closed? [_] (a.proto/closed? ch))
  (close! [_]
    (set! realized? true)
    (a/close! ch))

  a.proto/ReadPort
  (take! [_ handler]
    (a.proto/take! ch handler))

  a.proto/WritePort
  (put! [_ val handler]
    (a.proto/put! ch val handler)))

(defmethod print-method DerefablePromiseChannel
  [v w]
  (.write ^Writer w "#object[")
  (.write ^Writer w (str (str/replace-first (str v) #"@" " ") " \""))
  (.write ^Writer w (str v))
  (.write ^Writer w "\"]"))

(defmethod print-dup DerefablePromiseChannel
  [o w]
  (print-ctor o (fn [o w] (print-dup (.-ch ^DerefablePromiseChannel o) w)) w))

(prefer-method print-method DerefablePromiseChannel clojure.lang.IPersistentMap)
(prefer-method print-method DerefablePromiseChannel clojure.lang.IDeref)
(prefer-method print-method DerefablePromiseChannel clojure.lang.IBlockingDeref)

(prefer-method print-dup DerefablePromiseChannel clojure.lang.IPersistentMap)
(prefer-method print-dup DerefablePromiseChannel clojure.lang.IDeref)
(prefer-method print-dup DerefablePromiseChannel clojure.lang.IBlockingDeref)

(defn derefable-promise-chan
  "Creates an implementation of [[clojure.lang.IDeref]] which is also a core.async chan."
  ([]
   (derefable-promise-chan nil))
  ([xform]
   (derefable-promise-chan xform nil))
  ([xform ex-handler]
   (DerefablePromiseChannel. (a/promise-chan xform ex-handler) false)))
