(ns discljord.snowflakes
  "Contains utility functions to parse, deconstruct and create [snowflake ids](https://discord.com/developers/docs/reference#snowflakes)."
  (:require [discljord.util :refer [parse-if-str]]))

(def ^:const discord-epoch
  "The Discord epoch - the number of milliseconds that had passed since January 1, 1970, on January 1, 2015."
  1420070400000)

(defn timestamp
  "Returns the UNIX timestamp (ms) for when this id was generated.

  Takes either a string or a number representing the snowflake."
  [snowflake]
  (+ (bit-shift-right (parse-if-str snowflake) 22) discord-epoch))

(defn internal-worker-id
  "Returns the internal id of the worker that generated this id.

  Takes either a string or a number representing the snowflake."
  [snowflake]
  (bit-shift-right (bit-and (parse-if-str snowflake) 0x3E0000) 17))

(defn internal-process-id
  "Returns the internal id of the process this id was generated on.

  Takes either a string or a number representing the snowflake."
  [snowflake]
  (bit-shift-right (bit-and (parse-if-str snowflake) 0x1F000) 15))

(defn increment
  "Returns the increment of this id, i.e. the number of ids that had been generated before on its process.

  Takes either a string or a number representing the snowflake."
  [snowflake]
  (bit-and (parse-if-str snowflake) 0xFFF))

(defn parse-snowflake
  "Extracts all information from the given id and returns it as a map.

  The map has the following keys:
  `:id` - The input snowflake as a number
  `:timestamp` - See [[timestamp]]
  `:internal-worker-id` - See [[internal-worker-id]]
  `:internal-process-id` - See [[internal-process-id]]
  `:increment` - See [[increment]]"
  [snowflake]
  (let [snowflake (parse-if-str snowflake)]
    {:id                  snowflake
     :timestamp           (timestamp snowflake)
     :internal-worker-id  (internal-worker-id snowflake)
     :internal-process-id (internal-process-id snowflake)
     :increment           (increment snowflake)}))

(defn timestamp->snowflake
  "Takes a UNIX milliseconds timestamp (like from `(System/currentTimeMillis)`) and returns it as a Discord snowflake id.

  This can be used for paginated endpoints where ids are used to represent bounds like
  'before' and 'after' to get results before, after or between timestamps."
  [timestamp]
  (bit-shift-left (- timestamp discord-epoch) 22))
