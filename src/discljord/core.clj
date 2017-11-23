(ns discljord.core
  (:require [discljord.bots :as b]
            [discljord.connections :as c]
            [discljord.messaging :as m]
            [clojure.core.async :as a]))

(defn send-message
  [channel message]
  nil)

(defn mention
  [user]
  nil)

(defn create-bot
  [{:keys [token] :as params}]
  params)

(defn connect-bot
  [bot]
  bot)

(defn disconnect-bot
  [bot]
  bot)

(defmacro defcommands
  [bot param-destructured listener-map]
  (let [b bot
        p param-destructured
        l listener-map]
    nil))
