(ns snergly.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.core :as omcore]
            [om.dom :as dom]
            [snergly.algorithms :as algs]
            [snergly.grid :as grid]
            [snergly.util :as util]
            [snergly.image :as image]
            ))

(enable-console-print!)

(declare reconciler)

(defn set-maze-params! [& kvpairs]
  (assert (= 0 (mod (count kvpairs) 2)) "Arguments must be pairs of keys and values")
  (om/transact! reconciler
                (mapv (fn [[k v]] `(snergly.core/set-maze {:maze-key ~k :value ~v}))
                      (partition 2 kvpairs))))

(def init-data
  {:app/algorithms (sort algs/algorithm-names)
   :app/analyses ["none" "distances"] ; "path" "longest path"
   :maze {:algorithm ""
          :rows 10
          :columns 10
          :cell-size 10
          :grid nil

          :analysis "none"
          :start-row 0
          :start-col 0
          :end-row 0
          :end-col 0

          :active nil}})

(defn annotate-grid [maze distances]
  (grid/grid-annotate-cells maze {:color (grid/xform-values #(util/color-cell (:max distances) %) distances)}))

(defn annotate-grid-flat [maze distances]
  (grid/grid-annotate-cells maze {:color (grid/xform-values #(util/color-cell % %) distances)}))

(defn produce-analysis-async [maze {:keys [analysis start-row start-col end-row end-col] :as maze-params}]
  (println (str "analysis: " analysis))
  (if (= "distances" analysis)
    (do
      (let [intermediate-chan (async/chan)
            result-chan (algs/find-distances maze [start-row start-col] intermediate-chan)]
        (set-maze-params! :active "Finding distances …")
        (go-loop [grid maze]
                 (let [distances (async/<! intermediate-chan)]
                   (if distances
                     (let [grid (annotate-grid grid distances)]
                       (set-maze-params! :grid (atom grid))
                       (async/<! (async/timeout 0))
                       (recur grid))
                     (let [distances (async/<! result-chan)]
                       (set-maze-params! :grid (atom (annotate-grid maze distances))
                                         :active nil)
                       (async/<! (async/timeout 0)))
                     )))
        (async/timeout 0)))
    (async/timeout 0)
  ))

(defn produce-maze-async [{:keys [rows columns algorithm] :as maze-params}]
  (println (str "algorithm: " algorithm))
  (set-maze-params! :active "Carving maze …")
  (let [intermediate-chan (async/chan)
        algorithm-fn (algs/algorithm-functions algorithm)
        grid (grid/make-grid rows columns)
        result-chan (algorithm-fn (grid/make-grid rows columns) intermediate-chan)]
    (go
      (set-maze-params! :grid (atom grid))
      (async/<! (async/timeout 0))
      (loop []
        (if-let [new-maze (async/<! intermediate-chan)]
          (do
            (set-maze-params! :grid (atom new-maze))
            (async/<! (async/timeout 0))
            (recur))
          (let [maze (async/<! result-chan)]
            (set-maze-params! :grid (atom maze)
                              :active nil)
            maze))))))

(defn run-animation [maze-params]
  (go
    (let [maze (async/<! (produce-maze-async maze-params))
          maze (async/<! (produce-analysis-async maze maze-params))])))

;; -----------------------------------------------------------------------------
;; Parsing

(defmulti read om/dispatch)

(defmethod read :default
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if-let [[_ value] (find st key)]
      {:value value}
      {:value :not-found})))

(defmulti produce-maze-value (fn [k v s] k))

(defn mutate [{:keys [state] :as env} key {:keys [maze-key value] :as params}]
  (if (= 'snergly.core/set-maze key)
    (let [new-value (produce-maze-value maze-key value @state)]
      {:value {:keys {:maze [maze-key]}}
       :action #(swap! state assoc-in [:maze maze-key] new-value)})
    {:value nil}))

(defmethod produce-maze-value :algorithm
  [_ form-value _]
  form-value)

(defmethod produce-maze-value :analysis
  [_ form-value _]
  form-value)

(defmethod produce-maze-value :active
  [_ form-value _]
  form-value)

(defmethod produce-maze-value :grid
  [_ new-value {:keys [maze] :as state}]
  (let [{:keys [algorithm rows columns grid]} maze]
    new-value))

(defmethod produce-maze-value :default
  [_ form-value _]
  (js/parseInt form-value))

;; -----------------------------------------------------------------------------
;; Components

(defn ready-to-go [{:keys [algorithm rows columns grid] :as maze}]
  (and (not= "" algorithm)
       (and (integer? rows) (> rows 1) (< rows 100))
       (and (integer? columns) (> columns 1) (< columns 100))
       (or (nil? grid)
           (not= algorithm (:algorithm-name grid))
           (not= rows (:rows grid))
           (not= columns (:columns grid)))))

(defui MazeDisplay
  static om/IQuery
  (query [this]
    '[{:maze [:grid :rows :columns :cell-size :algorithm :active]}])
  Object
  (componentDidMount [this]
    (let [{:keys [grid cell-size] :as maze} (om/props this)
          c (.-_canvas this)
          g (.getContext c "2d")]
      (image/image-grid g @grid cell-size)
      )
    )
  (componentDidUpdate [this prev-props prev-state]
    (let [{:keys [grid cell-size] :as maze} (om/props this)
          c (.-_canvas this)
          g (.getContext c "2d")]
      (image/image-grid g @grid cell-size)
      )
    )
  (render [this]
    (let [{:keys [grid rows columns algorithm cell-size active] :as maze} (om/props this)]
      (let [height (inc (* cell-size rows))
            width (inc (* cell-size columns))]
        (println (str "Active: " active))
        (dom/div nil
                 (dom/div nil (or active "\u00a0")) ; &nbsp;
                 (dom/canvas #js {:id "c1"
                                  :height height
                                  :width width
                                  :ref #(aset this "_canvas" %)})
                            )))))

(def maze-display (om/factory MazeDisplay))

(defui MazeControlPanel
  static om/IQuery
  (query [this]
    '[:app/algorithms :app/analyses
      {:maze [:algorithm :rows :columns :grid
              :analysis :start-row :start-col :end-row :end-col
              :active]}])
  Object
  (render [this]
    (let [{:keys [app/algorithms app/analyses maze]} (om/props this)
          {:keys [algorithm rows columns grid
                  analysis start-row start-col end-row end-col
                  active]} maze
          modify (fn [maze-key e]
                   (om/transact! this `[(snergly.core/set-maze {:maze-key ~maze-key :value ~(aget e "target" "value")})]))
          go-async (fn [maze e] (run-animation maze))]
      ;; TODO: remember how to disable form elements by group, rather than one-at-a-time.
      ;; TODO: when rows/cols are reduced, adjust analysis params downward if necessary.
      (dom/div
        nil
        (dom/div
          nil
          (dom/label nil
                     "Algorithm: "
                     (dom/select #js {:value algorithm
                                      :disabled active
                                      :onChange (partial modify :algorithm)}
                                 (concat [(dom/option #js {:key ""} "")]
                                         (map
                                           (fn [name]
                                             (dom/option #js {:key name} name))
                                           algorithms)))))
        (dom/div
          nil
          (dom/label nil
                     "Rows: "
                     (dom/input #js {:type "number"
                                     :disabled active
                                     :value rows
                                     :min 2
                                     :max 99
                                     :style #js {:width "30px"}
                                     :onInput (partial modify :rows)}))
          (dom/label nil
                     "Columns: "
                     (dom/input #js {:type "number"
                                     :disabled active
                                     :value columns
                                     :min 2
                                     :max 99
                                     :style #js {:width "30px"}
                                     :onInput (partial modify :columns)})))
        (dom/div
          nil
          (dom/label nil
                     "Analysis: "
                     (dom/select #js {:value analysis
                                      :disabled active
                                      :onChange (partial modify :analysis)}
                                 (map (fn [name] (dom/option #js {:key name} name)) analyses))))
        (when (contains? #{"distances" "path"} analysis)
          (dom/div
            nil
            (dom/label nil
                       "Start Row: "
                       (dom/input #js {:type "number"
                                       :disabled active
                                       :value start-row
                                       :min 0
                                       :max (dec rows)
                                       :style #js {:width "30px"}
                                       :onInput (partial modify :start-row)}))
            (dom/label nil
                       "Start Column: "
                       (dom/input #js {:type "number"
                                       :disabled active
                                       :value start-col
                                       :min 0
                                       :max (dec columns)
                                       :style #js {:width "30px"}
                                       :onInput (partial modify :start-col)}))))
        (when (= "path" analysis)
          (dom/div
            nil
            (dom/label nil
                      "End Row: "
                       (dom/input #js {:type "number"
                                       :disabled active
                                       :value end-row
                                       :min 0
                                       :max (dec rows)
                                       :style #js {:width "30px"}
                                       :onInput (partial modify :end-row)}))
            (dom/label nil
                      "End Column: "
                       (dom/input #js {:type "number"
                                       :disabled active
                                       :value end-col
                                       :min 0
                                       :max (dec columns)
                                       :style #js {:width "30px"}
                                       :onInput (partial modify :end-col)}))))
        (dom/div
          nil
          (dom/button #js {:disabled (or active (not (ready-to-go maze)))
                           :onClick (partial go-async maze)}
                      "Go!"))
        (when grid (dom/div nil (maze-display maze)))
        ))))

