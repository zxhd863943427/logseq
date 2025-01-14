(ns frontend.db.restore
  "Fns for DB restore(from text or sqlite)"
  (:require [datascript.core :as d]
            [frontend.config :as config]
            [frontend.db.conn :as db-conn]
            [frontend.db.file-based.migrate :as db-migrate]
            [frontend.db.persist :as db-persist]
            [frontend.db.react :as react]
            [frontend.db.utils :as db-utils]
            [frontend.state :as state]
            [frontend.persist-db :as persist-db]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.util :as sqlite-util]
            [promesa.core :as p]
            [frontend.util :as util]
            [cljs-time.core :as t]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.util :as db-property-util]
            [datascript.transit :as dt]
            [logseq.db.sqlite.common-db :as sqlite-common-db]))

(defn- old-schema?
  "Requires migration if the schema version is older than db-schema/version"
  [db]
  (let [v (db-migrate/get-schema-version db)
        ;; backward compatibility
        v (if (integer? v) v 0)]
    (cond
      (= db-schema/version v)
      false

      (< db-schema/version v)
      (do
        (js/console.error "DB schema version is newer than the app, please update the app. " ":db-version" v)
        false)

      :else
      true)))

(defn- restore-graph-from-text!
  "Swap db string into the current db status
   stored: the text to restore from"
  [repo stored]
  (p/let [db-name (db-conn/datascript-db repo)
          db-conn (d/create-conn (db-conn/get-schema repo))
          _ (swap! db-conn/conns assoc db-name db-conn)
          _ (when stored
              (let [stored-db (try (db-utils/string->db stored)
                                   (catch :default _e
                                     (js/console.warn "Invalid graph cache")
                                     (d/empty-db (db-conn/get-schema repo))))
                    db (if (old-schema? stored-db)
                         (db-migrate/migrate stored-db)
                         stored-db)]
                (db-conn/reset-conn! db-conn db)))]
    (d/transact! db-conn [{:schema/version db-schema/version}])))

(defn- update-built-in-properties!
  [conn]
  (let [txs (mapcat
             (fn [[k-keyword {:keys [schema original-name] :as property-config}]]
               (let [k-name (name k-keyword)
                     property (d/entity @conn [:block/name k-name])]
                 (when (and
                        (not= {:schema schema
                               :original-name (or original-name k-name)}
                              {:schema (:block/schema property)
                               :original-name (:block/original-name property)})
                         ;; Updating closed values disabled until it's worth the effort
                         ;; to diff closed values
                        (not (:closed-values property-config)))
                   (if property
                     [{:block/schema schema
                       :block/original-name (or original-name k-name)
                       :block/name (util/page-name-sanity-lc k-name)
                       :block/uuid (:block/uuid property)
                       :block/type "property"}]
                     (if (:closed-values property-config)
                       (db-property-util/build-closed-values
                        (or original-name k-name)
                        (assoc property-config :block/uuid (d/squuid))
                        {})
                       [(sqlite-util/build-new-property
                         {:block/schema schema
                          :block/original-name (or original-name k-name)
                          :block/name (util/page-name-sanity-lc k-name)
                          :block/uuid (d/squuid)})])))))
             db-property/built-in-properties)]
    (when (seq txs)
      (d/transact! conn txs))))

(defn- restore-graph-from-sqlite!
  "Load initial data from SQLite"
  [repo]
  (state/set-state! :graph/loading? true)
  (p/let [start-time (t/now)
          data (persist-db/<fetch-init-data repo)
          _ (assert (some? data) "No data found when reloading db")
          datoms (dt/read-transit-str data)
          datoms-count (count datoms)
          conn (sqlite-common-db/restore-initial-data datoms)
          db-name (db-conn/datascript-db repo)
          _ (swap! db-conn/conns assoc db-name conn)
          end-time (t/now)]

    ;; FIXME: why not do this when creating the db?
    (update-built-in-properties! conn)

    (println :restore-graph-from-sqlite!-prepare (t/in-millis (t/interval start-time end-time)) "ms"
             " Datoms in total: " datoms-count)

    ;; FIXME:
    ;; (db-migrate/migrate attached-db)

    (p/let [_ (p/delay 150)]          ; More time for UI refresh
      (state/set-state! [repo :restore/unloaded-blocks] nil)
      (state/set-state! [repo :restore/unloaded-pages] nil)
      (state/set-state! :graph/loading? false)
      (react/clear-query-state!)
      (state/pub-event! [:ui/re-render-root]))))

(defn restore-graph!
  "Restore db from serialized db cache"
  [repo]
  (if (config/db-based-graph? repo)
    (restore-graph-from-sqlite! repo)
    (p/let [db-name (db-conn/datascript-db repo)
            stored (db-persist/get-serialized-graph db-name)]
      (restore-graph-from-text! repo stored))))
