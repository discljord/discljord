# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
Discljord follows semantic versioning.

## [1.3.1] - 2022-01-22
IMPORTANT, this is the first release on the new com.github.discljord group id.
If you update the version and it doesn't appear, ensure you've updated your
group id.

### Added
 - Support for the `guild-scheduled-events` intent

### Fixed
 - Arity exception in permissions namespace
 - Reflection warning from the util ns
 - Incorrect gateway version was requested

## [1.3.0] - 2021-11-30
### Added
 - Timestamp formatting utilities in `discljord.formatting`
 - Sticker Endpoints
 - Stage Endpoints
 - Additional arities on caching middleware functions for custom caching configuration and handlers
 - Threads Feature
   - Endpoints
     - `start-thread-with-message!`
     - `start-thread-without-message!`
     - `join-thread!`
     - `add-thread-member!`
     - `leave-thread!`
     - `remove-thread-member!`
     - `list-thread-members!`
     - `list-active-threads!`
     - `list-public-archived-threads!`
     - `list-private-archived-threads!`
     - `list-joined-private-archived-threads!`
   - New caching middlewares to cache threads
 - Initial support for Message Components
 - New function for creating handlers for multiple events, `discljord.events/normalize-handlers`
 - Application Commands Feature
   - Management Endpoints
     - `get-global/guild-application-commands!`
     - `create-global/guild-application-command!`
     - `get-global/guild-application-command!`
     - `edit-global/guild-application-command!`
     - `delete-global/guild-application-command!`
     - `bulk-overwrite-global/guild-application-command!`
     - `get-guild-application-command-permissions!`
     - `get-application-command-permissions!`
     - `edit-application-command-permissions!`
     - `batch-edit-application-command-permissions!`
   - Interactions Endpoints
     - `create-interaction-response!`
     - `get-original-interaction-response!`
     - `edit-original-interaction-response!`
     - `delete-original-interaction-response!`
     - `create-followup-message!`
     - `edit-followup-message!`
     - `delete-followup-message!`
 - New webhook endpoints
   - `get-webhook-message!`
   - `edit-webhook-message!`
   - `delete-webhook-message!`
 - `execute-webhook!` now also accepts a stream like `create-message!`
 - Keyword argument `:shards` to `discljord.connections/status-update!` to specify which shards to send to

### Changed
 - Add public vars defining the API version used
 - Set API version to `v9`
 - Removed question mark from argument for `get-guild`'s `with-counts` for being redundant

### Fixed
 - Some invalid keywords could be constructed during event deserialization
 - Rate limit checks would crash the messaging component when instrumented
 - String permissions values passed from Discord failed to parse in permissions functions
 - Invalid intents were accepted on bot connection
 - The `ex-info` produced for missing intents in `discljord.connections/connect-bot!` was not thrown
 - Previous user and guilds state was discarded in ready event caching handler
 - The `Content-Type` header was sent on requests with no body
 - Event data with snowflake keys would be conformed to keyword keys
 - User state was not cached during guild member chunks
 - User state was incorrectly stored during presence updates
 - User state was not cached from member updates
 - Guild member state was not correctly added in the caching
 - User's token was logged at trace level logging on identify
 - `discljord.events.middleware/transduce` throws an arity exception for using the incorrect `concat`
 - `:discljord.specs/snowflake` spec does not throw an exception anymore when passed a string
 - Added missing specs for `wait` param in `execute-webhook` and `stream` param in various endpoints
 - Fixed ambiguities regarding specs for parameters named `type`

## [1.2.3] - 2021-05-26
### Fixed
 - Case where websocket clients weren't closed in some cases

## [1.2.2] - 2021-01-20
### Fixed
 - Fix wrong uses of body/query-params in
    - `get-guild`
    - `group-dm-add-recipient`
    - `create-guild-ban`
    - `edit-channel-permissions`
 - Double checking for sequence numbers on resume
 - `ex-info` called with an incorrect arity when missing intents

## [1.2.1] - 2021-01-19
### Added
 - Add field for `with_counts` to be passed on `get-guild!`

## [1.2.0] - 2020-12-09
### Added
 - `get-shard-state!`, `add-shards!`, and `remove-shards!` to the `discljord.connections` namespace to allow transferring shards
 - `discljord.messaging/get-guild-widget!` to fetch the full guild widget json
 - Support for creating replies
 - Updated to API v8
 - `discljord.permissions/permission-flags` as a counterpart to `permission-int`
 - `discljord.permissions/permission-int` arity to construct custom permission integers
 - Option to disable transport compression on the gateway api
 - Namespace with functions to extract information from Discord's snowflake ids

### Changed
 - Connection functions return an `ex-info` when `intents` are not specified, in compliance with API v8

### Fixed
 - Failure to resume on several types of connection loss
 - Inconsistent handling of releasing of the websocket client
 - `ex-info` values are sent on 429s from HTTP endpoints even if they are later retried
 - Errors occur when tokens have whitespace at the beginning and ends

