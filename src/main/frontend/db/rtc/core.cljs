(ns frontend.db.rtc.core
  "Main ns for rtc related fns"
  (:require-macros
   [frontend.db.rtc.macro :refer [with-sub-data-from-ws get-req-id get-result-ch]])
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.core.async :as async :refer [<! >! chan go go-loop]]
            [cljs.core.async.interop :refer [p->c]]
            [clojure.set :as set]
            [cognitect.transit :as transit]
            [frontend.db :as db]
            [frontend.db.rtc.const :as rtc-const]
            [frontend.db.rtc.op :as op]
            [frontend.db.rtc.ws :as ws]
            [frontend.handler.page :as page-handler]
            [frontend.handler.user :as user]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.transaction :as outliner-tx]
            [frontend.state :as state]
            [frontend.util :as util]
            [malli.core :as m]
            [malli.util :as mu]))


;;                     +-------------+
;;                     |             |
;;                     |   server    |
;;                     |             |
;;                     +----^----+---+
;;                          |    |
;;                          |    |
;;                          |   rtc-const/data-from-ws-schema
;;                          |    |
;; rtc-const/data-to-ws-schema   |
;;                          |    |
;;                          |    |
;;                          |    |
;;                     +----+----v---+                     +------------+
;;                     |             +--------------------->            |
;;                     |   client    |                     |  indexeddb |
;;                     |             |<--------------------+            |
;;                     +-------------+                     +------------+
;;                                frontend.db.rtc.op/op-schema

(def state-schema
  "
  | :*graph-uuid                      | atom of graph-uuid syncing now                     |
  | :*repo                            | atom of repo name syncing now                      |
  | :data-from-ws-chan                | channel for receive messages from server websocket |
  | :data-from-ws-pub                 | pub of :data-from-ws-chan, dispatch by :req-id     |
  | :*stop-rtc-loop-chan              | atom of chan to stop <loop-for-rtc                 |
  | :*ws                              | atom of websocket                                  |
  | :*rtc-state                       | atom of state of current rtc progress              |
  | :toggle-auto-push-client-ops-chan | channel to toggle pushing client ops automatically |
  | :*auto-push-client-ops?           | atom to show if it's push client-ops automatically |
  | :force-push-client-ops-chan       | chan used to force push client-ops                 |
"
  [:map {:closed true}
   [:*graph-uuid :any]
   [:*repo :any]
   [:data-from-ws-chan :any]
   [:data-from-ws-pub :any]
   [:*stop-rtc-loop-chan :any]
   [:*ws :any]
   [:*rtc-state :any]
   [:toggle-auto-push-client-ops-chan :any]
   [:*auto-push-client-ops? :any]
   [:force-push-client-ops-chan :any]])
(def state-validator (fn [data] (if ((m/validator state-schema) data)
                                  true
                                  (prn (mu/explain-data state-schema data)))))

(def rtc-state-schema
  [:enum :open :closed])
(def rtc-state-validator (m/validator rtc-state-schema))

(def transit-w (transit/writer :json))
(def transit-r (transit/reader :json))

(def ^{:private true :dynamic true} *RUNNING-TESTS* "true when running tests" false)
(defmulti transact-db! (fn [action & _args]
                        (keyword (str (name action) (when *RUNNING-TESTS* "-for-test")))))

(defmethod transact-db! :delete-blocks [_ & args]
  (outliner-tx/transact!
   {:persist-op? false}
   (apply outliner-core/delete-blocks! args)))

(defmethod transact-db! :delete-blocks-for-test [_ & args]
  (prn ::delete-block-for-test args))

(defmethod transact-db! :move-blocks [_ & args]
  (outliner-tx/transact!
   {:persist-op? false}
   (apply outliner-core/move-blocks! args)))

(defmethod transact-db! :move-blocks-for-test [_ & args]
  (prn ::move-blocks-for-test args))

(defmethod transact-db! :insert-blocks [_ & args]
  (outliner-tx/transact!
   {:persist-op? false}
   (apply outliner-core/insert-blocks! args)))

(defmethod transact-db! :insert-blocks-for-test [_ & args]
  (prn ::insert-blocks-for-test args))

(defmethod transact-db! :save-block [_ & args]
  (outliner-tx/transact!
   {:persist-op? false}
   (apply outliner-core/save-block! args)))

(defmethod transact-db! :save-block-for-test [_ & args]
  (prn ::save-block-for-test args))

