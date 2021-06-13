(ns discljord.messaging.impl
  "Implementation namespace for `discljord.messaging`."
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [discljord.http :refer [api-url]]
   [discljord.messaging.specs :as ms]
   [discljord.specs :as ds]
   [discljord.util :refer [bot-token clean-json-input]]
   [org.httpkit.client :as http]
   [clojure.tools.logging :as log])
  (:import
   (java.io File)
   (java.net URLEncoder)
   (java.util Date)))

;; NOTE: Rate limits for emoji don't follow the same conventions, and are handled per-guild
;;       as a result, expect lots of 429's

(defmulti dispatch-http
  "Takes a process and endpoint, and dispatches an http request.
  Must return the response object from the call to allow the runtime
  to update the rate limit."
  (fn [token endpoint data]
    (::ms/action endpoint)))
(s/fdef dispatch-http
  :args (s/cat :token string?
               :endpoint ::ms/endpoint
               :data (s/coll-of any?)))

(defn auth-headers
  [token user-agent]
  {"Authorization" token
   "User-Agent"
   (str "DiscordBot ("
        "https://github.com/IGJoshua/discljord"
        ", "
        "1.3.0-SNAPSHOT"
        ") "
        user-agent)
   "Content-Type" "application/json"})

(defmacro defdispatch
  "Defines a dispatch method for the the endpoint with `endpoint-name`.

  In the parameter declarations `[major-var & params]`, the major variable always comes first,
  no matter at which position it is in the actual endpoint definition.
  Even if the corresponding endpoint does not have a major variable,
  a symbol for it needs to be provided in the parameters (typically `_` since it will always be bound to `nil`).

  `opts` are the optional parameters as specified in the endpoint definition,
  `method` is the http method used (`:get`, `:post`, ...),
  `status-sym` is a symbol that will be bound to the response code (http status),
  `body-sym` is a symbol that will be bound to the response body (as a string),
  `url-string` is the url of the endpoint (its expression can depend on the arguments),
  `method-params` is a map describing what to send in the request,
  `promise-val` is the expression that will be in the promise returned to the user of the endpoint."
  [endpoint-name [major-var & params] [& opts] opts-sym
   method status-sym body-sym url-str
   method-params promise-val]
  `(defmethod dispatch-http ~endpoint-name
     [token# endpoint# [prom# ~@params & {user-agent# :user-agent audit-reason# :audit-reason
                                            :keys [~@opts] :as opts#}]]
     (let [~opts-sym (dissoc opts# :user-agent)
           ~major-var (-> endpoint#
                          ::ms/major-variable
                          ::ms/major-variable-value)
           headers# (auth-headers token# user-agent#)
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
       (when-not (= ~status-sym 429)
         (let [prom-val# ~promise-val]
           (if (some? prom-val#)
             (a/>!! prom# prom-val#)
             (a/close! prom#))))
       response#)))

(defn ^:private json-body
  [body]
  (when-let [json-msg (json/read-str body)]
    (clean-json-input json-msg)))

(defn ^:private conform-to-json
  [opts]
  (into {}
        (map (fn [[k v]]
               [(keyword (str/replace (name k) #"-" "_")) v]))
        opts))

(defmacro ^:private def-message-dispatch
  "Creates a dispatch definition based on the common pattern of creating,
  editing and updating some sort of message"
  [name params method url]
  (let [[opts status body] (repeatedly gensym)
        delete? (= method :delete)]
    `(defdispatch ~name
       ~params [] ~opts ~method ~status ~body
       ~url
       ~(if delete? `{} `{:body (json/write-str ~opts)})
       ~(if delete? `(= ~status 204) `(json-body ~body)))))

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

(defn- send-message! [token url prom multipart always-expect-content? {:keys [wait ^File file user-agent allowed-mentions stream message-reference] :as opts}]
  (let [payload (-> opts
                    (dissoc :user-agent :file :stream)
                    conform-to-json
                    json/write-str)
        multipart (cond-> (conj multipart {:name "payload_json" :content payload})
                    file (conj {:name "file" :content file :filename (.getName file)})
                    stream (conj (assoc stream :name "file")))
        response @(http/post (api-url url)
                             {:query-params (when (some? wait) {:wait wait})
                              :headers (assoc (auth-headers token user-agent)
                                              "Content-Type" "multipart/form-data")
                              :multipart multipart})
        status (:status response)
        raw-body (:body response)
        body (if (or always-expect-content? (= status 200))
               (cond->> (json-body raw-body)
                 (not= 2 (quot status 100)) (ex-info ""))
               (= status 204))]
    (when-not (= status 429)
      (if (some? body)
        (a/>!! prom body)
        (a/close! prom)))
    response))

