(ns frontend.handler.dnd
  "Provides fns for drag and drop"
  (:require [frontend.handler.editor :as editor-handler]
            [frontend.handler.property :as property-handler]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.tree :as tree]
            [frontend.modules.outliner.transaction :as outliner-tx]
            [logseq.graph-parser.util.block-ref :as block-ref]
            [frontend.state :as state]
            [frontend.db :as db]))

(defn move-blocks
  [^js event blocks target-block original-block move-to]
  (let [blocks' (map #(db/pull (:db/id %)) blocks)
        first-block (first blocks')
        top? (= move-to :top)
        nested? (= move-to :nested)
        alt-key? (and event (.-altKey event))
        current-format (:block/format first-block)
        target-format (:block/format target-block)
        target-block (if nested? target-block
                         (or original-block target-block))]
    (cond
      ;; alt pressed, make a block-ref
      (and alt-key? (= (count blocks) 1))
      (do
        (property-handler/file-persist-block-id! (state/get-current-repo) (:block/uuid first-block))
        (editor-handler/api-insert-new-block!
         (block-ref/->block-ref (:block/uuid first-block))
         {:block-uuid (:block/uuid target-block)
          :sibling? (not nested?)
          :before? top?}))

      ;; format mismatch
      (and current-format target-format (not= current-format target-format))
      (state/pub-event! [:notification/show
                         {:content [:div "Those two pages have different formats."]
                          :status :warning
                          :clear? true}])

      (every? map? (conj blocks' target-block))
      (let [target-node (outliner-core/block target-block)]
        (outliner-tx/transact!
         {:outliner-op :move-blocks}
         (editor-handler/save-current-block!)
         (if top?
           (let [first-child?
                 (= (tree/-get-parent-id target-node)
                    (tree/-get-left-id target-node))]
             (if first-child?
               (when-let [parent (tree/-get-parent target-node)]
                 (outliner-core/move-blocks! blocks' (:data parent) false))
               (when-let [before-node (tree/-get-left target-node)]
                 (outliner-core/move-blocks! blocks' (:data before-node) true))))
           (outliner-core/move-blocks! blocks' target-block (not nested?)))))

      :else
      nil)))
