(ns discljord.bots-test
  (:require [discljord.bots :refer :all]
            [clojure.test :as t]
            [org.httpkit.fake :as fake]
            [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as spec]
            [clojure.string :as str]))

(t/deftest sharding
  (t/testing "Shard ID's are correctly generated from guilds"
    (t/is (= (shard-id-from-guild {:url "blah" :shard-count 1}
                                    {:id 1237318975 :name "Blah" :state (atom {})})
             0))))

(t/deftest message-processing
  (t/testing "Are messages conveyed through the message process?"
    (let [ch (a/chan 10)
          val {:event-type :test :event-data {:s "Value!"}}
          listener {:event-type :test :event-channel (a/chan 10)}]
      (a/>!! ch val)
      (start-message-proc! ch [listener])
      (let [result (a/alts!! [(:event-channel listener) (a/timeout 100)])]
        (t/is (and (not (nil? result))
                   (= val
                      (first result)))))))
  (t/testing "Are messages taken off thier channels?"
    (let [test-atom (atom false)
          event-channel (a/chan)
          listeners [{:event-channel event-channel
                      :event-type :test
                      :event-handler (fn [{:keys [event-type event-data] :as event}]
                                       (reset! test-atom true))}]]
      (start-listeners! listeners)
      (a/>!! event-channel {:event-type :test :event-data nil})
      (Thread/sleep 10)
      (t/is @test-atom)
      (a/>!! event-channel {:event-type :disconnect :event-data nil}))))

(t/deftest bot-creation
  (t/testing "Are bots created properly?"
    (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                          (fn [orig-fn opts callback]
                            (if (= (:headers opts) {"Authorization" "Bot TEST_TOKEN"})
                              {:status 200 :body (json/write-str
                                                  {"url" "wss://fake.gateway.api/" "shards" 1})}
                              {:status 401
                               :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
      (let [bot (create-bot {:token "TEST_TOKEN"})]
        (spec/explain :discljord.bots/bot bot)
        (t/is (spec/valid? :discljord.bots/bot bot)))))
  (t/testing "Are bots with multiple shards valid?"
    (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                          (fn [orig-fn opts callback]
                            (if (= (:headers opts) {"Authorization" "Bot TEST_TOKEN"})
                              {:status 200 :body (json/write-str
                                                  {"url" "wss://fake.gateway.api/" "shards" 3})}
                              {:status 401
                               :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
      (let [bot (create-bot {:token "TEST_TOKEN"})]
        (spec/explain :discljord.bots/bot bot)
        (t/is (spec/valid? :discljord.bots/bot bot)))))
  (t/testing "Are bot tokens trimmed and prepended with \"Bot \"?"
    (fake/with-fake-http ["https://discordapp.com/api/gateway/bot?v=6&encoding=json"
                          (fn [orig-fn opts callback]
                            (if (= (:headers opts) {"Authorization" "Bot TEST_TOKEN"})
                              {:status 200 :body (json/write-str
                                                  {"url" "wss://fake.gateway.api/" "shards" 1})}
                              {:status 401
                               :body (json/write-str {"code" 0 "message" "401: Unauthorized"})}))]
      (let [tok "VALID_TOKEN"
            bot (create-bot {:token tok})]
        (spec/explain :discljord.bots/bot bot)
        (t/is (= (str "Bot " (str/trim tok))
                 (:token bot)))))))

(t/deftest bot-state
  (t/testing "Is state fetched properly from its map?"
    (let [state {:discljord.bots/internal-state {:guilds [{:name "blah" :id 1 :state {:test-chan :a}}]}
                 :test-global :b}]
      (t/is (= (@#'get-key state :test-global)
               :b))
      (t/is (:test-global-2 (@#'set-key state :test-global-2 true)))))
  (t/testing "Is state fetched from a bot?"
    (let [bot (create-bot {})]
      (t/is (= {}
               (state bot)))
      (t/is (:test-global (state+ bot :test-global true)))
      (t/is (= :test
               (:test-global (update-state bot :test-global (fn [_] :test)))))
      (t/is (not (:test-global (state- bot :test-global)))))))

(t/deftest test-guild-state
  (let [bot (create-bot {:guilds [{:name nil :id 0 :state {}}]})]
    (t/testing "Is guild state created properly?"
      (t/is (= {}
               (guild-state bot 0)))
      (t/is (= {:test true} (guild-state+ bot 0 :test true)))
      (t/is (= {:test false} (update-guild-state bot 0 :test not)))
      (t/is (= {} (guild-state- bot 0 :test))))))
