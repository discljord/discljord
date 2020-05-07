# Contributing to discljord
This document outlines the ways that anyone can contribute to the development of discljord. It includes guidelines for creating issues, pull requests, as well as a description of the project architecture to assist developers in getting a foothold in the codebase.

## Issues
Issues are not intended for general support, but are rather intended for feature requests, bug reports, documentation requests, and future planning. If you have a general question about discljord's usage, feel free to contact me in the #clojure_discljord channel of the [Unofficial Discord API Server](https://discord.gg/discord-api).

### Feature Requests
Feature requests should state clearly what the desired functionality is, how it might impact the end-user API of discljord, and what the goal of such a feature might be. Optionally, they may also include sections on how such a feature might grow in the future.

#### Purely Functional Event Pump (Example Feature Request)
##### Desired Functionality
A new function within `discljord.events` which creates an event pump, much like the current one does, but which allows a user-created state map to be passed in, and the return value of the call will be the next state.

##### Rationale
The current event pump leaves something to be desired, in that all top-level functionality provided by the bot in the form of event handlers which rely on bot state must interact with some type of mutable reference. Many bots may benefit architecturally from having all their event handlers become functions of state, even if they aren't pure functions.

##### API Impact
Since this adds a new API which doesn't affect old ones, current consumers of the API will be unaffected. Users of this new API will be able to work in an environment much more closely resembling a functionally pure one.

##### Future Growth
Potential room for growth here would be to introduce a way of having side-effect resolvers which are separated from the event handlers themselves, acting much like re-frame's fx. These handlers would likely not be able to change the state of the bot on their own, but would be able to kick off asynchronous operations like sending messages with the messaging API of discljord, or sending delayed events back to the bot to be responded to.

### Bug Reports
Bug reports should include a description of the bug, as well as code which can consistently reproduce it. If the bug cannot be consistently reproduced, then a detailed description of the events surrounding the bug would be appreciated, and a log file including discljord's logs from the time of the bug will be required, ideally at a trace logging level, but the default is good if the bug could not be reproduced under trace debugging levels.

When providing code which can reproduce a bug, be careful to ensure that no bot tokens are included in the code or the resources provided with it. Such code can either be entered within a code block in github if it is short, or a link to a repository with ideally a minimum reproduction case, or the full bot's code if it cannot be tracked down to a minimum reproduction case.

### Documentation Requests
Requests for documentation should include a short description of what has failed to be documented correctly or which needs to be updated; a note to say if said documentation should be a part of the codox documentation (which is pulled from all the docstrings throughout the project), code comments, or the project wiki; and if possible, a description of the reason for the request (e.g. unclear documentation, no documentation for a particular feature, current documentation is out of date, etc.).

## Pull Requests
This repository follows a fairly strict branching strategy, very similar to [git flow](https://nvie.com/posts/a-successful-git-branching-model/), with the main caveat being a strict policy against fast-forward merges. Any new work, bug fixes, documentation changes, etc., need to be on their own branch, named appropriately, ether with a straight `kebab-case` name if it's obvious, or in the form `<type-of-work>/<short-description>`. These branches need to be made off of `develop`, and any PRs created from them need to be against `develop`.

Pull requests should have a clear and concise description of the functionality which is added by the PR, as well as link to any related issues. If a developer wants assistance with developing functionality, they can create a draft PR and I will be happy to coach them through development of the functionality. Draft PRs should always include a checklist of functionality which must be included before the PR is considered "done" and can be reviewed and merged (although such a review step may be brief if I have assisted you in development).

## Project Architecture
TODO
