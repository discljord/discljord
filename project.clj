(defproject discljord "0.1.0-SNAPSHOT"
  :description "A Clojure library to allow the creation of Discord bots with a relatively high level of abstraction."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.2.0"]
                 [stylefruits/gniazdo "1.0.1"]
                 [org.clojure/core.async "0.3.442"]
                 [com.rpl/specter "1.0.5"]
                 [org.clojure/tools.logging "0.4.1"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[http-kit.fake "0.2.2"]
                                  [midje "1.9.2"]]}}
  :jvm-opts ["--add-modules" "java.xml.bind"])
