![Discljord](img/comp.png)

Discljord is a library for the easy creation of Discord Bots in Clojure! It works asynchronously by default, and has built-in support for sharding and rate-limits, with no extra work for the bot creator. Feel free to drop on by our [support server](https://discord.gg/discljord) if you'd like to try it out or need any help.

## Version 1.0.0 Released

With version 1.0.0 of discljord finally coming out, it's finally reaching "feature completion", in that it fits with my initial vision of what the library should provide. It has user-controlled sharding and transparent sharding, full support for the entire REST API, support for gateway communication, and transparent representations of all the events that discord sends.

This does not however mean that the project is going into maintenance mode, but rather than further development will be to keep the project up to date with changes to Discord's API, and also add new features to make development of bots easier and more streamlined.

Current future plans involve adding features for caching information Discord sends to the bot, validating user permissions, creating commands, etc.

## Quick Start

With [leiningen](https://leiningen.org) you can set up a project really quickly:

1. Run `lein new discljord first-bot`

2. Put your bot token in `config.edn`

3. Run `lein run`

4. Your bot should go online now!

Alternatively, you can start a REPL (Read-Eval-Print-Loop) with `lein repl` and experiment with the API interactively.

If you are new to Clojure, have a look at a [language introduction](https://gist.github.com/yogthos/be323be0361c589570a6da4ccc85f58f) before you dive straight in. You should also get a general idea of [how to use the REPL](https://clojure.org/guides/repl/introduction).

The `start-bot!` and `stop-bot!` functions help you connect/disconnect to/from the Discord API from the REPL.

## Installation

Add the following to your project.clj in leiningen:

```clojure
[org.suskalo/discljord "1.2.3"]
```

If you use tools.deps, then add the following to your `:dependencies` key in your `deps.edn`:

```clojure
{org.suskalo/discljord {:mvn/version "1.2.3"}}
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
                   (= (:channel-id event-data) channel-id)
                   (not (:bot (:author event-data))))
          (m/create-message! message-ch channel-id :content "Hello, World!"))
        (when (= :channel-pins-update event-type)
          (c/disconnect-bot! connection-ch))
        (when-not (= :disconnect event-type)
          (recur))))
    (finally
      (m/stop-connection! message-ch)
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
                (c/disconnect-bot! connection-ch))
              (m/create-message! message-ch channel :content message-content))))
        (when (= :channel-pins-update event-type)
          (c/disconnect-bot! connection-ch))
        (when-not (= :disconnect event-type)
          (recur))))
    (finally
      (m/stop-connection! message-ch)
      (a/close!           event-ch))))
```

### Event Handlers

Discljord also provides a default event pump to assist with simplicity and extensibility of the code. The one currently supported is a simple pump very similar to the one above which calls a function or multimethod you pass in.

```clojure
(require '[discljord.connections :as c])
(require '[discljord.messaging :as m])
(require '[discljord.events :as e])
(require '[clojure.core.async :as a])

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
    (c/disconnect-bot! (:connection @state))
    (when-not bot
      (m/create-message! (:messaging @state) channel-id :content "Hello, World!"))))

(let [event-ch (a/chan 100)
      connection-ch (c/connect-bot! token event-ch)
      messaging-ch (m/start-connection! token)
      init-state {:connection connection-ch
                  :event event-ch
                  :messaging messaging-ch}]
  (reset! state init-state)
  (try (e/message-pump! event-ch handle-event)
    (finally
      (m/stop-connection! messaging-ch)
      (a/close!           event-ch))))
```

This bot builds slightly on the earlier Hello World bot, in that it sends its message to the channel it was messaged on (which should include DMs), and if that message is "!disconnect" it will disconnect itself.

Discljord does not currently have an opinion about how you store your state, however in future it may provide additional message pump types which have opinions about state or other parts of your program. These will always be opt-in, and you will always be able to write your own message pump like the first example.

### Declarative Event Handlers

The event pump provided by discljord will accept any function that takes an event type and event data, but discljord also provides a utility function to perform event dispatch to different functions per event type.

```clojure
(require '[discljord.connections :as c])
(require '[discljord.messaging :as m])
(require '[discljord.events :as e])
(require '[clojure.core.async :as a])

(def token "TOKEN")

(def state (atom nil))

(defn greet-or-disconnect
  [event-type {{bot :bot} :author :keys [channel-id content]}]
  (if (= content "!disconnect")
    (a/put! (:connection @state) [:disconnect])
    (when-not bot
      (m/create-message! (:messaging @state) channel-id :content "Hello, World!"))))

(defn send-emoji
  [event-type {:keys [channel-id emoji]}]
  (when (:name emoji)
    (m/create-message! (:messaging @state) channel-id
                       :content (if (:id emoji)
                                  (str "<:" (:name emoji) ":" (:id emoji) ">")
                                  (:name emoji)))))

(def handlers
  {:message-create [#'greet-or-disconnect]
   :message-reaction-add [#'send-emoji]})

(let [event-ch (a/chan 100)
      connection-ch (c/connect-bot! token event-ch)
      messaging-ch (m/start-connection! token)
      init-state {:connection connection-ch
                  :event event-ch
                  :messaging messaging-ch}]
  (reset! state init-state)
  (try (e/message-pump! event-ch (partial e/dispatch-handlers #'handlers))
    (finally
      (m/stop-connection! messaging-ch)
      (c/disconnect-bot! connection-ch))))
```

This bot will send emoji to the channel that a reaction is added in, as well as perform the same hello-world response the previous bot did. The main difference is that the actual dispatch to different handler functions is done via data, rather than via a multimethod. Additionally, this adds the ability to call multiple handler functions with a single event.

### Intents

As of right now, Discord will send your bot all events which happen on any guild that your bot is in, however you can specify [intents](https://discord.com/developers/docs/topics/gateway#gateway-intents) to specify which events you want to receive. In the future, Discord intends to make these intents mandatory. They can be specified as a keyword argument to `discljord.connections/connect-bot!`, and are represented as a set of keywords in lower-kebab-case.

## Logging

Discljord uses [clojure.tools.logging](https://github.com/clojure/tools.logging) for all its logging, which means that you are responsible for providing it with a suitable logging implementation as well as configuration. If no configuration is provided, then it will likely default to whatever gets brought in by your other dependencies, potentially eventually falling back to `java.util.logging`, however it should still function even with no intervention by the library user.

Logging levels in discljord follow a basic pattern that anything at a `warn` level or lower is handled by discljord, and `error` or `fatal` will be used to inform the user of something that they can or should take action in an attempt to fix. Besides the generalization, here is a more specific listing of the purpose of the logging levels:

- Fatal :: Used when some condition is met that prevents discljord from continuing, even with user input
- Error :: Used when an unhandled error occurs or when user error causes the application to fail
- Warn :: Used when an error occurs but is handled by discljord without user input
- Info :: Used for application-scale control flow
- Debug :: Used for process-scale control flow
- Trace :: Used for logging all communication events and function-level control flow

## Known Issues

 - Compression may fail on very large payloads, appearing as an EOF while parsing JSON exception. This can be mitigated by setting the `disable-compression` flag on `connect-bot!`
 - `with-counts?` keyword argument on `discljord.messaging/get-guild!` is specified with the keyword `:with-counts`, without a questionmark

If you find any other issues, please report them, and I'll attempt to fix them as soon as possible!

## Supporting the Project
If you would like to contribute financially to the creation of Discljord, a [Patreon page](https://www.patreon.com/discljord) is available to support me. There's absolutely no obligation to donate if you use this software, but if you do, I'll add your username to this document to thank you for your support!

## Current Patrons
You could have your name added here if you support the project!

- Jonathan Walch
- Johnny JayJay
- Peter Monks

## License

Copyright © 2017-2021 Joshua Suskalo

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
