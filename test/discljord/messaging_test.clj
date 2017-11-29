(ns discljord.messaging-test
  (:require [discljord.messaging :refer :all]
            [clojure.test :as t]))

(t/deftest mentions
  (t/testing "Are user mentions properly created?"
    (t/is (= "<@1701>"
             (mention 1701)))))
