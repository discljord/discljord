(defproject org.suskalo/discljord "0.2.6-SNAPSHOT"
  :description " A Clojure wrapper library for the Discord API, with full API coverage (except voice, for now), and high scalability."
  :url "https://github.com/IGJoshua/discljord"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/data.json "0.2.6"]
                 [com.taoensso/timbre "4.10.0"]
                 [http-kit "2.4.0-alpha3"]
                 [stylefruits/gniazdo "1.1.3"]
                 [com.rpl/specter "1.1.2"]]
  :target-path "target/%s"
  :jar-name "discljord-%s.jar"
  :deploy-branches ["master" "release"]
  :profiles {:dev {:dependencies [[http-kit.fake "0.2.2"]]
                   :plugins [[lein-codox "0.10.6"]]
                   :exclusions [http-kit]}})
