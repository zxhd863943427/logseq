(ns logseq.publishing.db
  "Provides db fns and associated util fns for publishing"
  (:require [datascript.core :as d]
            [logseq.db.frontend.rules :as rules]
            [clojure.set :as set]
            [clojure.string :as string]))

(defn ^:api get-area-block-asset-url
  "Returns asset url for an area block used by pdf assets. This lives in this ns
  because it is used by this dep and needs to be independent from the frontend app"
  ([block page] (get-area-block-asset-url block page {}))
  ;; TODO: Add prop-lookup-fn support for db graphs and commandline publishing
  ([block page {:keys [prop-lookup-fn] :or {prop-lookup-fn get}}]
   (when-some [props (and block page (:block/properties block))]
     (when-some [uuid (:block/uuid block)]
       (when-some [stamp (prop-lookup-fn props :hl-stamp)]
         (let [group-key      (string/replace-first (:block/original-name page) #"^hls__" "")
               hl-page        (prop-lookup-fn props :hl-page)
               encoded-chars? (boolean (re-find #"(?i)%[0-9a-f]{2}" group-key))
               group-key      (if encoded-chars? (js/encodeURI group-key) group-key)]
           (str "./assets/" group-key "/" (str hl-page "_" uuid "_" stamp ".png"))))))))

(defn- clean-asset-path-prefix
  [path]
  (when (string? path)
    (string/replace-first path #"^[.\/\\]*(assets)[\/\\]+" "")))

(defn- get-public-pages
  [db]
  (->> (d/q
        '[:find ?p
          :where
          [?p :block/name]
          [?p :block/properties ?properties]
          [(get ?properties :public) ?pub]
          [(= true ?pub)]]
        db)
       (map first)))

(defn- get-db-public-pages
  "Returns public pages and anything they are directly related to: their tags,
  their properties and any property values that are pages.  Anything on the
  related pages are _not_ included e.g. properties on tag or property pages"
  [db]
  (let [pages (->> (d/q
                    '[:find ?p
                      :in $ %
                      :where (page-property ?p :public true)]
                    db
                    (rules/extract-rules rules/db-query-dsl-rules [:page-property]))
                   (map first)
                   set)
        page-ents (map #(d/entity db %) pages)
        tag-pages* (mapcat #(map :db/id (:block/tags %)) page-ents)
        tag-pages (concat tag-pages*
                          ;; built-in property needs to be public to display tags
                          (when (seq tag-pages*)
                            (some-> (d/entity db [:block/name "tags"]) :db/id vector)))
        property-pages (mapcat (fn [ent]
                                 (let [props (:block/properties ent)]
                                   (->> (keys props)
                                        (into (mapcat #(filter uuid? (if (coll? %) % [%]))
                                                      (vals props)))
                                        (map #(:db/id (d/entity db [:block/uuid %]))))))
                               page-ents)]
    (concat pages tag-pages property-pages)))

(defn- get-db-public-false-pages
  [db]
  (->> (d/q
        '[:find ?p
          :in $ %
          :where (page-property ?p :public false)]
        db
        (rules/extract-rules rules/db-query-dsl-rules [:page-property]))
       (map first)
       set))

(defn- get-public-false-pages
  [db]
  (->> (d/q
        '[:find ?p
          :where
          [?p :block/name]
          [?p :block/properties ?properties]
          [(get ?properties :public) ?pub]
          [(= false ?pub)]]
        db)
       (map first)))

(defn- get-public-false-block-ids
  [db]
  (->> (d/q
        '[:find ?b
          :where
          [?p :block/name]
          [?p :block/properties ?properties]
          [(get ?properties :public) ?pub]
          [(= false ?pub)]
          [?b :block/page ?p]]
        db)
       (map first)))

;; FIXME: store assets as blocks for db-based graphs
(defn- get-assets
  [db datoms]
  (let [pull (fn [eid db]
               (d/pull db '[*] eid))
        get-page-by-eid
        (memoize #(some->
                   (pull % db)
                   :block/page
                   :db/id
                   (pull db)))]
    (->>
     (keep
      (fn [datom]
        (cond-> []
          (= :block/content (:a datom))
          (concat (let [matched (re-seq #"\([./]*/assets/([^)]+)\)" (:v datom))]
                    (when (seq matched)
                      (for [[_ path] matched]
                        (when (and (string? path)
                                   (not (string/ends-with? path ".js")))
                          path)))))
          ;; area image assets
          ;; FIXME: Lookup by property uuid
          (and
           (= :block/properties (:a datom))
           (= (keyword (get (:v datom) :hl-type)) :area))
          (#(let [path (some-> (pull (:e datom) db)
                               (get-area-block-asset-url
                                (get-page-by-eid (:e datom))))
                  path (clean-asset-path-prefix path)]
              (conj % path)))))
      datoms)
     flatten
     distinct)))

(defn- get-aliases-for-page-ids
  [db page-ids]
  (->> (d/q '[:find ?e
              :in $ ?pages %
              :where
              [?page :block/name]
              [(contains? ?pages ?page)]
              (alias ?page ?e)]
            db
            (set page-ids)
            (:alias rules/rules))
       (map first)
       set))

(defn clean-export!
  "Prepares a database assuming all pages are public unless a page has a 'public:: false'"
  [db {:keys [db-graph?]}]
  (let [remove? #(contains? #{"recent" "file"} %)
        non-public-datom-ids (if db-graph?
                               (get-db-public-false-pages db)
                               (set (concat (get-public-false-pages db) (get-public-false-block-ids db))))
        filtered-db (d/filter db
                              (fn [_db datom]
                                (let [ns (namespace (:a datom))]
                                  (and (not (remove? ns))
                                       (not (contains? #{:block/file} (:a datom)))
                                       (not (contains? non-public-datom-ids (:e datom)))))))
        datoms (d/datoms filtered-db :eavt)
        assets (get-assets db datoms)]
    [@(d/conn-from-datoms datoms (:schema db)) assets]))

(defn filter-only-public-pages-and-blocks
  "Prepares a database assuming all pages are private unless a page has a 'public:: true'"
  [db {:keys [db-graph?]}]
  (when-let [public-pages* (seq (if db-graph? (get-db-public-pages db) (get-public-pages db)))]
    (let [public-pages (set/union (set public-pages*)
                                  (get-aliases-for-page-ids db public-pages*))
          exported-namespace? #(contains? #{"block" "recent"} %)
          filtered-db (d/filter db
                                (fn [db datom]
                                  (let [ns (namespace (:a datom))]
                                    (and
                                     (not (contains? #{:block/file} (:a datom)))
                                     (not= ns "file")
                                     (or
                                      (not (exported-namespace? ns))
                                      (and (= ns "block")
                                           (or
                                            (contains? public-pages (:e datom))
                                            (contains? public-pages (:db/id (:block/page (d/entity db (:e datom))))))))))))
          datoms (d/datoms filtered-db :eavt)
          assets (get-assets db datoms)]
      [@(d/conn-from-datoms datoms (:schema db)) assets])))