(defmethod dispatch-http :create-message
  [token endpoint [prom & {:keys [attachments stream] :as opts}]]
  (let [channel-id (-> endpoint
                       ::ms/major-variable
                       ::ms/major-variable-value)
        multipart (cond-> []
                    attachments (into (for [attachment attachments]
                                        {:name "attachment"
                                         :content attachment
                                         :filename (.getName ^File attachment)})))]
    (send-message! token (str "/channels/" channel-id "/messages")
                   prom multipart true (dissoc opts :attachments))))

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

(defdispatch :delete-all-reactions-for-emoji
  [channel-id message-id emoji] [] _ :delete status _
  (str "/channels/" channel-id "/messages/" message-id "/reactions/" (URLEncoder/encode emoji))
  {}
  (= status 204))


(def-message-dispatch :edit-message
  [channel-id message-id] :patch
  (str "/channels/" channel-id "/messages/" message-id))

(def-message-dispatch :delete-message
  [channel-id message-id] :delete
  (str "/channels/" channel-id "/messages/" message-id))

(defdispatch :bulk-delete-messages
  [channel-id messages] [] _ :post status _
  (str "/channels/" channel-id "/messages/bulk-delete")
  {:body (json/write-str {:messages messages})}
  (= status 204))

(defdispatch :edit-channel-permissions
  [channel-id overwrite-id allow deny type] [] _ :put status _
  (str "/channels/" channel-id "/permissions/" overwrite-id)
  {:body (json/write-str {:allow allow
                          :deny deny
                          :type type})}
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
  {:body (json/write-str (conform-to-json opts))}
  nil)

(defdispatch :group-dm-remove-recipient
  [channel-id user-id] [] _ :delete _ _
  (str "/channels/" channel-id "/recipients/" user-id)
  {}
  nil)

(defdispatch :start-thread-with-message
  [channel-id message-id name auto-archive-duration] [] _ :post _ body
  (str "/channels/" channel-id "/messages/" message-id "/threads")
  {:body (json/write-str {:name name
                          :auto_archive_duration auto-archive-duration})}
  (json-body body))

(defdispatch :start-thread-without-message
  [channel-id name auto-archive-duration] [] _ :post _ body
  (str "/channels/" channel-id "/threads")
  {:body (json/write-str {:name name
                          :auto_archive_duration auto-archive-duration})}
  (json-body body))

(defdispatch :join-thread
  [channel-id] [] _ :put status _
  (str "/channels/" channel-id "/thread-members/@me")
  {}
  (= status 204))

(defdispatch :add-thread-member
  [channel-id user-id] [] _ :put status body
  (str "/channels/" channel-id "/thread-members/" user-id)
  {}
  (= status 204))

(defdispatch :leave-thread
  [channel-id] [] _ :delete status _
  (str "/channels/" channel-id "/thread-members/@me")
  {}
  (= status 204))

(defdispatch :remove-thread-member
  [channel-id user-id] [] _ :delete status _
  (str "/channels/" channel-id "/thread-members/" user-id)
  {}
  (= status 204))

(defdispatch :list-thread-members
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id "/thread-members")
  {}
  (json-body body))

(defdispatch :list-active-threads
  [channel-id] [] _ :get _ body
  (str "/channels/" channel-id "/threads/active")
  {}
  (json-body body))

(defdispatch :list-public-archived-threads
  [channel-id] [] opts :get _ body
  (str "/channels/" channel-id "/threads/archived/public")
  {:query-params opts}
  (json-body body))

(defdispatch :list-private-archived-threads
  [channel-id] [] opts :get _ body
  (str "/channels/" channel-id "/threads/archived/private")
  {:query-params opts}
  (json-body body))

(defdispatch :list-joined-private-archived-threads
  [channel-id] [] opts :get _ body
  (str "/channels/" channel-id "/users/@me/threads/archived/private")
  {:query-params opts}
  (json-body body))

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
  [guild-id] [] opts :get _ body
  (str "/guilds/" guild-id)
  {:query-params (conform-to-json opts)}
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
  {:body (json/write-str (conform-to-json opts))}
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