### Deprecated
 - `discljord.messaging/get-guild-embed!`, prefer `get-guild-widget-settings!`
 - `discljord.messaging/modify-guild-embed!`, prefer `modify-guild-widget!`

## [1.1.1] - 2020-07-15
### Fixed
 - Gateway disconnects when a heartbeat is sent on a closed websocket (for real this time)

## [1.1.0] - 2020-07-12
### Added
 - Namespace with utilities to create URLs to Discord's CDN, such as avatars or icons
 - Namespace with functions for validating if a user has a given permission
 - Message formatting utilities including functions to create mentions, Markdown styling and user tags (User#1234)
 - Support for get current application information endpoint
 - Middleware to cache information that Discord sends during events
 - Function to create an event handler which dispatches to functions based on event type
 - Middleware for filtering out messages from bots
 - Middleware for making event streams transducible (somewhat redundant with the channel having a transducer)
 - Middleware for mapping and filtering
 - Middleware to concat handlers
 - Middleware for event handlers

### Changed
 - Stop code 4013 (invalid intent) labeled as user error
 - If the promise returned by `message-create!` contains a body but the response code was not a success, it will be wrapped as the body of an `ex-info`
 - Gateway connections now use zlib transport compression

### Fixed
 - Gateway disconnects when a heartbeat is sent on a closed websocket

## [1.0.1] - 2020-07-15
### Fixed
 - Gateway disconnects when a heartbeat is sent on a closed websocket

## [1.0.0] - 2020-06-27
### Added
 - Support for user-level shard control (enabling distributed bots)

### Changed
 - Logging library from `taoensso.timbre` to `clojure.tools.logging`
 - Minimum reconnect time for a single shard reduced to 0 seconds

### Fixed
 - Bots will not try to resume
 - Buffer size for bots is too small for large servers
 - Reflection warnings
 - Multiple bots in the same JVM have identify rate limits interfere with each other
 - Instrumenting `message-pump!` calls the function repeatedly
 - Instrumenting calls to all functions raises internal errors
 - Resumes do not reset the retry count
 - Direct memory leak when reconnects occur (for real this time)

## [0.2.9] - 2020-06-18
### Added
 - Support for doing a parking take on the promises returned from all REST endpoint functions
 - Support for removing all reactions of a given emoji from a message
 - Support for stream-like data in `discljord.messaging/create-message!`

### Fixed
 - Messages arrive out of order when rate limits are hit
 - Gateway communication started while a shard is disconnected crashes discljord
 - Spec for embeds had incorrect type for embed field values
 - Direct memory leak when reconnects occur

### Removed
 - Dependency on `com.rpl.specter`

## [0.2.8] - 2020-04-13
### Added
 - Support for audit log reasons on all requests made with `discljord.messaging`
 - Support for gateway intents
 - Support for allowed-mentions in `discljord.messaging/execute-webhook!` payload
 - Support for allowed-mentions in `discljord.messaging/create-message!` payload

### Changed
 - `discljord.messaging/execute-webhook!` had its parameters moved to optional ones

### Fixed
 - Invalid payload for bulk-message-delete requests
 - User-requested disconnect does not send a `:disconnect` event
 - Websocket clients of closed websockets were not stopped
 - `discljord.messaging/execute-webhook!` was unable to send files
 - Documentation for create-message! saying file sharing was not implemented

## [0.2.7] - 2020-02-24
### Fixed
 - `:disconnect` event no longer sent when bot shuts down

## [0.2.6] - 2020-02-24
### Fixed
 - Bots are unable to disconnect properly
 - Bots fail to reconnect after spending time with internet unavailable

## Deprecated
 - `discljord.util/*enable-logging*`, prefer directly interacting with timbre
 - `discljord.util/set-logging-level!`, prefer directly interacting with timbre

## Upgraded
 - gniazdo version

## [0.2.5] - 2019-05-27
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

[Unreleased]: https://github.com/IGJoshua/discljord/compare/1.3.0..HEAD
[1.3.0]: https://github.com/IGJoshua/discljord/compare/1.2.3..1.3.0
[1.2.3]: https://github.com/IGJoshua/discljord/compare/1.2.2..1.2.3
[1.2.2]: https://github.com/IGJoshua/discljord/compare/1.2.1..1.2.2
[1.2.1]: https://github.com/IGJoshua/discljord/compare/1.2.0..1.2.1
[1.2.0]: https://github.com/IGJoshua/discljord/compare/1.1.1..1.2.0
[1.1.1]: https://github.com/IGJoshua/discljord/compare/1.1.0..1.1.1
[1.1.0]: https://github.com/IGJoshua/discljord/compare/1.0.0..1.1.0
[1.0.1]: https://github.com/IGJoshua/discljord/compare/1.0.0..1.0.1
[1.0.0]: https://github.com/IGJoshua/discljord/compare/0.2.9..1.0.0
[0.2.9]: https://github.com/IGJoshua/discljord/compare/0.2.8..0.2.9
[0.2.8]: https://github.com/IGJoshua/discljord/compare/0.2.7..0.2.8
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
