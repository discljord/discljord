(ns discljord.messaging.impl
  "Implementation namespace for `discljord.messaging`."
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [com.rpl.specter :refer [ATOM keypath select-first transform]]
   [discljord.http :refer [api-url]]
   [discljord.messaging.specs :as ms]
   [discljord.specs :as ds]
   [discljord.util :refer [bot-token clean-json-input *enable-logging*]]
   [org.httpkit.client :as http]
   [taoensso.timbre :as log])
  (:import
   (java.util Date)))

;; NOTE: Rate limits for emoji don't follow the same conventions, and are handled per-guild
;;       as a result, expect lots of 429's

(defmulti dispatch-http
  "Takes a process and endpoint, and dispatches an http request.
  Must return the response object from the call to allow the runtime
  to update the rate limit."
  (fn [process endpoint data]
    (::ms/action endpoint)))
(s/fdef dispatch-http
  :args (s/cat :process (ds/atom-of? ::ms/process)
               :endpoint ::ms/endpoint
               :data (s/coll-of any?)))

(defn auth-headers
  [token user-agent]
  {"Authorization" (bot-token token)
   "User-Agent"
   (str "DiscordBot ("
        "https://github.com/IGJoshua/discljord"
        ", "
        "0.2.8"
        ") "
        user-agent)
   "Content-Type" "application/json"})

