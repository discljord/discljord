# discljord

Discljord is a library for the easy creation of Discord Bots in Clojure! It works asyncronously by default, and has built-in support for sharding and rate-limits, with no extra work for the bot creator.

## Installation

Add the following to your project.clj in leiningen:

```clojure
[org.suskalo/discljord "0.1.5"]
```

## Usage

To use discljord, add it to the project dependencies in leiningen and run
```
lein deps
```
After that, discljord should be ready to go, and you can start building a bot!

### Basic Bot Construction

Bots in discljord are applications which consist of three separate processes. First is the connection on which Discord sends information to the bot. Second is an event handler which takes care of all the events sent to it by Discord. Third is a messaging process which takes messages the event handler (or other sources) want to send, and sends it to Discord, respecting rate limits.

Communication between these three processes is facilitiated via core.async's channels. To create a connection process, simply call the function `discljord.connections/connect-bot!`, which takes a channel on which you want to communicate with that process, and returns a channel that it will send events on. Starting the process to send messages is done with `discljord.messaging/start-connection!` which takes the token of your bot and returns a channel which you need to keep track of to send it messages, via the other functions in the `discljord.messaging` namespace.

Here's a short example, using the minimum of features to get a bot up and running:

```clojure
(ns example.hello-world
  (:require [discljord.connections :as c]
            [discljord.messaging :as m]
            [clojure.core.async :as a]))
            
(def token "TOKEN")
(def channel "12345")

(defn -main
  [& args]
  (let [event-ch (a/chan 100)
        connection-ch (c/connect-bot! token event-ch)
        message-ch (m/start-connection! token)]
    (loop []
      (let [[event-type event-data] (a/<!! event-ch)]
        (when (and (= :message-create event-type)
                   (= (:channel-id event-data) channel)
                   (not (:bot (:author event-data))))
          (m/send-message! message-ch channel "Hello, World!"))
        (when (= :channel-pins-update event-type)
          (a/>!! connection-ch [:disconnect]))
        (when-not (= :disconnect event-type)
          (recur))))
    (m/stop-connection! message-ch)
    (c/disconnect-bot! connection-ch)))
```

This is a very simple example, which will send the message "Hello, world!" once for each message it recieves in the hard-coded channel from a non-bot user, and disconnects when a message is pinned or unpinned in any channel it can see.

This small example should also help clarify what the three processes are. The first is where you're getting your events, the second is the loop in the above `-main` function, and the third is the messaging connection which you communicate with when calling `send-message!`.

### Event Handlers

Discljord also provides a default event pump to assist with simplicity and extensibility of the code. The one currently supported is a simple pump very similar to the one above which calls a function or multimethod you pass in.

```clojure
(ns example.event-handler
  (:require [discljord.connections :as c]
            [discljord.messaging :as m]
            [discljord.events :as e]
            [clojure.core.async :as a]))

(def token "TOKEN")

(def state (atom nil))

(defmulti handle-event
  (fn [event-type event-data]
    event-type))

(defmethod handle-event :default
  [event-type event-data])

(defmethod handle-event :message-create
  [event-type {{bot :bot} :author :keys [channel-id content]}]
  (when (= content "!disconnect")
    (a/put! (:connection @state) [:disconnect])
    (m/stop-connection!))
  (when-not bot
    (m/send-message! (:messaging @state) channel-id "Hello, World!")))

(defn -main
  [& args]
  (let [event-ch (a/chan 100)
        connection-ch (c/connect-bot! token event-ch)
        messaging-ch (m/start-connection! token)
        init-state {:connection connection-ch
                    :event event-ch
                    :messaging messaging-ch}]
    (reset! state init-state)
    (e/message-pump! event-ch handle-event))
    (c/disconnect-bot! connection-ch))
```

This bot builds slightly on the last, in that it sends its message to the channel it was messaged on (which should include DMs), and if that message is "!disconnect" it will disconnect itself.

Discljord does not currently have an opinion about how you store your state, however in future it may provide additional message pump types which have opinions about state or other parts of your program. These will always be opt-in, and you will always be able to write your own message pump like the first example.

## Known Issues

- If you exceed the rate limit of an endpoint, any other messages sent to that endpoint may arrive out of order.

If you find any other issues, please report them, and I'll attempt to fix them as soon as possible!

## License

Copyright © 2017 Joshua Suskalo

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
