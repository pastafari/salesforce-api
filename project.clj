(defproject org.clojars.pastafari/salesforce-api "0.1.0"
  :description "A Salesforce API wrapper in Clojure"
  :url "http://github.com/pastafari"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http-kit "2.1.16"]
                 [org.clojure/data.json "0.2.5"
                  :exclusions [org.clojure/clojure]]])
