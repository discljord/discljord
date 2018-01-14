(ns discljord.messaging
  (:require [discljord.bots :as bots]
            [discljord.connections :as conn]
            [org.httpkit.client :as http]
            [clojure.data.json :as json])
  (:use com.rpl.specter))

(defn mention
  [user-id]
  (str "<@" user-id ">"))

;; TODO Create a system for interacting with the discord HTTP endpoint.
;; This will have to hold a state with all the information about various endpoints
;; that will be used for communication with the discord api. Most notably rate limits.

;; Whenever the user makes a request through the discord HTTP api, the library
;; should check it against a the current state to see if the endpoint is currently
;; under a rate limit, and if it isn't, send off the request immediately, and if it is,
;; stick it in a queue of some kind that is checked over for things which need to be
;; run, and if it is ready to be run, then it will be.

;; how is this going to be accomplished?
;; Possibly create core.async channels for each of the main endpoint types, i.e.
;; one channel for channel operations, one for guilds, one for webhooks, etc.
;; Then if any given request is rate limited, it will go into a vector that another channel
;; constantly sweeps for things which have had their time expire for the rate limit.
;; If it finds one, that item will be run and another sweep will take place.

;; NOTE: use this approach vvvv
;; Another option is to make a large map that's stored separately from the main bot state
;; this map will contain mappings of ids to channels that contain the requests that need
;; to be made related to that id. This way we can guarentee the order of requests for
;; a given channel, guild, and webhook. Have one thread for each, and it will map over
;; and check for when the rate limit is finished, run the request, and if it gets back a rate limit
;; then it will update the rate limit for that endpoint and place the request back at the front
;; of the queue

(defn send-message
  [bot channel-id content]
  (http/post (conn/api-url (str "/channels/" channel-id "/messages"))
             {:headers {"Authorization" (:token bot)
                        "Content-Type" "application/json"}
              :body (json/write-str {:content content})}))
