(defproject com.github.discljord/discljord "1.3.1"
  :description " A Clojure wrapper library for the Discord API, with full API coverage (except voice, for now), and high scalability."
  :url "https://github.com/IGJoshua/discljord"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/tools.logging "1.2.4"]
                 [http-kit/http-kit "2.5.3"]
                 [stylefruits/gniazdo "1.2.1"]]
  :target-path "target/%s"
  :jar-name "discljord-%s.jar"
  :deploy-branches ["master" "release" "hotfix"]
  :profiles {:dev {:dependencies [[http-kit.fake/http-kit.fake "0.2.2"]
                                  [ch.qos.logback/logback-classic "1.2.11"]]
                   :plugins [[lein-codox "0.10.8"]]
                   :exclusions [http-kit]
                   :jvm-opts ["--add-opens" "java.base/java.lang=ALL-UNNAMED" "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]}})