(def maze-control-panel (om/factory MazeControlPanel))

;; -----------------------------------------------------------------------------
;; Initialization


(def reconciler
  (om/reconciler {:state init-data
                  :parser (om/parser {:read read :mutate mutate})
                  :logger nil}))

(om/add-root! reconciler
              MazeControlPanel (gdom/getElement "app"))

;; -----------------------------------------------------------------------------
;; Utilities

(defn q [query]
  (let [config (:config reconciler)
        parser (:parser config)]
    (parser config query)))

(defn t! [update]
  (om.next/transact! reconciler update))

(defn h* []
  (.-arr (-> reconciler :config :history)))

(defn -huuid [uuid-or-index]
  (if (integer? uuid-or-index)
    (get (h*) uuid-or-index)
    uuid-or-index))

(defn h
  ([]
   (doseq [uuid (h*)]
     (println (str "#uuid \"" uuid "\"")))
   nil)
  ([uuid-or-index]
   (let [uuid (-huuid uuid-or-index)]
     [uuid (get @(.-index (-> reconciler :config :history)) uuid)])))

(defn h! [uuid-or-index]
  (om/from-history reconciler (-huuid uuid-or-index)))

(defn p [jsobj]
  (goog.object/forEach jsobj (fn [val key obj] (println (str "Key: " key)))))


;; REPL things:
;;
;; Get to the right namespace:
;; (in-ns 'snergly.core)
;;
;; Read:
;; (q [:app/algorithms]
;;
;; Mutate:
;; (t! '[(snergly.core/set-maze {:maze-key :algorithm :value "sidewinder"})])
;;
;; Show all history uuids:
;; (h)
;;
;; Show a history state:
;; (h 2) ; or (h #uuid "9e7160a0-89cc-4482-aba1-7b894a1c54b4")
;;
;; Return to history point:
;; (h! 2) ; or (h! #uuid "9e7160a0-89cc-4482-aba1-7b894a1c54b4")
;;
;; See all the state:
;; @reconciler
;;
;; Find the query for a component:
;; (om/get-query (om/class->any reconciler AnimalsList))
;;
;; Change the query for a component:
;; (om/set-query!
;;   (om/class->any reconciler AnimalsList)
;;   {:params {:start 0 :end 5}})
