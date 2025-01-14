(ns logseq.tasks.dev
  "Tasks for general development. For desktop or mobile development see their
  namespaces"
  (:require [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [babashka.cli :as cli]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [clojure.data :as data]))

(defn lint
  "Run all lint tasks
  - clj-kondo lint
  - carve lint for unused vars
  - lint for vars that are too large
  - lint invalid translation entries
  - lint to ensure file and db graph remain separate"
  []
  (doseq [cmd ["clojure -M:clj-kondo --parallel --lint src --cache false"
               "bb lint:carve"
               "bb lint:large-vars"
               "bb lint:db-and-file-graphs-separate"
               "bb lang:validate-translations"
               "bb lint:ns-docstrings"]]
    (println cmd)
    (shell cmd)))


(defn gen-malli-kondo-config
  "Generate clj-kondo type-mismatch config from malli schema
  .clj-kondo/metosin/malli-types/config.edn"
  []
  (let [config-edn ".clj-kondo/metosin/malli-types/config.edn"
        compile-cmd "clojure -M:cljs compile gen-malli-kondo-config"]
    (println compile-cmd)
    (shell compile-cmd)
    (println "generate kondo config: " config-edn)
    (io/make-parents config-edn)
    (let [config (with-out-str
                   (pp/pprint (edn/read-string (:out (shell {:out :string} "node ./static/gen-malli-kondo-config.js")))))]
      (spit config-edn config))))

(defn build-publishing
  "Builds release publishing asset when files have changed"
  [& _args]
  (if-let [_files (and (not (System/getenv "SKIP_ASSET"))
                       (seq (set (fs/modified-since (fs/file "static/js/publishing/main.js")
                                                    (fs/glob "." "{src/main,deps/graph-parser/src}/**")))))]
    (do
      (println "Building publishing js asset...")
      (shell "clojure -M:cljs release publishing"))
    (println "Publishing js asset is up to date")))

(defn diff-datoms
  "Runs data/diff on two edn files written by dev:db-datoms"
  [file1 file2 & args]
  (let [spec {:ignored-attributes
              ;; Ignores some attributes by default that are expected to change often
              {:alias :i :coerce #{:keyword} :default #{:block/tx-id :block/left :block/updated-at}}}
        {{:keys [ignored-attributes]} :opts} (cli/parse-args args {:spec spec})
        datom-filter (fn [[e a _ _ _]] (contains? ignored-attributes a))
        data-diff* (apply data/diff (map (fn [x] (->> x slurp edn/read-string (remove datom-filter))) [file1 file2]))
        data-diff (->> data-diff*
                       ;; Drop common as we're only interested in differences
                       drop-last
                       ;; Remove nils as we're only interested in diffs
                       (mapv #(vec (remove nil? %))))]
    (pp/pprint data-diff)))