# discljord

Discljord is a library for the easy creation of Discord Bots in Clojure! It works asyncronously by default, and has built-in support for sharding and rate-limits, with no extra work for the bot creator.

## Installation

Add the following to your project.clj in leiningen:

```clojure
[org.suskalo/discljord "1.0.0-SNAPSHOT"]
```

## Usage

To use discljord, add it to the project dependencies in leiningen's `project.clj` and run
```
lein deps
```
or to tools.deps' `deps.edn` and run
```
clj -e "(println \"Dependencies downloaded\")"
```
After that, discljord should be ready to go, and you can start building a bot!

### Bot Registration

Before your code can connect to Discord and start making API requests, it must be registered with Discord.  This is a one-time activity per bot, and involves:

1. Logging in to the [Discord developer portal](https://discord.com/developers).
2. Creating a new application (blue "New Application" button, top right).
3. Navigating to the "Bot" tab on the left, and click the blue "Add Bot" button.  Confirm your choice (blue "Yes, do it!" button).
4. Copying the bot's auth token (blue "Copy" button) and paste it somewhere secure (**do not save this token anywhere public!**).
5. Turning off the "Public Bot" setting (halfway down the page).  This step is optional, and you can turn this back on once your bot is released if you want others to be able to find it.
6. Navigating to the "OAuth2" tab on the left, and select the "bot" checkbox in the Scopes section.
7. Copying the URL generated in the previous step (blue "Copy" button), then paste it into a new browser tab.
8. Selecting which Discord server(s) you wish to add the bot to (note: you must have the "Manage Servers" role for servers to be visible in the dropdown).

After a few minutes, the bot should become visible as a user in the selected server, and it will be able to connect to, and interact with, that server.

### Basic Bot Construction

Bots in discljord are applications which consist of three separate processes. First is the connection on which Discord sends information to the bot. Second is an event handler which takes care of all the events sent to it by Discord. Third is a messaging process which takes messages the event handler (or other sources) want to send, and sends it to Discord, respecting rate limits.

Communication between these three processes is facilitiated via core.async's channels. To create a connection process, simply call the function `discljord.connections/connect-bot!`, which takes a channel on which you want to communicate with that process, and returns a channel that it will send events on. Starting the process to send messages is done with `discljord.messaging/start-connection!` which takes the token of your bot and returns a channel which you need to keep track of to send it messages, via the other functions in the `discljord.messaging` namespace.

### Examples

Each of these examples can be run in a REPL, either via `lein try org.suskalo/discljord` (if you're using leiningen) or `clj -Sdeps '{:deps {org.suskalo/discljord #:mvn {:version "0.2.9"}}}'` (if you're using the Clojure CLI tools).  All of the examples are blocking (you will not be returned to the REPL until the bots are terminated).

#### Logging Bot

This example prints out all events that the bot receives to stdout, and is useful for learning what events and associated information can be sent by Discord.  Terminate it with Ctrl+C (note: when using the Clojure CLI tools this will also terminate your REPL).

```clojure
(require '[clojure.core.async    :as a])
(require '[discljord.connections :as c])
(require '[discljord.messaging   :as m])

(def token "TOKEN")

(let [event-ch      (a/chan 100)
      connection-ch (c/connect-bot! token event-ch)
      message-ch    (m/start-connection! token)]
  (try
    (loop []
      (let [[event-type event-data] (a/<!! event-ch)]
        (println "🎉 NEW EVENT! 🎉")
        (println "Event type:" event-type)
        (println "Event data:" (pr-str event-data))
        (recur)))
    (finally
      (m/stop-connection! message-ch)
      (c/disconnect-bot!  connection-ch)
      (a/close!           event-ch))))
```

#### Hello World Bot

This example responds with "Hello, World" to every message a human user posts in a given channel.  Note that you will need to determine the id of the channel (a channel-id is a long number that is stored in a string); this can be done using the logging bot above.  Terminate it by pinning or unpinning a message in the channel.

```clojure
(require '[clojure.core.async    :as a])
(require '[discljord.connections :as c])
(require '[discljord.messaging   :as m])

(def token      "TOKEN")
(def channel-id "12345")

(let [event-ch      (a/chan 100)
      connection-ch (c/connect-bot! token event-ch)
      message-ch    (m/start-connection! token)]
  (try
    (loop []
      (let [[event-type event-data] (a/<!! event-ch)]
        (when (and (= :message-create event-type)
                   (= (:channel-id event-data) channel)
                   (not (:bot (:author event-data))))
          (m/create-message! message-ch channel :content "Hello, World!"))
        (when (= :channel-pins-update event-type)
          (a/>!! connection-ch [:disconnect]))
        (when-not (= :disconnect event-type)
          (recur))))
    (finally
      (m/stop-connection! message-ch)
      (c/disconnect-bot!  connection-ch)
      (a/close!           event-ch))))
```

This small example should also help clarify what the three processes are. The first is where you're getting your events, the second is the loop in the `let` form, and the third is the messaging connection which you communicate with when calling `create-message!`.

#### Echo Bot

This example responds to messages by echoing whatever is said by human users in a specific channel, except for the message "!exit" (which terminates the bot).

```clojure
(require '[clojure.string        :as s])
(require '[clojure.core.async    :as a])
(require '[discljord.connections :as c])
(require '[discljord.messaging   :as m])

(def token      "TOKEN")
(def channel-id "12345")

(let [event-ch      (a/chan 100)
      connection-ch (c/connect-bot! token event-ch)
      message-ch    (m/start-connection! token)]
  (try
    (loop []
      (let [[event-type event-data] (a/<!! event-ch)]
        (when (and (= :message-create event-type)
                   (= (:channel-id event-data) channel)
                   (not (:bot (:author event-data))))
          (let [message-content (:content event-data)]
            (if (= "!exit" (s/trim (s/lower-case message-content)))
              (do
                (m/create-message! message-ch channel-id :content "Goodbye!")
                (a/>!! connection-ch [:disconnect]))
              (m/create-message! message-ch channel :content message-content))))
        (when (= :channel-pins-update event-type)
          (a/>!! connection-ch [:disconnect]))
        (when-not (= :disconnect event-type)
          (recur))))
    (finally
      (m/stop-connection! message-ch)
      (c/disconnect-bot!  connection-ch)
      (a/close!           event-ch))))
```

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
  (if (= content "!disconnect")
    (a/put! (:connection @state) [:disconnect])
    (when-not bot
      (m/create-message! (:messaging @state) channel-id :content "Hello, World!"))))

(defn -main
  [& args]
  (let [event-ch (a/chan 100)
        connection-ch (c/connect-bot! token event-ch)
        messaging-ch (m/start-connection! token)
        init-state {:connection connection-ch
                    :event event-ch
                    :messaging messaging-ch}]
    (reset! state init-state)
    (e/message-pump! event-ch handle-event)
    (m/stop-connection! messaging-ch)
    (c/disconnect-bot! connection-ch)))
```

This bot builds slightly on the earlier Hello World bot, in that it sends its message to the channel it was messaged on (which should include DMs), and if that message is "!disconnect" it will disconnect itself.

Discljord does not currently have an opinion about how you store your state, however in future it may provide additional message pump types which have opinions about state or other parts of your program. These will always be opt-in, and you will always be able to write your own message pump like the first example.

### Intents
As of right now, Discord will send your bot all events which happen on any guild that your bot is in, however you can specify [intents](https://discord.com/developers/docs/topics/gateway#gateway-intents) to specify which events you want to receive. In the future, Discord intends to make these intents mandatory. They can be specified as a keyword argument to `discljord.connections/connect-bot!`, and are represented as a set of keywords in lower-kebab-case.

## Known Issues
None at the moment

If you find any other issues, please report them, and I'll attempt to fix them as soon as possible!

## License

Copyright © 2017-2020 Joshua Suskalo

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
