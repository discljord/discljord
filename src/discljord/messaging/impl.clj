(ns discljord.messaging.impl)

(def ^:private rate-limits (atom {}))

;; Create a map of channels for different major endpoints.
;; When a request needs to be made, send it over the channel,
;; which will check on the rate limit for that particular endpoint.

;; NOTE(Joshua): Ask the discord API chat if each endpoint that has a global
;;               rate limit all interact with the same rate limit, or if the global
;;               limit is just a default, and is still applied separately to each endpoint
