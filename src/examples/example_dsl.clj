(ns examples.example-dsl
  (:require [discljord.core :as discord
             :refer :all]
            [clojure.java.io :as io])
  (:gen-class))

(def creator (slurp (io/resource "author.txt")))

(defcommand disconnect "disconnect"
  [guild channel author args]
  (when (= (:id author)
         creator)
    (disconnect-bot!)))

(defcommand prefix "prefix"
  [guild channel author args]
  (update-prefix *guild-state* (first args)))

(defregexcommand quote-add #"quote\s+add\s+\"([^\"]+)\"\s+-\s+(.+)"
  [guild channel author matches]
  (let [quote (first (drop 1 matches))
        quote-author (first (drop 2 matches))
        quote-id (count ((:quotes *guild-state*) quote-author))]
    (guild-state+ [:quotes quote-author]
                  (conj ((:quotes *guild-state*) quote-author)
                        quote))
    (send-message! (str "Quote added to " quote-author " with id " quote-id "!"))))

(defcommand quote-remove "quote remove"
  [guild channel author args]
  (let [quote-author (first args)
        quote-id (nth args 2)]
    (guild-state+ [:quotes quote-author]
                  (vec (remove #{quote-id}
                               ((:quotes *guild-state*) quote-author))))))

(defcommand quote "quote"
  [guild channel author args]
  (let [[quote-author quotes-vec] (rand-nth (:quotes *guild-state*))
        quote-id (rand (count quotes-vec))
        quote (nth quotes-vec quote-id)]
    (send-message! (str quote-id " " quote-author ": \"" quote "\""))))

(def default-prefix "!")

(defhandler connect :connect
  []
  (set-playing "with DSLs"))

(defhandler user-join :user-join
  [user]
  (send-message! (str "Greetings, " (:username user) "! Welcome to the server!")))

(defhandler commands :message-create
  [guild channel author message]
  (commands default-prefix
            disconnect
            prefix
            quote-add
            quote-remove
            quote))

(defbot bot
  connect
  user-join
  commands)

(defn -main
  [& args]
  (connect-bot bot))
