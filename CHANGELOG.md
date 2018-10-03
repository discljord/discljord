# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
 - Changelog
 - Logging about retries during connections
 - Request :guild-members-chunk events
 - Ability to update bot's status on Discord
 - Ability to update bot's voice state on Discord
 - Ability to get roles from a guild using the messaging api

### Removed
 - Uberjar profile in Leiningen
 
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

[Unreleased]: https://github.com/IGJoshua/discljord/compare/0.1.6..develop
[0.1.6]: https://github.com/IGJoshua/discljord/compare/0.1.5..0.1.6
[0.1.5]: https://github.com/IGJoshua/discljord/compare/0.1.4..0.1.5
[0.1.4]: https://github.com/IGJoshua/discljord/compare/0.1.3..0.1.4
[0.1.3]: https://github.com/IGJoshua/discljord/compare/0.1.2..0.1.3
[0.1.2]: https://github.com/IGJoshua/discljord/compare/0.1.1..0.1.2
[0.1.1]: https://github.com/IGJoshua/discljord/compare/0.1.0..0.1.1
[0.1.0]: https://github.com/IGJoshua/discljord/compare/aa53670762bd6c5ecc35081115b438699be24077...0.1.0