(defdispatch :get-guild-widget-settings
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/widget")
  {}
  (json-body body))

(defdispatch :modify-guild-widget
  [guild-id widget] [] _ :patch _ body
  (str "/guilds/" guild-id "/widget")
  {:body (json/write-str widget)}
  (json-body body))

(defdispatch :get-guild-widget
  [guild-id] [] _ :get _ body
  (str "/guilds/" guild-id "/widget.json")
  {}
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

(defn- webhook-url
  ([id token]
   (str "/webhooks/" id \/ token))
  ([id token message-id]
   (str (webhook-url id token) "/messages/" message-id)))

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
  (webhook-url webhook-id webhook-token)
  {}
  (json-body body))

(defdispatch :modify-webhook
  [webhook-id] [] opts :patch _ body
  (str "/webhooks/" webhook-id)
  {:body (json/write-str (conform-to-json opts))}
  (json-body body))

(defdispatch :modify-webhook-with-token
  [webhook-id webhook-token] [] opts :patch _ body
  (webhook-url webhook-id webhook-token)
  {:body (json/write-str opts)}
  (json-body body))

(defdispatch :delete-webhook
  [webhook-id] [] _ :delete status _
  (str "/webhooks/" webhook-id)
  {}
  (= status 204))

(defdispatch :delete-webhook-with-token
  [webhook-id webhook-token] [] _ :delete status _
  (webhook-url webhook-id webhook-token)
  {}
  (= status 204))

(defn- execute-webhook!
  [token webhook-id webhook-token prom always-expect-content? opts]
  (send-message! token (webhook-url webhook-id webhook-token)
                 prom [] always-expect-content? opts))

(defmethod dispatch-http :execute-webhook
  [token endpoint [prom webhook-token & {:as opts}]]
  (execute-webhook! token
                   (-> endpoint ::ms/major-variable ::ms/major-variable-value)
                   webhook-token prom false opts))

(defdispatch :get-webhook-message
  [webhook-id webhook-token message-id] [] _ :get _ body
  (webhook-url webhook-id webhook-token message-id)
  {}
  (json-body body))

(def-message-dispatch :edit-webhook-message
  [webhook-id webhook-token message-id] :patch
  (webhook-url webhook-id webhook-token message-id))

(def-message-dispatch :delete-webhook-message
  [webhook-id webhook-token message-id] :delete
  (webhook-url webhook-id webhook-token message-id))

(defn- command-params [name description options default-perm]
  {:body (json/write-str (cond-> {:name name
                                  :description description}
                                 options (assoc :options options)
                                 (some? default-perm) (assoc :default_permission default-perm)))})

(defn- global-cmd-url
  ([application-id] (str "/applications/" application-id "/commands"))
  ([application-id command-id] (str (global-cmd-url application-id) \/ command-id)))


(defdispatch :get-global-application-commands
  [_ application-id] [] _ :get _ body
  (global-cmd-url application-id)
  {}
  (json-body body))


(defdispatch :create-global-application-command
  [_ application-id name description] [options default-permission] _ :post _ body
  (global-cmd-url application-id)
  (command-params name description options default-permission)
  (json-body body))

(defdispatch :edit-global-application-command
  [_ application-id command-id name description] [options default-permission] _ :patch _ body
  (global-cmd-url application-id command-id)
  (command-params name description options default-permission)
  (json-body body))

(defdispatch :delete-global-application-command
  [_ application-id command-id] [] _ :delete status _
  (global-cmd-url application-id command-id)
  {}
  (= status 204))

(defdispatch :bulk-overwrite-global-application-commands
  [_ application-id commands] [] _ :put _ body
  (global-cmd-url application-id)
  {:body (json/write-str commands)}
  (json-body body))

(defn- guild-cmd-url
  ([application-id guild-id] (str "/applications/" application-id "/guilds/" guild-id "/commands"))
  ([application-id guild-id command-id] (str (guild-cmd-url application-id guild-id) \/ command-id)))

(defdispatch :get-guild-application-commands
  [_ application-id guild-id] [] _ :get _ body
  (guild-cmd-url application-id guild-id)
  {}
  (json-body body))

(defdispatch :create-guild-application-command
  [_ application-id guild-id name description] [options default-permission] _ :post _ body
  (guild-cmd-url application-id guild-id)
  (command-params name description options default-permission)
  (json-body body))

