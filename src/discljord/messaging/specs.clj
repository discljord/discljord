(ns discljord.messaging.specs
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(s/def ::major-variable-type #{::ds/guild-id ::ds/channel-id ::ds/webhook-id})
(s/def ::major-variable-value ::ds/snowflake)
(s/def ::major-variable (s/keys :req [::major-variable-type
                                      ::major-variable-value]))

(s/def ::action keyword?)

(s/def ::endpoint (s/keys :req [::action]
                          :opt [::major-variable]))

(s/def ::rate (s/nilable number?))
(s/def ::remaining (s/nilable number?))
(s/def ::reset (s/nilable number?))
(s/def ::global (s/nilable boolean?))
(s/def ::rate-limit (s/keys :req [::rate ::remaining ::reset]
                            :opt [::global]))

(s/def ::endpoint-specific-rate-limits (s/map-of ::endpoint ::rate-limit))
(s/def ::global-rate-limit ::rate-limit)

(s/def ::rate-limits (s/keys :req [::endpoint-specific-rate-limits]
                             :opt [::global-rate-limit]))

(s/def ::process (s/keys :req [::rate-limits
                               ::ds/channel
                               ::ds/token]))

(s/def ::message (s/and string?
                        #(< (count %) 2000)))

(s/def ::delete-message-days (s/and int?
                                    (complement neg?)
                                    #(< % 8)))

(s/def ::reason string?)

(s/def ::user-agent string?)
(s/def ::tts boolean?)
