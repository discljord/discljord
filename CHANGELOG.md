# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
Discljord follows semantic versioning.

## [Unreleased]
### Added
 - Support for audit log reasons on all requests made with `discljord.messaging`
 - Support for gateway intents
 - Support for allowed-mentions in `discljord.messaging/execute-webhook!` payload
 - Support for allowed-mentions in `discljord.messaging/create-message!` payload

### Changed
 - `discljord.messaging/execute-webhook!` had its parameters moved to optional ones

### Fixed
 - Websocket clients of closed websockets were not stopped
 - `discljord.messaging/execute-webhook!` was unable to send files
 - Documentation for create-message! saying file sharing was not implemented

## [0.2.7]
### Fixed
 - `:disconnect` event no longer sent when bot shuts down

## [0.2.6]
### Fixed
 - Bots are unable to disconnect properly
 - Bots fail to reconnect after spending time with internet unavailable

## Deprecated
 - `discljord.util/*enable-logging*`, prefer directly interacting with timbre
 - `discljord.util/set-logging-level!`, prefer directly interacting with timbre

## Upgraded
 - gniazdo version

## [0.2.5]
### Added
 - Support for the Watching activity type
 - Support for pass-through activity types in case discljord doesn't support them all
 - Audit log reason for modify-guild (this is a framework to add this to all other requests which it would be applicable to)

### Fixed
 - Incorrect URL used for http api
 - remove-guild-member-role! makes a DELETE to /memebers instead of /members

## [0.2.4] - 2019-03-24
### Fixed
 - NullPointerException in rare case when dealing with rate limits
 - Rate-limit decreased by one compared to what it should be if always recieve headers
 - SSLContext endpoint identification algorithm warning
 - Deprecated method send-message! broke

## [0.2.3] - 2019-03-01
### Fixed
 - discljord.events not loading properly due to removed dependency for clojure.tools.logging

## [0.2.2] - 2019-03-01
### Fixed
 - http-kit HTTP requests would break on java 11

### Removed
 - Unnecessary dependency on clojure.tools.logging

## [0.2.1] - 2019-02-27
### Changed
 - Spec for files to be used with create-message! now requires a file rather than any?

### Fixed
 - Rate limit handling now accounts for server drift

## [0.2.0] - 2019-02-26
### Added
 - Changelog
 - Logging about retries during connections
 - Request :guild-members-chunk events
 - Ability to update bot's status on Discord
 - Ability to update bot's voice state on Discord
 - Support for re-sharding a bot as it grows
 - Docstrings for many namespaces
 - Support for tts in messages
 - New HTTP endpoint support (all)
 - Logging when unable to recieve gateway information
 - Support for embeds (with attachments)
 - Support for sending files
 
### Changed
 - Moved many functions from `discljord.connections` to an implementation namespace
 - Added new `create-message!` function to replace `send-message!`
 - Exchanged logging framework from log4j to timbre
 
### Fixed
 - Improper handling of rate limits when none is found
 - Bot no longer starts when out of identify packets for shards
 
### Depricated
 - `send-message!` function

### Removed
 - Uberjar profile in Leiningen
 
## [0.1.7] - 2019-01-13
### Fixed
 - README.md example bots
 
## [0.1.6] - 2018-10-02
### Added
 - log4j backend for `core.tools.logging`
 - discljord log4j configuration
 - deps.edn file to allow use with tools.deps projects
 - README section on Java 9 and later

### Removed
 - Unnecessary JVM options
 
## [0.1.5] - 2018-09-25
### Added
 - Buffer size for shards can now be specified by users

### Changed
 - Buffers now grow by 100k bytes on 1009 stop codes, rather than being set to a static 500k bytes

### Fixed
 - Removed unneeded dependency
 - No default buffer size provided
 - Buffer size not properly set on client
 
## [0.1.4] - 2018-09-25 
### Fixed
 - Incorrect spec on function `connect-bot!`
 - Exception thrown by shards due to atoms not being associative data structures
 
## [0.1.3] - 2018-09-24 
### Fixed
 - README installation lacked group ID
 
## [0.1.2] - 2018-09-24 
### Added
 - Group ID to Maven artifact
 - Disconnects with stop code 1009 now reconnect with larger buffers
 - Can now set flag so that the bot will not try to reconnect when it disconnects
 - Function to disconnect all shards in a bot
 - Exception handling in default message pump in `discljord.events`
 
### Changed
 - `handle-disconnect!` now takes the shard state
 - `connect-shard!` now returns a tuple of atoms, first is the connection, second is shard state

## [0.1.1] - 2018-09-23
### Fixed
 - Incorrect README version
 
### Removed
 - Example DSL code
 - `user` namespace

## [0.1.0] - 2018-09-23
### Added
 - Connection process
 - Shard handling
 - Messaging process
 - Copyright notice in README
 - Proper description for project
 - Set up deployment
 
### Changed
 - README follows new API
 - Project name from `discljord-functional` to `discljord` 

[Unreleased]: https://github.com/IGJoshua/discljord/compare/0.2.7..develop
[0.2.7]: https://github.com/IGJoshua/discljord/compare/0.2.6..0.2.7
[0.2.6]: https://github.com/IGJoshua/discljord/compare/0.2.5..0.2.6
[0.2.5]: https://github.com/IGJoshua/discljord/compare/0.2.4..0.2.5
[0.2.4]: https://github.com/IGJoshua/discljord/compare/0.2.3..0.2.4
[0.2.3]: https://github.com/IGJoshua/discljord/compare/0.2.2..0.2.3
[0.2.2]: https://github.com/IGJoshua/discljord/compare/0.2.1..0.2.2
[0.2.1]: https://github.com/IGJoshua/discljord/compare/0.2.0..0.2.1
[0.2.0]: https://github.com/IGJoshua/discljord/compare/0.1.7..0.2.0
[0.1.7]: https://github.com/IGJoshua/discljord/compare/0.1.6..0.1.7
[0.1.6]: https://github.com/IGJoshua/discljord/compare/0.1.5..0.1.6
[0.1.5]: https://github.com/IGJoshua/discljord/compare/0.1.4..0.1.5
[0.1.4]: https://github.com/IGJoshua/discljord/compare/0.1.3..0.1.4
[0.1.3]: https://github.com/IGJoshua/discljord/compare/0.1.2..0.1.3
[0.1.2]: https://github.com/IGJoshua/discljord/compare/0.1.1..0.1.2
[0.1.1]: https://github.com/IGJoshua/discljord/compare/0.1.0..0.1.1
[0.1.0]: https://github.com/IGJoshua/discljord/compare/aa53670762bd6c5ecc35081115b438699be24077...0.1.0
