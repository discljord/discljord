(defproject discljord "0.1.0-SNAPSHOT"
  :description "A Clojure library to allow the creation of Discord bots with a relatively high level of abstraction."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.json "0.2.6"]
                 [http-kit "2.2.0"]
                 [http-kit.fake "0.2.2"]
                 [stylefruits/gniazdo "1.0.1"]
                 [org.eclipse.jetty.websocket/websocket-client "9.4.6.v20170531"]
                 [org.clojure/core.async "0.3.442"]
                 [com.rpl/specter "1.0.5"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :jvm-opts ["-Dorg.eclipse.jetty.websocket.client.LEVEL=WARN"
             "--add-modules" "java.xml.bind"])