(defmacro defdispatch
  ""
  [endpoint-name [major-var & params] [& opts] opts-sym
   method status-sym body-sym url-str
   method-params promise-val]
  `(defmethod dispatch-http ~endpoint-name
     [process# endpoint# [prom# ~@params & {user-agent# :user-agent audit-reason# :audit-reason
                                            :keys [~@opts] :as opts#}]]
     (let [~opts-sym (dissoc opts# :user-agent)
           ~major-var (-> endpoint#
                          ::ms/major-variable
                          ::ms/major-variable-value)
           headers# (auth-headers (::ds/token @process#) user-agent#)
           headers# (if audit-reason#
                      (assoc headers# "X-Audit-Log-Reason" (http/url-encode audit-reason#))
                      headers#)
           request-params# (merge-with merge
                                       ~method-params
                                       {:headers headers#})
           ~'_ (log/trace "Making request to" ~major-var "with params" request-params#)
           response# @(~(symbol "org.httpkit.client" (name method))
                       (api-url ~url-str)
                       request-params#)
           ~'_ (log/trace "Response:" response#)
           ~status-sym (:status response#)
           ~body-sym (:body response#)]
       (deliver prom# ~promise-val)
       response#)))

(defn ^:private json-body
  [body]
  (if-let [json-msg (json/read-str body)]
    (clean-json-input json-msg)
    nil))

(defn ^:private conform-to-json
  [opts]
  (into {}
        (map (fn [[k v]]
               [(keyword (str/replace (name k) #"-" "_")) v]))
        opts))

(defdispatch :get-guild-audit-log
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/audit-logs")
  {}
  (json-body body))

(defdispatch :get-channel
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id)
  {}
  (json-body body))

(defdispatch :modify-channel
  [channel-id] [] opts :patch _ body
  (str "/channels/" channel-id)
  {:body (json/write-str (conform-to-json opts))}
  (json-body body))

(defdispatch :delete-channel
  [channel-id] [] _ :delete _ body
  (str "/channels/" channel-id)
  {}
  (json-body body))

(defdispatch :get-channel-messages
  [channel-id] [] opts :get _ body
  (str "/channels/" channel-id "/messages")
  {:query-params opts}
  (json-body body))

(defdispatch :get-channel-message
  [channel-id message-id] [] _ :get _ body
  (str "/channels/" channel-id "/messages/" message-id)
  {}
  (json-body body))

(defmethod dispatch-http :create-message
  [process endpoint [prom & {:keys [^java.io.File file user-agent attachments allowed-mentions stream] :as opts}]]
  (let [channel-id (-> endpoint
                       ::ms/major-variable
                       ::ms/major-variable-value)
        payload (conform-to-json (dissoc opts :user-agent :file :attachments :stream))
        payload-json (json/write-str payload)
        multipart [{:name "payload_json" :content payload-json}]
        multipart (if file
                    (conj multipart {:name "file" :content file :filename (.getName file)})
                    multipart)
        multipart (if attachments
                    (into multipart (for [attachment attachments]
                                      {:name "attachment"
                                       :content attachment
                                       :filename (.getName attachment)}))
                    multipart)
        multipart (if stream
                    (conj multipart (assoc stream :name "file"))
                    multipart)
        response @(http/post (api-url (str "/channels/" channel-id "/messages"))
                             {:headers (assoc (auth-headers (::ds/token @process) user-agent)
                                              "Content-Type" "multipart/form-data")
                              :multipart multipart})]
    (deliver prom (json-body (:body response)))
    response))

(defdispatch :create-reaction
  [channel-id message-id emoji] [] _ :put status _
  (str "/channels/" channel-id "/messages/" message-id "/reactions/" emoji "/@me")
  {}
  (= status 204))

(defdispatch :delete-own-reaction
  [channel-id message-id emoji] [] _ :delete status _
  (str "/channels/" channel-id "/messages/" message-id "/reactions/" emoji "/@me")
  {}
  (= status 204))

(defdispatch :delete-user-reaction
  [channel-id message-id emoji user-id] [] _ :delete status _
  (str "/channels/" channel-id "/messages/" message-id "/reactions/" emoji "/" user-id)
  {}
  (= status 204))

(defdispatch :get-reactions
  [channel-id message-id emoji] [] opts :get _ body
  (str "/channels/" channel-id "/messages/" message-id "/reactions/" emoji)
  {:query-params opts}
  (json-body body))

(defdispatch :delete-all-reactions
  [channel-id message-id] [] _ :delete status _
  (str "/channels/" channel-id "/messages/" message-id "/reactions")
  {}
  (= status 204))

(defdispatch :edit-message
  [channel-id message-id] [] opts :patch _ body
  (str "/channels/" channel-id "/messages/" message-id)
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :delete-message
  [channel-id message-id] [] _ :delete status _
  (str "/channels/" channel-id "/messages/" message-id)
  {}
  (= status 204))

(defdispatch :bulk-delete-messages
  [channel-id messages] [] _ :post status _
  (str "/channels/" channel-id "/messages/bulk-delete")
  {:body (json/write-str {:messages messages})}
  (= status 204))

(defdispatch :edit-channel-permissions
  [channel-id overwrite-id allow deny type] [] _ :put status _
  (str "/channels/" channel-id "/permissions/" overwrite-id)
  {:query-params {:allow allow
                  :deny deny
                  :type type}}
  (= status 204))

(defdispatch :get-channel-invites
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id "/invites")
  {}
  (json-body body))

(defdispatch :create-channel-invite
  [channel-id] [] opts :post _ body
  (str "/channels/" channel-id "/invites")
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :delete-channel-permission
  [channel-id overwrite-id] [] _ :delete status _
  (str "/channels/" channel-id "/permissions/" overwrite-id)
  {}
  (= status 204))

(defdispatch :trigger-typing-indicator
  [channel-id] [] _ :post status _
  (str "/channels/" channel-id "/typing")
  {}
  (= status 204))

(defdispatch :get-pinned-messages
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id "/pins")
  {}
  (json-body body))

(defdispatch :add-pinned-channel-message
  [channel-id message-id] [] _ :put status _
  (str "/channels/" channel-id "/pins/" message-id)
  {}
  (= status 204))

(defdispatch :delete-pinned-channel-message
  [channel-id message-id] [] _ :delete status _
  (str "/channels/" channel-id "/pins/" message-id)
  {}
  (= status 204))

(defdispatch :group-dm-add-recipient
  [channel-id user-id] [] opts :put _ _
  (str "/channels/" channel-id "/recipients/" user-id)
  {:query-params (conform-to-json opts)}
  nil)

(defdispatch :group-dm-remove-recipient
  [channel-id user-id] [] _ :delete _ _
  (str "/channels/" channel-id "/recipients/" user-id)
  {}
  nil)

(defdispatch :list-guild-emojis
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/emojis")
  {}
  (json-body body))

(defdispatch :get-guild-emoji
  [guild-id emoji] [] _ :get _ body
  (str "/guilds/" guild-id "/emojis/" emoji)
  {}
  (json-body body))

(defdispatch :create-guild-emoji
  [guild-id name image roles] [] _ :post _ body
  (str "/guilds/" guild-id "/emojis")
  {:body (json/write-str {:name name
                          :image image
                          :roles roles})}
  (json-body body))

(defdispatch :modify-guild-emoji
  [guild-id emoji name roles] [] _ :patch _ body
  (str "/guilds/" guild-id "/emojis/" emoji)
  {:body (json/write-str {:name name
                          :roles roles})}
  (json-body body))

(defdispatch :delete-guild-emoji
  [guild-id emoji] [] _ :delete status _
  (str "/guilds/" guild-id "/emojis/" emoji)
  {}
  (= status 204))

(defdispatch :create-guild
  [_ name region icon verification-level
   default-message-notifications
   explicit-content-filter role-objects
   channels] [] _ :post _ body
  (str "/guilds")
  {:body (json/write-str {:name name
                          :region region
                          :icon icon
                          :verification-level verification-level
                          :default-message-notifications default-message-notifications
                          :explicit-content-filter explicit-content-filter
                          :roles role-objects
                          :channels channels})}
  (json-body body))

(defdispatch :get-guild
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id)
  {}
  (json-body body))

(defdispatch :modify-guild
  [guild-id] [reason] opts :patch _ body
  (str "/guilds/" guild-id)
  (let [req {:body (json/write-str (conform-to-json (dissoc opts :reason)))}]
    (if reason
      (assoc req
             :headers {"X-Audit-Log-Reason" (or reason "")})
      req))
  (json-body body))

(defdispatch :delete-guild
  [guild-id] [] _ :delete status _
  (str "/guilds/" guild-id)
  {}
  (= status 204))

(defdispatch :get-guild-channels
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/channels")
  {}
  (json-body body))

(defdispatch :create-guild-channel
  [guild-id name] [] opts :post _ body
  (str "/guilds/" guild-id "/channels")
  {:body (json/write-str (conform-to-json (assoc opts :name name)))}
  (json-body body))

(defdispatch :modify-guild-channel-positions
  [guild-id channels] [] _ :patch status _
  (str "/guilds/" guild-id "/channels")
  {:body (json/write-str channels)}
  (= status 204))

(defdispatch :get-guild-member
  [guild-id user-id] [] _ :get _ body
  (str "/guilds/" guild-id "/members/" user-id)
  {}
  (json-body body))

(defdispatch :list-guild-members
  [guild-id] [] opts :get _ body
  (str "/guilds/" guild-id "/members")
  {:query-params opts}
  (json-body body))

(defdispatch :add-guild-member
  [guild-id user-id access-token] [] opts :put status body
  (str "/guilds/" guild-id "/members/" user-id)
  {:query-params (assoc opts :access_token access-token)}
  (if (= status 204)
    :already-member
    (json-body body)))

(defdispatch :modify-guild-member
  [guild-id user-id] [] opts :patch status _
  (str "/guilds/" guild-id "/members/" user-id)
  {:body (json/write-str (conform-to-json opts))}
  (= status 204))

(defdispatch :modify-current-user-nick
  [guild-id nick] [] _ :patch status body
  (str "/guilds/" guild-id "/members/@me/nick")
  {:body (json/write-str {:nick nick})}
  (when (= status 200)
    body))

(defdispatch :add-guild-member-role
  [guild-id user-id role-id] [] _ :put status _
  (str "/guilds/" guild-id "/members/" user-id "/roles/" role-id)
  {}
  (= status 204))

(defdispatch :remove-guild-member-role
  [guild-id user-id role-id] [] _ :delete status _
  (str "/guilds/" guild-id "/members/" user-id "/roles/" role-id)
  {}
  (= status 204))

(defdispatch :remove-guild-member
  [guild-id user-id] [] _ :delete status _
  (str "/guilds/" guild-id "/members/" user-id)
  {}
  (= status 204))

(defdispatch :get-guild-bans
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/bans")
  {}
  (json-body body))

(defdispatch :get-guild-ban
  [guild-id user-id] [] _ :get _ body
  (str "/guilds/" guild-id "/bans/" user-id)
  {}
  (json-body body))

(defdispatch :create-guild-ban
  [guild-id user-id] [delete-message-days reason] opts :put status _
  (str "/guilds/" guild-id "/bans/" user-id)
  {:query-params (conform-to-json opts)}
  (= status 204))

(defdispatch :remove-guild-ban
  [guild-id user-id] [] _ :delete status _
  (str "/guilds/" guild-id "/bans/" user-id)
  {}
  (= status 204))

(defdispatch :get-guild-roles
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/roles")
  {}
  (json-body body))

(defdispatch :create-guild-role
  [guild-id] [] opts :post _ body
  (str "/guilds/" guild-id "/roles")
  {:body (json/write-str (conform-to-json opts))}
  (json-body body))

(defdispatch :modify-guild-role-positions
  [guild-id roles] [] _ :patch _ body
  (str "/guilds/" guild-id "/roles")
  {:body (json/write-str roles)}
  (json-body body))

(defdispatch :modify-guild-role
  [guild-id role-id] [] opts :patch _ body
  (str "/guilds/" guild-id "/roles/" role-id)
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :delete-guild-role
  [guild-id role-id] [] _ :delete status _
  (str "/guilds/" guild-id "/roles/" role-id)
  {}
  (= status 204))

(defdispatch :get-guild-prune-count
  [guild-id] [] opts :get _ body
  (str "/guilds/" guild-id "/prune")
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :begin-guild-prune
  [guild-id days compute-prune-count] [] _ :post _ body
  (str "/guilds/" guild-id "/prune")
  {:body (json/write-str {:days days
                          :compute_prune_count compute-prune-count})}
  (json-body body))

(defdispatch :get-guild-voice-regions
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/regions")
  {}
  (json-body body))

(defdispatch :get-guild-invites
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/invites")
  {}
  (json-body body))

(defdispatch :get-guild-integrations
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/integrations")
  {}
  (json-body body))

(defdispatch :create-guild-integration
  [guild-id type id] [] _ :post status _
  (str "/guilds/" guild-id "/integrations")
  {:body (json/write-str {:type type
                          :id id})}
  (= status 204))

(defdispatch :modify-guild-integration
  [guild-id integration-id expire-behavior expire-grace-period enable-emoticons] [] _ :patch status _
  (str "/guilds/" guild-id "/integrations/" integration-id)
  {:body (json/write-str {:expire_behavior expire-behavior
                          :expire_grace_period expire-grace-period
                          :enable_emoticons enable-emoticons})}
  (= status 204))

(defdispatch :delete-guild-integration
  [guild-id integration-id] [] _ :delete status _
  (str "/guilds/" guild-id "/integrations")
  {}
  (= status 204))

(defdispatch :sync-guild-integration
  [guild-id integration-id] [] _ :post status _
  (str "/guilds/" guild-id "/integrations/" integration-id "/sync")
  {}
  (= status 204))

(defdispatch :get-guild-embed
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/embed")
  {}
  (json-body body))

(defdispatch :modify-guild-embed
  [guild-id embed] [] _ :patch _ body
  (str "/guilds/" guild-id "/embed")
  {:body (json/write-str embed)}
  (json-body body))

(defdispatch :get-guild-vanity-url
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/vanity-url")
  {}
  (json-body body))

(defdispatch :get-guild-widget-image
  [guild-id] [] opts :get _ body
  (str "/guilds/" guild-id "/widget.png")
  {:query-params (:style opts)
   :body (json/write-str (dissoc opts :style))}
  (json-body body))

(defdispatch :get-invite
  [_ invite-code] [] opts :get _ body
  (str "/invites/" invite-code)
  {:query-params (conform-to-json opts)}
  (json-body body))

(defdispatch :delete-invite
  [_ invite-code] [] _ :delete _ body
  (str "/invites/" invite-code)
  {}
  (json-body body))

(defdispatch :get-current-user
  [_] [] _ :get _ body
  "/users/@me"
  {}
  (json-body body))

(defdispatch :get-user
  [_ user-id] [] _ :get _ body
  (str "/users/" user-id)
  {}
  (json-body body))

(defdispatch :modify-current-user
  [_] [] opts :patch _ body
  "/users/@me"
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :get-current-user-guilds
  [_] [] opts :get _ body
  "/users/@me/guilds"
  {:query-params opts}
  (json-body body))

(defdispatch :leave-guild
  [_ guild-id] [] _ :delete status _
  (str "/users/@me/guilds/" guild-id)
  {}
  (= status 204))

(defdispatch :get-user-dms
  [_] [] _ :get _ body
  "/users/@me/channels"
  {}
  (json-body body))

(defdispatch :create-dm
  [_ user-id] [] _ :post _ body
  "/users/@me/channels"
  {:body (json/write-str {:recipient_id user-id})}
  (json-body body))

(defdispatch :create-group-dm
  [_ access-tokens nicks] [] _ :post _ body
  "/users/@me/channels"
  {:body (json/write-str {:access_tokens access-tokens
                          :nicks nicks})}
  (json-body body))

(defdispatch :get-user-connections
  [_] [] _ :get _ body
  "/users/@me/connections"
  {}
  (json-body body))

(defdispatch :list-voice-regions
  [_] [] _ :get _ body
  "/voice/regions"
  {}
  (json-body body))

(defdispatch :create-webhook
  [channel-id name] [avatar] _ :post _ body
  (str "/channels/" channel-id "/webhooks")
  {:body (json/write-str {:name name
                          :avatar avatar})}
  (json-body body))

(defdispatch :get-channel-webhooks
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id "/webhooks")
  {}
  (json-body body))

(defdispatch :get-guild-webhooks
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/webhooks")
  {}
  (json-body body))

(defdispatch :get-webhook
  [webhook-id] [] _ :get _ body
  (str "/webhooks/" webhook-id)
  {}
  (json-body body))

(defdispatch :get-webhook-with-token
  [webhook-id webhook-token] [] _ :get _ body
  (str "/webhooks/" webhook-id "/" webhook-token)
  {}
  (json-body body))

(defdispatch :modify-webhook
  [webhook-id] [] opts :patch _ body
  (str "/webhooks/" webhook-id)
  {:body (json/write-str (conform-to-json opts))}
  (json-body body))

(defdispatch :modify-webhook-with-token
  [webhook-id webhook-token] [] opts :patch _ body
  (str "/webhooks/" webhook-id "/" webhook-token)
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :delete-webhook
  [webhook-id] [] _ :delete status _
  (str "/webhooks/" webhook-id)
  {}
  (= status 204))

(defdispatch :delete-webhook-with-token
  [webhook-id webhook-token] [] _ :delete status _
  (str "/webhooks/" webhook-id "/" webhook-token)
  {}
  (= status 204))

(defmethod dispatch-http :execute-webhook
  [process endpoint [prom webhook-token & {:keys [^java.io.File file user-agent wait] :as opts
                                           :or {wait false}}]]
  (let [webhook-id (-> endpoint
                       ::ms/major-variable
                       ::ms/major-variable-value)
        payload (conform-to-json (dissoc opts :user-agent :file))
        payload-json (json/write-str payload)
        multipart [{:name "payload_json" :content payload-json}]
        multipart (if file
                    (conj multipart {:name "file" :content file :filename (.getName file)})
                    multipart)
        response @(http/post (api-url (str "/webhooks/" webhook-id "/" webhook-token))
                             {:query-params {:wait wait}
                              :headers (assoc (auth-headers (::ds/token @process) user-agent)
                                              "Content-Type" "multipart/form-data")
                              :multipart multipart})]
    (deliver prom (if (= (:status response) 200)
                    (json-body (:body response))
                    (= (:status response) 204)))
    response))

(defn rate-limited?
  "Takes a process and an endpoint and checks to see if the
  process is currently rate limited."
  [process endpoint]
  (let [specific-limit (select-first [::ms/rate-limits
                                      ::ms/endpoint-specific-rate-limits
                                      (keypath endpoint)]
                                     process)
        global-limit (select-first [::ms/rate-limits
                                    ::ms/global-rate-limit]
                                   process)
        remaining (or (::ms/remaining specific-limit)
                      (::ms/remaining global-limit))
        reset (or (::ms/reset specific-limit)
                  (::ms/reset global-limit))
        time (System/currentTimeMillis)]
    (and remaining
         (<= remaining 0)
         reset
         (< time reset))))
(s/fdef rate-limited?
  :args (s/cat :process ::ms/process
               :endpoint ::ms/endpoint)
  :ret boolean?)

(defn update-rate-limit
  "Takes a rate-limit and a response and returns an updated rate-limit.

  If a rate limit headers are included in the response, then the rate
  limit is updated to them, otherwise the existing rate limit is used,
  but the remaining limit is decremented."
  [rate-limit response]
  (let [headers (:headers response)
        rate (:x-ratelimit-limit headers)
        rate (if rate
               (Long. rate)
               (or (::ms/rate rate-limit)
                   5))
        remaining (:x-ratelimit-remaining headers)
        remaining (if remaining
                    (Long. remaining)
                    (dec (or (::ms/remaining rate-limit)
                             5)))
        date (when (:date headers)
               (Date/parse (:date headers)))
        reset (:x-ratelimit-reset headers)
        reset (if reset
                (* (Long. reset) 1000)
                (or (::ms/reset rate-limit)
                    0))
        reset (if date
                (+ (- reset date) (System/currentTimeMillis))
                reset)
        global-str (:x-ratelimit-global headers)
        global (if global-str
                 (Boolean. global-str)
                 (::ms/global rate-limit ::not-found))
        new-rate-limit {::ms/rate rate
                        ::ms/remaining remaining
                        ::ms/reset reset}
        new-rate-limit (if-not (= global ::not-found)
                         (assoc new-rate-limit ::ms/global global)
                         new-rate-limit)]
    new-rate-limit))
(s/fdef update-rate-limit
  :args (s/cat :rate-limit (s/nilable ::ms/rate-limit)
               :response (s/keys :req-un [::headers])))

(defn start!
  "Takes a token for a bot and returns a channel to communicate with the
  message sending process."
  [token]
  (let [process (atom {::ms/rate-limits {::ms/endpoint-specific-rate-limits {}}
                       ::ds/channel (a/chan 1000)
                       ::ds/token token})]
    (a/go-loop []
      (let [[endpoint & event-data :as event] (a/<! (::ds/channel @process))]
        (when-not (= endpoint :disconnect)
          (if (rate-limited? @process endpoint)
            (a/>! (::ds/channel @process) event)
            (when-let [response (a/<! (a/thread (try (dispatch-http process endpoint event-data)
                                                     (catch Exception e
                                                       (when *enable-logging*
                                                         (log/error e "Exception in dispatch-http"))
                                                       nil))))]
              (transform [ATOM
                          ::ms/rate-limits
                          (if (select-first [:headers :x-ratelimit-global] response)
                            ::ms/global-rate-limit
                            ::ms/endpoint-specific-rate-limits)
                          (keypath endpoint)]
                         #(update-rate-limit % response)
                         process)
              (when (= (:status response)
                       429)
                ;; This shouldn't happen for anything but emoji stuff, so this shouldn't happen
                (when *enable-logging*
                  (log/info "Bot triggered rate limit response."))
                ;; Resend the event to dispatch, hopefully this time not brekaing the rate limit
                (a/>! (::ds/channel @process) event))))
          (recur))))
    (::ds/channel @process)))
(s/fdef start!
  :args (s/cat :token ::ds/token)
  :ret ::ds/channel)

(defn stop!
  "Takes the channel returned from start! and stops the messaging process."
  [channel]
  (a/put! channel [:disconnect]))
(s/fdef stop!
  :args (s/cat :channel ::ds/channel)
  :ret nil?)
