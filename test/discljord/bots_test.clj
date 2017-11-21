(ns discljord.bots-test
  (:require [discljord.bots :refer :all]
            [clojure.test :as t]
            [clojure.core.async :as a]))

(t/deftest message-processing
  (t/testing "Are messages conveyed through the message process?"
    (let [ch (a/chan 10)
          val {:event :test :data {:s "Value!"}}
          listener {:event :test :channel (a/chan 10)}]
      (a/>!! ch val)
      (start-message-proc ch [listener])
      (let [result (a/alts!! [(:channel listener) (a/timeout 100)])]
        (t/is (= val
                 (first result)))))))
