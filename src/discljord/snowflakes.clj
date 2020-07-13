(ns discljord.snowflakes
  "Contains utility functions to parse, deconstruct and create [snowflake ids](https://discord.com/developers/docs/reference#snowflakes)."
  (:require [clojure.edn :as edn]))

(def ^:const discord-epoch
  "The Discord epoch, or the number of milliseconds that had passed since January 1, 1970 on January 1, 2015."
  1420070400000)

(defn- parse-if-str [input]
  (cond
    (number? input) input
    (string? input) (let [parsed (edn/read-string input)]
                      (if (number? parsed)
                        parsed
                        (throw (IllegalArgumentException. "Snowflake string must represent a valid number"))))
    :else (throw (IllegalArgumentException. "Snowflake must be string or number"))))

(defn timestamp [snowflake]
  (+ (bit-shift-right (parse-if-str snowflake) 22) discord-epoch))

(defn internal-worker-id [snowflake]
  (bit-shift-right (bit-and (parse-if-str snowflake) 0x3E0000) 17))

(defn internal-process-id [snowflake]
  (bit-shift-right (bit-and (parse-if-str snowflake) 0x1F000) 15))

(defn increment [snowflake]
  (bit-and (parse-if-str snowflake) 0xFFF))

(defn parse-snowflake [snowflake]
  (let [snowflake (parse-if-str snowflake)]
    {:timestamp           (timestamp snowflake)
     :internal-worker-id  (internal-worker-id snowflake)
     :internal-process-id (internal-process-id snowflake)
     :increment           (increment snowflake)}))

(defn timestamp->snowflake [timestamp]
  (bit-shift-left (- timestamp discord-epoch) 22))