(defmethod transact-db! :raw [_ & args]
  (apply db/transact! args))

(defmethod transact-db! :raw-for-test [_ & args]
  (prn ::raw-for-test args))

(defn apply-remote-remove-ops
  [repo remove-ops]
  (prn :remove-ops remove-ops)
  (doseq [op remove-ops]
    (when-let [block (db/pull repo '[*] [:block/uuid (uuid (:block-uuid op))])]
      (transact-db! :delete-blocks [block] {:children? false})
      (prn :apply-remote-remove-ops (:block-uuid op)))))

(defn- insert-or-move-block
  [repo block-uuid-str remote-parents remote-left-uuid-str move?]
  (when (and (seq remote-parents) remote-left-uuid-str)
    (let [local-left (db/pull repo '[*] [:block/uuid (uuid remote-left-uuid-str)])
          first-remote-parent (first remote-parents)
          local-parent (db/pull repo '[*] [:block/uuid (uuid first-remote-parent)])
          b {:block/uuid (uuid block-uuid-str)}
          ;; b-ent (db/entity repo [:block/uuid (uuid block-uuid-str)])
          ]
      (case [(some? local-parent) (some? local-left)]
        [false true]
        (if move?
          (transact-db! :move-blocks [b] local-left true)
          (transact-db! :insert-blocks
                        [{:block/uuid (uuid block-uuid-str)
                          :block/content ""
                          :block/format :markdown}]
                        local-left {:sibling? true :keep-uuid? true}))

        [true true]
        (let [sibling? (not= (:block/uuid local-parent) (:block/uuid local-left))]
          (if move?
            (transact-db! :move-blocks [b] local-left sibling?)
            (transact-db! :insert-blocks
                          [{:block/uuid (uuid block-uuid-str) :block/content ""
                            :block/format :markdown}]
                          local-left {:sibling? sibling? :keep-uuid? true})))

        [true false]
        (if move?
          (transact-db! :move-blocks [b] local-parent false)
          (transact-db! :insert-blocks
                        [{:block/uuid (uuid block-uuid-str) :block/content ""
                          :block/format :markdown}]
                        local-parent {:sibling? false :keep-uuid? true}))
        (throw (ex-info "Don't know where to insert" {:block-uuid block-uuid-str :remote-parents remote-parents
                                                      :remote-left remote-left-uuid-str}))))))

(defn- move-ops-map->sorted-move-ops
  [move-ops-map]
  (let [uuid->dep-uuids (into {} (map (fn [[uuid env]] [uuid (set (conj (:parents env) (:left env)))]) move-ops-map))
        all-uuids (set (keys move-ops-map))
        sorted-uuids
        (loop [r []
               rest-uuids all-uuids
               uuid (first rest-uuids)]
          (if-not uuid
            r
            (let [dep-uuids (uuid->dep-uuids uuid)]
              (if-let [next-uuid (first (set/intersection dep-uuids rest-uuids))]
                (recur r rest-uuids next-uuid)
                (let [rest-uuids* (disj rest-uuids uuid)]
                  (recur (conj r uuid) rest-uuids* (first rest-uuids*)))))))]
    (mapv move-ops-map sorted-uuids)))

(comment
  (def move-ops-map {"2" {:parents ["1"] :left "1" :x "2"}
                     "1" {:parents ["3"] :left nil :x "1"}
                     "3" {:parents [] :left nil :x "3"}})
  (move-ops-map->sorted-move-ops move-ops-map))

(defn- check-block-pos
  [repo block-uuid-str remote-parents remote-left-uuid-str]
  (let [local-b (db/pull repo '[{:block/left [:block/uuid]}
                                {:block/parent [:block/uuid]}
                                *]
                         [:block/uuid (uuid block-uuid-str)])
        remote-parent-uuid-str (first remote-parents)]
    (cond
      (nil? local-b)
      :not-exist

      (not (and (= (str (:block/uuid (:block/parent local-b))) remote-parent-uuid-str)
                (= (str (:block/uuid (:block/left local-b))) remote-left-uuid-str)))
      :wrong-pos
      :else nil)))



(defn- update-block-attrs
  [repo block-uuid op-value]
  (let [key-set (set/intersection
                 (conj rtc-const/general-attr-set :content)
                 (set (keys op-value)))]
    (when (seq key-set)
      (let [b-ent (db/pull repo '[*] [:block/uuid block-uuid])
            new-block
            (cond-> (db/pull repo '[*] (:db/id b-ent))
              (and (contains? key-set :content)
                   (not= (:content op-value)
                         (:block/content b-ent))) (assoc :block/content (:content op-value))
              (contains? key-set :updated-at)     (assoc :block/updated-at (:updated-at op-value))
              (contains? key-set :created-at)     (assoc :block/created-at (:created-at op-value))
              (contains? key-set :alias)          (assoc :block/alias (some->> (seq (:alias op-value))
                                                                               (map (partial vector :block/uuid))
                                                                               (db/pull-many repo [:db/id])
                                                                               (keep :db/id)))
              (contains? key-set :type)           (assoc :block/type (:type op-value))
              (contains? key-set :schema)         (assoc :block/schema (transit/read transit-r (:schema op-value)))
              (contains? key-set :tags)           (assoc :block/tags (some->> (seq (:tags op-value))
                                                                              (map (partial vector :block/uuid))
                                                                              (db/pull-many repo [:db/id])
                                                                              (keep :db/id)))
              ;; FIXME: it looks save-block won't save :block/properties??
              ;;        so I need to transact properties myself
              ;; (contains? key-set :properties)     (assoc :block/properties
              ;;                                            (transit/read transit-r (:properties op-value)))
              )]
        (transact-db! :save-block new-block)
        (let [properties (transit/read transit-r (:properties op-value))]
          (transact-db! :raw
                        repo
                        [{:block/uuid block-uuid
                          :block/properties properties}]
                        {:outliner-op :save-block}))))))

(defn apply-remote-move-ops
  [repo sorted-move-ops]
  (prn :sorted-move-ops sorted-move-ops)
  (doseq [{:keys [parents left self] :as op-value} sorted-move-ops]
    (let [r (check-block-pos repo self parents left)]
      (case r
        :not-exist
        (insert-or-move-block repo self parents left false)
        :wrong-pos
        (insert-or-move-block repo self parents left true)
        nil                             ; do nothing
        nil)
      (update-block-attrs repo (uuid self) op-value)
      (prn :apply-remote-move-ops self r parents left))))


(defn apply-remote-update-ops
  [repo update-ops]
  (prn :update-ops update-ops)
  (doseq [{:keys [parents left self] :as op-value} update-ops]
    (when (and parents left)
      (let [r (check-block-pos repo self parents left)]
        (case r
          :not-exist
          (insert-or-move-block repo self parents left false)
          :wrong-pos
          (insert-or-move-block repo self parents left true)
          nil)))
    (update-block-attrs repo (uuid self) op-value)
    (prn :apply-remote-update-ops self)))

(defn apply-remote-update-page-ops
  [repo update-page-ops]
  (doseq [{:keys [self page-name original-name] :as op-value} update-page-ops]
    (let [old-page-original-name (:block/original-name
                                  (db/pull repo [:block/original-name] [:block/uuid (uuid self)]))
          exist-page (db/pull repo [:block/uuid] [:block/name page-name])]
      (cond
          ;; same name but different uuid
          ;; remote page has same block/name as local's, but they don't have same block/uuid.
          ;; 1. rename local page's name to '<origin-name>-<ms-epoch>-Conflict'
          ;; 2. create page, name=<origin-name>, uuid=remote-uuid
        (and exist-page (not= (:block/uuid exist-page) (uuid self)))
        (do (page-handler/rename! original-name (util/format "%s-%s-CONFLICT" original-name (tc/to-long (t/now))))
            (page-handler/create! original-name {:redirect? false :create-first-block? false
                                                 :uuid (uuid self) :persist-op? false}))

          ;; a client-page has same uuid as remote but different page-names,
          ;; then we need to rename the client-page to remote-page-name
        (and old-page-original-name (not= old-page-original-name original-name))
        (page-handler/rename! old-page-original-name original-name false false)

          ;; no such page, name=remote-page-name, OR, uuid=remote-block-uuid
          ;; just create-page
        :else
        (page-handler/create! original-name {:redirect? false :create-first-block? false
                                             :uuid (uuid self) :persist-op? false}))

      (update-block-attrs repo (uuid self) op-value))))

(defn apply-remote-remove-page-ops
  [repo remove-page-ops]
  (doseq [op remove-page-ops]
    (when-let [page-name (:block/name
                          (db/pull repo [:block/name] [:block/uuid (uuid (:block-uuid op))]))]
      (page-handler/delete! page-name nil {:redirect-to-home? false :persist-op? false}))))


(defn- filter-remote-data-by-local-unpushed-ops
  "when remote-data request client to move/update blocks,
  these updates maybe not needed, because this client just updated some of these blocks,
  so we need to filter these just-updated blocks out, according to the unpushed-local-ops in indexeddb"
  [affected-blocks-map local-unpushed-ops]
  (reduce
   (fn [affected-blocks-map local-op]
     (case (first local-op)
       "move"
       (let [block-uuids (:block-uuids (second local-op))
             remote-ops (vals (select-keys affected-blocks-map block-uuids))
             block-uuids-to-del-in-result
             (keep (fn [op] (when (= :move (:op op)) (:self op))) remote-ops)]
         (apply dissoc affected-blocks-map block-uuids-to-del-in-result))

       "update"
       (let [block-uuid (:block-uuid (second local-op))
             local-updated-attr-set (set (keys (:updated-attrs (second local-op))))]
         (if-let [remote-op (get affected-blocks-map block-uuid)]
           (assoc affected-blocks-map block-uuid
                  (if (= :update-attrs (:op remote-op))
                    (apply dissoc remote-op local-updated-attr-set)
                    remote-op))
           affected-blocks-map))
       ;;else
       affected-blocks-map))
   affected-blocks-map local-unpushed-ops))


(defn <apply-remote-data
  [repo data-from-ws & {:keys [max-pushed-op-key]}]
  (assert (rtc-const/data-from-ws-validator data-from-ws) data-from-ws)
  (go
    (let [remote-t (:t data-from-ws)
          remote-t-before (:t-before data-from-ws)
          {:keys [local-tx ops]} (<! (p->c (op/<get-ops&local-tx repo)))]
      (cond
        (not (and (pos? remote-t)
                  (pos? remote-t-before)))
        (throw (ex-info "invalid remote-data" {:data data-from-ws}))

        (<= remote-t local-tx)
        (prn ::skip :remote-t remote-t :remote-t remote-t-before :local-t local-tx)

        (< local-tx remote-t-before)
        (do (prn ::need-pull-remote-data :remote-t remote-t :remote-t-before remote-t-before :local-t local-tx)
            ::need-pull-remote-data)

        (<= remote-t-before local-tx remote-t)
        (let [affected-blocks-map (:affected-blocks data-from-ws)
              unpushed-ops (when max-pushed-op-key
                             (keep (fn [[key op]] (when (> key max-pushed-op-key) op)) ops))
              affected-blocks-map* (if unpushed-ops
                                     (filter-remote-data-by-local-unpushed-ops
                                      affected-blocks-map unpushed-ops)
                                     affected-blocks-map)
              {remove-ops-map :remove move-ops-map :move update-ops-map :update-attrs
               update-page-ops-map :update-page remove-page-ops-map :remove-page}
              (update-vals
               (group-by (fn [[_ env]] (get env :op)) affected-blocks-map*)
               (partial into {}))
              remove-ops (vals remove-ops-map)
              sorted-move-ops (move-ops-map->sorted-move-ops move-ops-map)
              update-ops (vals update-ops-map)
              update-page-ops (vals update-page-ops-map)
              remove-page-ops (vals remove-page-ops-map)]
          (util/profile :apply-remote-update-page-ops (apply-remote-update-page-ops repo update-page-ops))
          (util/profile :apply-remote-remove-ops (apply-remote-remove-ops repo remove-ops))
          (util/profile :apply-remote-move-ops (apply-remote-move-ops repo sorted-move-ops))
          (util/profile :apply-remote-update-ops (apply-remote-update-ops repo update-ops))
          (util/profile :apply-remote-remove-page-ops (apply-remote-remove-page-ops repo remove-page-ops))
          (<! (p->c (op/<update-local-tx! repo remote-t))))
        :else (throw (ex-info "unreachable" {:remote-t remote-t
                                             :remote-t-before remote-t-before
                                             :local-t local-tx}))))))

(defn- <push-data-from-ws-handler
  [repo push-data-from-ws]
  (prn :push-data-from-ws push-data-from-ws)
  (go
    (let [r (<! (<apply-remote-data repo push-data-from-ws))]
      (when (= r ::need-pull-remote-data)
        r))))


(defn- ^:large-vars/cleanup-todo local-ops->remote-ops
  [repo sorted-ops _verbose?]
  (let [[remove-block-uuid-set move-block-uuid-set update-page-uuid-set remove-page-uuid-set update-block-uuid->attrs]
        (reduce
         (fn [[remove-block-uuid-set move-block-uuid-set update-page-uuid-set
               remove-page-uuid-set update-block-uuid->attrs]
              op]
           (case (first op)
             "move"
             (let [block-uuids (set (:block-uuids (second op)))
                   move-block-uuid-set (set/union move-block-uuid-set block-uuids)
                   remove-block-uuid-set (set/difference remove-block-uuid-set block-uuids)]
               [remove-block-uuid-set move-block-uuid-set update-page-uuid-set
                remove-page-uuid-set update-block-uuid->attrs])

             "remove"
             (let [block-uuids (set (:block-uuids (second op)))
                   move-block-uuid-set (set/difference move-block-uuid-set block-uuids)
                   remove-block-uuid-set (set/union remove-block-uuid-set block-uuids)]
               [remove-block-uuid-set move-block-uuid-set update-page-uuid-set
                remove-page-uuid-set update-block-uuid->attrs])

             "update-page"
             (let [block-uuid (:block-uuid (second op))
                   update-page-uuid-set (conj update-page-uuid-set block-uuid)]
               [remove-block-uuid-set move-block-uuid-set update-page-uuid-set
                remove-page-uuid-set update-block-uuid->attrs])

             "remove-page"
             (let [block-uuid (:block-uuid (second op))
                   remove-page-uuid-set (conj remove-page-uuid-set block-uuid)]
               [remove-block-uuid-set move-block-uuid-set update-page-uuid-set
                remove-page-uuid-set update-block-uuid->attrs])

             "update"
             (let [{:keys [block-uuid updated-attrs]} (second op)
                   attr-map (update-block-uuid->attrs block-uuid)
                   {{old-alias-add :add old-alias-retract :retract} :alias
                    {old-tags-add :add old-tags-retract :retract}   :tags
                    {old-type-add :add old-type-retract :retract}   :type
                    {old-prop-add :add old-prop-retract :retract}   :properties} attr-map
                   {{new-alias-add :add new-alias-retract :retract} :alias
                    {new-tags-add :add new-tags-retract :retract}   :tags
                    {new-type-add :add new-type-retract :retract}   :type
                    {new-prop-add :add new-prop-retract :retract}   :properties} updated-attrs
                   new-attr-map
                   (cond-> (merge (select-keys updated-attrs [:content :schema])
                                  (select-keys attr-map [:content :schema]))
                     ;; alias
                     (or old-alias-add new-alias-add)
                     (assoc-in [:alias :add] (set/union old-alias-add new-alias-add))
                     (or old-alias-retract new-alias-retract)
                     (assoc-in [:alias :retract] (set/difference (set/union old-alias-retract new-alias-retract)
                                                                 old-alias-add new-alias-add))
                     ;; tags
                     (or old-tags-add new-tags-add)
                     (assoc-in [:tags :add] (set/union old-tags-add new-tags-add))
                     (or old-tags-retract new-tags-retract)
                     (assoc-in [:tags :retract] (set/difference (set/union old-tags-retract new-tags-retract)
                                                                old-tags-add new-tags-add))
                     ;; type
                     (or old-type-add new-type-add)
                     (assoc-in [:type :add] (set/union old-type-add new-type-add))
                     (or old-type-retract new-type-retract)
                     (assoc-in [:type :retract] (set/difference (set/union old-type-retract new-type-retract)
                                                                old-type-add new-type-retract))

                     ;; properties
                     (or old-prop-add new-prop-add)
                     (assoc-in [:properties :add] (set/union old-prop-add new-prop-add))
                     (or old-prop-retract new-prop-retract)
                     (assoc-in [:properties :retract] (set/difference (set/union old-prop-retract new-prop-retract)
                                                                      old-prop-add new-prop-retract)))
                   update-block-uuid->attrs (assoc update-block-uuid->attrs block-uuid new-attr-map)]
               [remove-block-uuid-set move-block-uuid-set update-page-uuid-set
                remove-page-uuid-set update-block-uuid->attrs])
             (throw (ex-info "unknown op type" op))))
         [#{} #{} #{} #{} {}] sorted-ops)
        move-ops (keep
                  (fn [block-uuid]
                    (when-let [block (db/pull repo '[{:block/left [:block/uuid]}
                                                     {:block/parent [:block/uuid]}]
                                              [:block/uuid (uuid block-uuid)])]
                      (let [left-uuid (some-> block :block/left :block/uuid str)
                            parent-uuid (some-> block :block/parent :block/uuid str)]
                        (when (and left-uuid parent-uuid)
                          [:move
                           {:block-uuid block-uuid :target-uuid left-uuid :sibling? (not= left-uuid parent-uuid)}]))))
                  move-block-uuid-set)
        remove-block-uuid-set
        (filter (fn [block-uuid] (nil? (db/pull [:block/uuid] repo [:block/uuid (uuid block-uuid)]))) remove-block-uuid-set)
        remove-ops (when (seq remove-block-uuid-set) [[:remove {:block-uuids remove-block-uuid-set}]])
        update-page-ops (keep (fn [block-uuid]
                                (when-let [{page-name :block/name
                                            original-name :block/original-name}
                                           (db/pull repo [:block/name :block/original-name] [:block/uuid (uuid block-uuid)])]
                                  [:update-page {:block-uuid block-uuid
                                                 :page-name page-name
                                                 :original-name (or original-name page-name)}]))
                              update-page-uuid-set)
        remove-page-ops (keep (fn [block-uuid]
                                (when (nil? (db/pull repo [:block/uuid] [:block/uuid (uuid block-uuid)]))
                                  [:remove-page {:block-uuid block-uuid}]))
                              remove-page-uuid-set)
        update-ops (keep (fn [[block-uuid attr-map]]
                           (when-let [b (db/pull repo '[{:block/left [:block/uuid]}
                                                        {:block/parent [:block/uuid]}
                                                        *]
                                                 [:block/uuid (uuid block-uuid)])]
                             (let [key-set (set (keys attr-map))
                                   left-uuid (some-> b :block/left :block/uuid str)
                                   parent-uuid (some-> b :block/parent :block/uuid str)
                                   attr-alias-map (when (contains? key-set :alias)
                                                    (let [{:keys [add retract]} (:alias attr-map)
                                                          add-uuids (->> add
                                                                         (map (fn [x] [:block/uuid x]))
                                                                         (db/pull-many repo [:block/uuid])
                                                                         (keep :block/uuid)
                                                                         (map str))
                                                          retract-uuids (map str retract)]
                                                      (cond-> {}
                                                        (seq add-uuids)     (assoc :add add-uuids)
                                                        (seq retract-uuids) (assoc :retract retract-uuids))))
                                   attr-type-map (when (contains? key-set :type)
                                                   (let [{:keys [add retract]} (:type attr-map)
                                                         current-type-value (set (:block/type b))
                                                         add (set/intersection add current-type-value)
                                                         retract (set/difference retract current-type-value)]
                                                     (cond-> {}
                                                       (seq add)     (assoc :add add)
                                                       (seq retract) (assoc :retract retract))))
                                   attr-tags-map (when (contains? key-set :tags)
                                                   (let [{:keys [add retract]} (:tags attr-map)
                                                         add-uuids (->> add
                                                                        (map (fn [x] [:block/uuid x]))
                                                                        (db/pull-many repo [:block/uuid])
                                                                        (keep :block/uuid)
                                                                        (map str))
                                                         retract-uuids (map str retract)]
                                                     (cond-> {}
                                                       (seq add-uuids) (assoc :add add-uuids)
                                                       (seq retract-uuids) (assoc :retract retract-uuids))))
                                   attr-properties-map (when (contains? key-set :properties)
                                                         (let [{:keys [add retract]} (:properties attr-map)
                                                               properties (:block/properties b)
                                                               add* (into []
                                                                          (update-vals (select-keys properties add)
                                                                                       (partial transit/write transit-w)))]
                                                           (cond-> {}
                                                             (seq add*)    (assoc :add add*)
                                                             (seq retract) (assoc :retract retract))))]

                               [:update
                                (cond-> {:block-uuid block-uuid}
                                  (:block/updated-at b)       (assoc :updated-at (:block/updated-at b))
                                  (:block/created-at b)       (assoc :created-at (:block/created-at b))
                                  (contains? key-set :schema) (assoc :schema (transit/write transit-w (:block/schema b)))
                                  attr-type-map               (assoc :type attr-type-map)
                                  attr-alias-map              (assoc :alias attr-alias-map)
                                  attr-tags-map               (assoc :tags attr-tags-map)
                                  attr-properties-map         (assoc :properties attr-properties-map)
                                  (and (contains? key-set :content)
                                       (:block/content b))    (assoc :content (:block/content b))
                                  (and left-uuid parent-uuid) (assoc :target-uuid left-uuid
                                                                     :sibling? (not= left-uuid parent-uuid)))])))
                         update-block-uuid->attrs)]
    [update-page-ops remove-ops move-ops update-ops remove-page-ops]))


(defn- <get-N-ops
  [repo n]
  (go
    (let [{:keys [ops local-tx]} (<! (p->c (op/<get-ops&local-tx repo)))
          ops (take n ops)
          op-keys (map first ops)
          ops (map second ops)
          max-op-key (apply max op-keys)]
      {:ops ops :op-keys op-keys :max-op-key max-op-key :local-tx local-tx})))

(def ^:private size-30kb (* 30 1024))

(defn- <gen-remote-ops-<30kb
  [repo & n]
  (go
    (let [n (or n 100)
          {:keys [ops local-tx op-keys max-op-key]} (<! (<get-N-ops repo n))
          ops-for-remote (apply concat (local-ops->remote-ops repo ops nil))
          ops-for-remote-str (-> ops-for-remote
                                 rtc-const/data-to-ws-decoder
                                 rtc-const/data-to-ws-encoder
                                 clj->js
                                 js/JSON.stringify)
          size (.-size (js/Blob. [ops-for-remote-str]))]
      (if (<= size size-30kb)
        {:ops-for-remote ops-for-remote
         :local-tx local-tx
         :op-keys op-keys
         :max-op-key max-op-key}
        (let [n* (int (/ n (/ size size-30kb)))]
          (assert (pos? n*) {:n* n :n n :size size})
          (<! (<gen-remote-ops-<30kb repo n*)))))))


(defn- <client-op-update-handler
  [state]
  {:pre [(some? @(:*graph-uuid state))
         (some? @(:*repo state))]}
  (go
    (let [repo @(:*repo state)
          {:keys [ops-for-remote local-tx op-keys max-op-key]} (<! (<gen-remote-ops-<30kb repo))
          r (with-sub-data-from-ws state
              (<! (ws/<send! state {:req-id (get-req-id)
                                    :action "apply-ops" :graph-uuid @(:*graph-uuid state)
                                    :ops ops-for-remote :t-before (or local-tx 1)}))
              (<! (get-result-ch)))]
      (if-let [remote-ex (:ex-data r)]
        (case (:type remote-ex)
          ;; conflict-update remote-graph, keep these local-pending-ops
          ;; and try to send ops later
          "graph-lock-failed"
          (do (prn :graph-lock-failed)
              nil)
          ;; this case means something wrong in remote-graph data,
          ;; nothing to do at client-side
          "graph-lock-missing"
          (do (prn :graph-lock-missing)
              nil)
          ;; else
          (throw (ex-info "Unavailable" {:remote-ex remote-ex})))
        (do (assert (pos? (:t r)) r)
            (<! (p->c (op/<clean-ops repo op-keys)))
            (<! (<apply-remote-data repo (rtc-const/data-from-ws-decoder r)
                                    :max-pushed-op-key max-op-key))
            (prn :<client-op-update-handler :t (:t r)))))))

(defn- make-push-client-ops-timeout-ch
  [repo never-timeout?]
  (if never-timeout?
    (chan)
    (go
      (<! (async/timeout 2000))
      (when (seq (:ops (<! (p->c (op/<get-ops&local-tx repo)))))
        true))))

(defn <loop-for-rtc
  [state graph-uuid repo]
  {:pre [(state-validator state)
         (some? graph-uuid)
         (some? repo)]}
  (go
    (reset! (:*repo state) repo)
    (reset! (:*rtc-state state) :open)
    (let [{:keys [data-from-ws-pub _client-op-update-chan]} state
          push-data-from-ws-ch (chan (async/sliding-buffer 100) (map rtc-const/data-from-ws-decoder))
          stop-rtc-loop-chan (chan)
          *auto-push-client-ops? (:*auto-push-client-ops? state)
          force-push-client-ops-ch (:force-push-client-ops-chan state)
          toggle-auto-push-client-ops-ch (:toggle-auto-push-client-ops-chan state)]
      (reset! (:*stop-rtc-loop-chan state) stop-rtc-loop-chan)
      (<! (ws/<ensure-ws-open! state))
      (reset! (:*graph-uuid state) graph-uuid)
      (with-sub-data-from-ws state
        (<! (ws/<send! state {:action "register-graph-updates" :req-id (get-req-id) :graph-uuid graph-uuid}))
        (<! (get-result-ch)))

      (async/sub data-from-ws-pub "push-updates" push-data-from-ws-ch)
      (<! (go-loop [push-client-ops-ch
                    (make-push-client-ops-timeout-ch repo (not @*auto-push-client-ops?))]
            (let [{:keys [push-data-from-ws client-op-update stop continue]}
                  (async/alt!
                    toggle-auto-push-client-ops-ch {:continue true}
                    force-push-client-ops-ch {:client-op-update true}
                    push-client-ops-ch ([v] (if (and @*auto-push-client-ops? (true? v))
                                              {:client-op-update true}
                                              {:continue true}))
                    push-data-from-ws-ch ([v] {:push-data-from-ws v})
                    stop-rtc-loop-chan {:stop true}
                    :priority true)]
              (cond
                continue
                (recur (make-push-client-ops-timeout-ch repo (not @*auto-push-client-ops?)))

                push-data-from-ws
                (let [r (<! (<push-data-from-ws-handler repo push-data-from-ws))]
                  (when (= r ::need-pull-remote-data)
                    ;; trigger a force push, which can pull remote-diff-data from local-t to remote-t
                    (async/put! force-push-client-ops-ch true))
                  (recur (make-push-client-ops-timeout-ch repo (not @*auto-push-client-ops?))))

                client-op-update
                (let [maybe-exp (<! (user/<wrap-ensure-id&access-token
                                     (<! (<client-op-update-handler state))))]
                  (if (= :expired-token (:anom (ex-data maybe-exp)))
                    (prn ::<loop-for-rtc "quitting loop" maybe-exp)
                    (recur (make-push-client-ops-timeout-ch repo (not @*auto-push-client-ops?)))))

                stop
                (do (ws/stop @(:*ws state))
                    (reset! (:*rtc-state state) :closed))

                :else
                nil))))
      (async/unsub data-from-ws-pub "push-updates" push-data-from-ws-ch))))


(defn <grant-graph-access-to-others
  [state graph-uuid & {:keys [target-user-uuids target-user-emails]}]
  (go
    (let [r (with-sub-data-from-ws state
              (<! (ws/<send! state (cond-> {:req-id (get-req-id)
                                            :action "grant-access"
                                            :graph-uuid graph-uuid}
                                     target-user-uuids (assoc :target-user-uuids target-user-uuids)
                                     target-user-emails (assoc :target-user-emails target-user-emails))))
              (<! (get-result-ch)))]
      (if-let [ex-message (:ex-message r)]
        (prn ::<grant-graph-access-to-others ex-message (:ex-data r))
        (prn ::<grant-graph-access-to-others :succ)))))

(defn <toggle-auto-push-client-ops
  [state]
  (go
    (swap! (:*auto-push-client-ops? state) not)
    (>! (:toggle-auto-push-client-ops-chan state) true)))

(defn <get-block-content-versions
  [state block-uuid]
  (go
    (when (some-> state :*graph-uuid deref)
      (with-sub-data-from-ws state
        (<! (ws/<send! state {:req-id (get-req-id)
                              :action "query-block-content-versions"
                              :block-uuids [block-uuid]
                              :graph-uuid @(:*graph-uuid state)}))
        (let [{:keys [ex-message ex-data versions]} (<! (get-result-ch))]
          (if ex-message
            (prn ::<get-block-content-versions :ex-message ex-message :ex-data ex-data)
            versions))))))


(defn- init-state
  [ws data-from-ws-chan]
  ;; {:post [(m/validate state-schema %)]}
  {:*rtc-state (atom :closed :validator rtc-state-validator)
   :*graph-uuid (atom nil)
   :*repo (atom nil)
   :data-from-ws-chan data-from-ws-chan
   :data-from-ws-pub (async/pub data-from-ws-chan :req-id)
   :toggle-auto-push-client-ops-chan (chan (async/sliding-buffer 1))
   :*auto-push-client-ops? (atom true :validator boolean?)
   :*stop-rtc-loop-chan (atom nil)
   :force-push-client-ops-chan (chan (async/sliding-buffer 1))
   :*ws (atom ws)})

(defn <init-state
  []
  (go
    (let [data-from-ws-chan (chan (async/sliding-buffer 100))
          ws-opened-ch (chan)]
      (<! (user/<wrap-ensure-id&access-token
           (let [token (state/get-auth-id-token)
                 ws (ws/ws-listen token data-from-ws-chan ws-opened-ch)]
             (<! ws-opened-ch)
             (init-state ws data-from-ws-chan)))))))