(defdispatch :edit-guild-application-command
  [_ application-id guild-id command-id name description] [options default-permission] _ :patch _ body
  (guild-cmd-url application-id guild-id command-id)
  (command-params name description options default-permission)
  (json-body body))

(defdispatch :delete-guild-application-command
  [_ application-id guild-id command-id] [] _ :delete status _
  (guild-cmd-url application-id guild-id command-id)
  {}
  (= status 204))

(defdispatch :bulk-overwrite-guild-application-commands
  [_ application-id guild-id commands] [] _ :put _ body
  (guild-cmd-url application-id guild-id)
  {:body (json/write-str commands)}
  (json-body body))

(defdispatch :get-guild-application-command-permissions
  [_ application-id guild-id] [] _ :get _ body
  (str (guild-cmd-url application-id guild-id) "/permissions")
  {}
  (json-body body))

(defdispatch :get-application-command-permissions
  [_ application-id guild-id command-id] [] _ :get _ body
  (str (guild-cmd-url application-id guild-id command-id) "/permissions")
  {}
  (json-body body))

(defdispatch :edit-application-command-permissions
  [_ application-id guild-id command-id permissions] [] _ :put _ body
  (str (guild-cmd-url application-id guild-id command-id) "/permissions")
  {:body (json/write-str {:permissions permissions})}
  (json-body body))

(defdispatch :batch-edit-application-command-permissions
  [_ application-id guild-id permissions] [] _ :put _ body
  (str (guild-cmd-url application-id guild-id) "/permissions")
  {:body (json/write-str permissions)}
  (json-body body))

(defmethod dispatch-http :create-interaction-response
  [token endpoint [prom interaction-id interaction-token type & {:as opts}]]
  (send-message! token (str "/interactions/" interaction-id \/ interaction-token "/callback") prom [] false (assoc opts :type type)))

(defdispatch :get-original-interaction-response
  [interaction-token application-id] [] _ :get _ body
  (webhook-url application-id interaction-token "@original")
  {}
  (json-body body))

(def-message-dispatch :edit-original-interaction-response
  [interaction-token application-id] :patch
  (webhook-url application-id interaction-token "@original"))

(def-message-dispatch :delete-original-interaction-response
  [_ application-id interaction-token] :delete
  (webhook-url application-id interaction-token "@original"))

(defmethod dispatch-http :create-followup-message
  [token endpoint [prom app-id & {:as opts}]]
  (execute-webhook! token app-id
                    (-> endpoint ::ms/major-variable ::ms/major-variable-value)
                    prom true opts))

(def-message-dispatch :edit-followup-message
  [interaction-token application-id message-id] :patch
  (webhook-url application-id interaction-token message-id))

(def-message-dispatch :delete-followup-message
  [interaction-token application-id message-id] :delete
  (webhook-url application-id interaction-token message-id))


(defdispatch :get-current-application-information
  [_] [] _ :get _ body
  "/oauth2/applications/@me"
  {}
  (json-body body))

(defn rate-limited?
  "Returns the number of millis until the limit expires, or nil if not limited"
  [rate-limit]
  (let [remaining (::ms/remaining rate-limit)
        reset (::ms/reset rate-limit)
        time (System/currentTimeMillis)]
    (when (and remaining
             (<= remaining 0)
             reset
             (< time reset))
      (- reset time))))
(s/fdef rate-limited?
  :args (s/cat :rate-limit ::ms/rate-limit)
  :ret (s/nilable nat-int?))

(defn update-rate-limit
  "Takes a rate-limit and a map of headers and returns an updated rate-limit.

  If rate limit headers are included in the map, then the rate limit is updated
  to them, otherwise the existing rate limit is used, but the remaining limit is
  decremented."
  [rate-limit headers]
  (let [rate (:x-ratelimit-limit headers)
        rate (if rate
               (Long. ^String rate)
               (or (::ms/rate rate-limit)
                   5))
        reset (:x-ratelimit-reset headers)
        reset (if reset
                (long (* (Double. ^String reset) 1000))
                (or (::ms/reset rate-limit)
                    0))
        reset (if (> (or (::ms/reset rate-limit) 0) reset)
                (::ms/reset rate-limit)
                reset)
        current-time (System/currentTimeMillis)
        remaining (:x-ratelimit-remaining headers)
        remaining (when remaining (Long. ^String remaining))
        remaining (let [old-rem (::ms/remaining rate-limit)]
                    (if-not remaining
                      (dec (if (> reset current-time)
                             (or old-rem 1)
                             rate))
                      (min remaining
                           (or (when (< current-time (or (::ms/reset rate-limit) 0))
                                 (dec old-rem))
                               Long/MAX_VALUE))))
        new-rate-limit {::ms/rate rate
                        ::ms/remaining remaining
                        ::ms/reset reset}]
    new-rate-limit))
