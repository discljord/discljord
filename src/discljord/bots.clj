(ns discljord.bots
  (:require [clojure.core.async :as a]
            [discljord.connections :as conn]))

(defn start-message-proc
  [event-channel event-listeners]
  (a/go-loop []
    (let [event (a/<! event-channel)]
      (doseq [{:keys [channel]} (filter #(= (:event event) (:event %)) event-listeners)]
        (a/>! channel event)))
    (recur)))
