# discljord

Discljord is a library for the easy creation of Discord Bots in Clojure! It works asyncronously by default, and has built-in support for sharding and rate-limits, with no extra work for the bot creator.

## Installation

### THIS DOESN'T WORK FOR NOW, HOWEVER IT SHOULD BE PUBLISHED SOON

Add the following to your project.clj in leiningen:

```clojure
[discljord "0.1.0-SNAPSHOT"]
```

When working with discljord, there are some things to be aware of. Under the hood, discljord uses core.async and clojure.spec. Since clojure.spec isn't readily available in Clojure 1.8, discljord depends on Clojure 1.9.0-RC1. Additionally, the most recent version of core.async has a bad `:refer-clojure` clause in its `ns` macro call, meaning that it doesn't compile when spec is loaded. This means that instead of the most recent version of core.async, discljord makes use of version 0.3.442 which fixed this issue.

# UNDER CONSTRUCTION. THE FOLLOWING IS NOT ALL ACCURATE (YET).

## Usage

To use discljord, add it to the project dependencies in leiningen and run
```
lein deps
```
After that, discljord should be ready to go, and you can start building a bot!

In order to build that bot, you need to do a few things.

 - Define a bot
 - Associate listeners to that bot
 - Start the bot
 
So to that end, here we go!

### Define a Bot
 
Bots in discljord are atoms that contain maps that fit with the `:discljord.spec/bot` definition. When initially creating a bot though, you don't need a fully defined `::bot` map. Instead you use the `create-bot` function from the `discljord.core` namespace.

```clojure
(ns readme.example
  (:require [discljord.core :as discord]
            [discljord.spec :as ds]))
  
(def token "RANDOMTOKEN")
  
(def bot (atom (create-bot {::ds/token token})))
```

That will create a bot with the given token.

### Associate Listeners

There are effectively two kinds of listeners in discljord: command listeners, and event listeners. Command listeners are used for responding to specific commands, and are what is most frequenly used by bots. Event listeners will listen in to specific events that discord sends through their websocket api. First, let's look at command listeners:

```clojure
(defcommands bot
  {:keys [user channel contents] :as params}
  {"echo" {:help-text "Echos back whatever was said in the command."
           :callback (discord/message channel (str (discord/mention user) contents))}
   "exit" {:help-text "Makes the bot quit"
           :callback (discord/disconnect! bot)}})
```

The defcommands macro takes the bot on which you are listening for commands, a destructuring form, and a map of commands to maps that define said commands. The destructuring form is used in a let to destructure a map, so you can use either a simple symbol, or associative destructuring to get only the required keys out.

To create an event listener, it acts very similarly:

```clojure
(deflistener bot
  :guild-member-add
  {:keys [user guild-id] :as params}
  (discord/message (discord/main-channel guild-id)
                   (str (discord/mention user) "Welcome to " (discord/guild-name guild-id) "!")))
```

This event listener will wait for an event to come in with the type of "GUILD_MEMBER_ADD", will bind the data object to `params` and fetch out relevant keys, and finally run the body.

### Start the Bot

Finally, to start the bot, you simply need to call `start-bot!` like so:

```clojure
(defn -main
  []
  (discord/start-bot! bot))
```

And that's all there is to it!

## Handling state

In discljord, state is stored within atoms contining maps. There are two kinds of state in discljord, global state, and guild-specific state. Global state is used for the entire bot and exists across all guilds. Guild-specific state is state that is stored inside a specific guild.

### Global State

TODO: Global state system

### Guild-Specific State

TODO: Guild-specific state system

## Known Issues

None yet. If you happen to find something though, please post it in the Issues page of this repository!

## How it Works

TODO: Give background on how discljord works under the hood.

### Asynchronous Calls

TODO: Give specific information on how core.async is used in discljord.

### Sharding

TODO: Give specific information on how sharding is handled in dislcjord.

## License

Copyright © 2017 Joshua Suskalo

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