(s/fdef update-rate-limit
  :args (s/cat :rate-limit (s/nilable ::ms/rate-limit)
               :headers map?)
  :ret ::ms/rate-limit)

(defn make-request!
  "Makes a request after waiting for the rate limit, retrying if necessary."
  [token rate-limits global-limit endpoint event-data bucket]
  (letfn [(make-request [endpoint event-data bucket]
            (log/trace "Making request to endpoint" endpoint)
            (when bucket
              (log/trace "Had bucket, checking rate limit")
              (loop [limit (if-not (= ::global-limit bucket)
                             (get-in @rate-limits [bucket (::ms/major-variable endpoint)])
                             @global-limit)
                     reset-in (rate-limited? limit)]
                (log/trace "Got limit" limit)
                (when reset-in
                  (log/trace "Got millis to reset in" reset-in)
                  (a/<!! (a/timeout reset-in))
                  (log/trace "Waited for limit, re-checking")
                  (recur (if-not (= ::global-limit bucket)
                           (get-in @rate-limits [bucket (::ms/major-variable endpoint)])
                           @global-limit)
                         (rate-limited? limit)))))
            (log/trace "Making request")
            (try (dispatch-http token endpoint event-data)
                 (catch Exception e
                   (log/error e "Exception in dispatch-http")
                   nil)))]
    (try
      (loop [response (make-request endpoint event-data bucket)
             bucket bucket]
        (log/trace "Got response from request" response)
        (if response
          (let [headers (:headers response)
                global (when-let [global (:x-ratelimit-global headers)]
                         (Boolean. ^String global))
                new-bucket (or (:x-ratelimit-bucket headers)
                               bucket)]
            ;; Update the rate limits
            (if global
              (swap! global-limit update-rate-limit headers)
              (when new-bucket
                (swap! rate-limits
                       update-in [new-bucket (::ms/major-variable endpoint)] #(update-rate-limit % headers))))
            (if-not (= 429 (:status response))
              (if global
                ::global-limit
                new-bucket)
              ;; If we got a 429, wait for the retry time and go again
              (let [retry-after (:retry-after (json-body (:body response)))]
                (log/warn "Got a 429 response to request" endpoint event-data "with response" response)
                (log/trace "Retrying after" retry-after "seconds")
                (a/<!! (a/timeout (long (* 1000 retry-after))))
                (recur (make-request endpoint event-data new-bucket)
                       new-bucket))))
          bucket))
      (catch Exception e
        (log/warn "Caught exception on agent" e)
        bucket))))
(s/fdef make-request!
  :args (s/cat :token ::ds/token
               :rate-limits ::ms/rate-limits
               :global-limit ::ms/global-limit
               :endpoint ::ms/endpoint
               :event-data any?
               :bucket string?)
  :ret string?)

(defn step-agent
  "Takes a process and an event, and runs the request, respecting rate limits"
  [process [endpoint & event-data :as event]]
  (log/trace "Stepping agent with process" process)
  (let [bucket (or (get-in process [::ms/endpoint-agents endpoint])
                   (agent nil))]
    (send-off bucket #(make-request! (::ds/token process)
                                     (::ms/rate-limits process)
                                     (::ms/global-limit process)
                                     endpoint event-data %))
    (assoc-in process [::ms/endpoint-agents endpoint] bucket)))
(s/fdef step-agent
  :args (s/cat :process ::ms/process
               :event (s/spec (s/cat :endpoint ::ms/endpoint
                                     :event-data (s/* any?)))))

(defn start!
  "Takes a token for a bot and returns a channel to communicate with the
  message sending process."
  [token]
  (log/info "Starting messaging process")
  (let [process {::ms/rate-limits (atom {})
                 ::ms/endpoint-agents {}
                 ::ds/channel (a/chan 1000)
                 ::ds/token (bot-token token)
                 ::ms/global-limit (atom nil)}]
    (a/go-loop [process process]
      (let [[action :as event] (a/<! (::ds/channel process))]
        (log/trace "Got event" event)
        (when-not (= action :disconnect)
          (recur (step-agent process event)))))
    (::ds/channel process)))
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
