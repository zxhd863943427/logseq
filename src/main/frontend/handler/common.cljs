(ns frontend.handler.common
  "Common fns for handlers"
  (:require [cljs-bean.core :as bean]
            [cljs.reader :as reader]
            [frontend.date :as date]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.handler.property :as property-handler]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            ["ignore" :as Ignore]
            [goog.functions :refer [debounce]]))

(defn copy-to-clipboard-without-id-property!
  [repo format raw-text html blocks]
  (util/copy-to-clipboard! (property-handler/remove-id-property repo format raw-text)
                           :html html
                           :blocks blocks))

(defn config-with-document-mode
  [config]
  (assoc config
         :document/mode? (state/sub [:document/mode?])))

(defn ignore-files
  [pattern paths]
  (-> (Ignore)
      (.add pattern)
      (.filter (bean/->js paths))
      (bean/->clj)))

(defn safe-read-string
  [content error-message-or-handler]
  (try
    (reader/read-string content)
    (catch :default e
      (js/console.error e)
      (if (fn? error-message-or-handler)
        (error-message-or-handler e)
        (println error-message-or-handler))
      {})))

(defn get-page-default-properties
  [page-name]
  {:title page-name
   ;; :date (date/get-date-time-string)
   })

(defn fix-pages-timestamps
  [pages]
  (map (fn [{:block/keys [created-at updated-at journal-day] :as p}]
         (cond->
           p

           (nil? created-at)
           (assoc :block/created-at
                  (if journal-day
                    (date/journal-day->ts journal-day)
                    (util/time-ms)))

           (nil? updated-at)
           (assoc :block/updated-at
                  ;; Not exact true
                  (if journal-day
                    (date/journal-day->ts journal-day)
                    (util/time-ms)))))
    pages))

(defn show-custom-context-menu! [e context-menu-content]
  (util/stop e)
  (let [position [(gobj/get e "clientX") (gobj/get e "clientY")]]
    (state/show-custom-context-menu! context-menu-content position)))

(defn listen-to-scroll!
  [element]
  (let [*scroll-timer (atom nil)
        on-scroll (fn []
                    (when @*scroll-timer
                      (js/clearTimeout @*scroll-timer))
                    (state/set-state! :ui/scrolling? true)
                    (state/save-scroll-position! (util/scroll-top))
                    (state/save-main-container-position!
                     (-> (gdom/getElement "main-content-container")
                         (gobj/get "scrollTop")))
                    (reset! *scroll-timer (js/setTimeout
                                           (fn [] (state/set-state! :ui/scrolling? false)) 500)))
        debounced-on-scroll (debounce on-scroll 100)]
    (.addEventListener element "scroll" debounced-on-scroll false)))
