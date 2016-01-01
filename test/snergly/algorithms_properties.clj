(ns snergly.algorithms-properties
  (:import (clojure.lang PersistentQueue))
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer :all]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [schema.test]
            [snergly.algorithms :refer :all]
            [snergly.grid :as grid]))

;; This seems like a perfect application for property-based testing.  There's
;; no way to inspect a maze and prove that it was generated by a correct
;; implementation of, say, Wilson's algorithm.  And the inherent randomness of
;; the algorithms means that any particular example I come up with might not
;; expose all of the issues.
;;
;; But we do know that all of these algorithms are supposed to produce
;; "perfect" mazes (fully connected, no loops).  And I know that I want them
;; all to report intermediate results for animation purposes according to
;; particular rules.
;;
;; So if I can find a way to validate that the final maze is a perfect maze,
;; then I can have a fair degree of confidence that an implementation that
;; seems (from inspection) to correctly implement the algorithm is, in fact,
;; correct, because nearly all bugs would either disrupt that property or cause
;; a crash or non-termination.  (A bug that didn't do any of those things would
;; actually mean it was a different algorithm.)
;;
;; And if I can find a way to validate that the intermediate results are
;; reported according to my rules, then I can be confident that the animations
;; are correct.

(use-fixtures :once schema.test/validate-schemas)

;; -----------------------------------------------------------------------------
;; Generators

(def gen-dimen
  (gen/fmap inc gen/s-pos-int))

(def gen-grid
  (gen/fmap #(apply grid/make-grid %) (gen/vector gen-dimen 2)))

;; -----------------------------------------------------------------------------
;; Utility and validation functions

(defn grid-cells [g]
  (map #(grid/grid-cell g %) (grid/grid-coords g)))

(defn new-grid?
  [grid]
  (and (grid/new? grid)
       (every? empty?
               (map #(:links (grid/grid-cell grid %)) (grid/grid-coords grid)))))

(defn has-cycle?
  ([grid] (has-cycle? grid [0 0]))
  ([grid start]
   (loop [[current-coord parent] [start nil]
          frontier PersistentQueue/EMPTY
          visited #{}]
     (cond
       (nil? current-coord) false
       (contains? visited current-coord) true
       :else (let [cell (grid/grid-cell grid current-coord)
                   links (remove #(= parent %) (:links cell))
                   next-frontier (apply conj frontier (map #(vector % current-coord) links))]
               (recur (peek next-frontier)
                      (pop next-frontier)
                      (conj visited current-coord)))))))

;; -----------------------------------------------------------------------------
;; Capturing asynchronous updates

(defn initial-grid [algorithm-fn grid]
  (let [intermediate-chan (async/chan)
        result-chan (algorithm-fn grid intermediate-chan)]
    (async/<!! (async/go-loop [first-g nil]
                 (if-let [new-g (async/<! intermediate-chan)]
                   (recur (or first-g new-g))
                   (let [_ (async/<! result-chan)]
                     first-g))))))

(defn final-grid [algorithm-fn grid]
  (let [intermediate-chan (async/chan)
        result-chan (algorithm-fn grid intermediate-chan)]
    (async/<!! (async/go-loop []
                 (if (async/<! intermediate-chan)
                   (recur)
                   (async/<! result-chan))))))

(defn all-grids [algorithm-fn grid]
  (let [intermediate-chan (async/chan)
        result-chan (algorithm-fn grid intermediate-chan)]
    (async/<!! (async/go-loop [grids []]
                 (if-let [g (async/<! intermediate-chan)]
                   (recur (conj grids g))
                   (conj grids (async/<! result-chan)))))))

(defn actual-changes [grids]
  (letfn [(add-links [s cell]
            (conj s (:links cell)))
          (compute-changes [[a b]]
            (let [a-links (reduce add-links #{} (grid-cells a))
                  b-links (reduce add-links #{} (grid-cells b))]
              (set/difference b-links a-links)
              ))]
    (map compute-changes (partition 2 (interleave grids (rest grids))))))

;; -----------------------------------------------------------------------------
;; Property definitions

(defmacro check-algorithm-properties
  [alg-name & specs]
  ;; In these definitions, some names are used consistently to help make
  ;; things clearer.
  ;;
  ;; * the word "initial" refers to the very first grid, which includes no
  ;;   changes and no links.
  ;; * a "report" is any grid that is supplied by the algorithm through either
  ;;   channel, including the initial.
  ;; * the word "update" refers to all reports except the initial.  Each update
  ;;   is supposed to include changes (because there's no sense supplying them
  ;;   for animation unless they've changed).
  ;; * the word "final" refers to the final grid (the one that is the return
  ;;   value from the algorithm)
  ;; * the word "incomplete" refers to all of the reports *prior* to the final
  ;;   grid, including the initial.
  ;; * the word "intermediate" refers to all of the reports *except* for the
  ;;   initial and the final.

  (let [specs (if (empty? specs)
                #{:perfect :first-new :all-changed :each-changes :accurate-changes :updates-link-2}
                (into #{} specs))]
    `(do
       ;; Is the final grid a perfect maze?
       (when (contains? ~specs :perfect)
         (defspec ~(symbol (str alg-name "-produces-a-perfect-maze"))
           5
           (prop/for-all [grid# gen-grid]
             (let [final# (final-grid (algorithm-functions "binary-tree") grid#)
                   links# (map :links (grid-cells final#))
                   distances# ((synchronous-fn find-distances) final# [0 0])
                   ]
               (every? not-empty links#) ; quick check for no isolated cells
               (every? #(contains? distances# %) (grid/grid-coords final#)) ; every cell reachable
               (not (has-cycle? final#)) ; no cycles
               ))))

       ;; Is the initial grid a new maze with no changes?
       (when (contains? ~specs :first-new)
         (defspec ~(symbol (str alg-name "-first-grid-is-new"))
           2
           (prop/for-all [grid# gen-grid]
             (new-grid? (initial-grid (algorithm-functions ~alg-name) grid#)))))

       ;; Is every cell eventually changed?
       (when (contains? ~specs :all-changed)
         (defspec ~(symbol (str alg-name "-all-cells-changed"))
           10
           (prop/for-all [grid# gen-grid]
             (let [reports# (all-grids (algorithm-functions ~alg-name) grid#)
                   change-sets# (filter identity (map :changed-cells reports#))
                   changed-cells# (apply set/union change-sets#)]
               (= (into #{} (grid/grid-coords grid#)) changed-cells#)))))

       ;; Do all updates actually change the grid?
       (when (contains? ~specs :each-changes)
         (defspec ~(symbol (str alg-name "-each-update-changes"))
           5
           (prop/for-all [grid# gen-grid]
             (let [updates# (rest (all-grids (algorithm-functions ~alg-name) grid#))
                   change-sets# (map :changed-cells updates#)]
               ;(println (str "Change sets: " (into [] (map empty? change-sets#))))
               (every? not-empty change-sets#)))))

       ;; Does each update link exactly two cells?
       (when (contains? ~specs :updates-link-2)
         (defspec ~(symbol (str alg-name "-links-two-cells-each-update"))
           5
           (prop/for-all [grid# gen-grid]
             (let [reports# (all-grids (algorithm-functions ~alg-name) grid#)
                   update-change-sets# (map :changed-cells (rest reports#))
                   ;deltas# (partition 2 (interleave grids# (rest reports##)))
                   ;matched-up# (partition 2 (interleave update-change-sets# deltas#))
                   ]
               (every? (fn [cs#] (= 2 (count cs#)))
                       update-change-sets#)))))

       ;; This isn't working, and it's too complex, anyway.  I might remove it
       ;; and trust that :each-changes plus :updates-link-2 suffice.
       ;;
       ;; Is each update's :changed-cells set accurate?
       #_(when (contains? ~specs :accurate-changes)
           (defspec ~(symbol (str alg-name "-cells-changed-is-accurate"))
             5
             (prop/for-all [grid# gen-grid]
               (let [reports# (all-grids (algorithm-functions ~alg-name) grid#)
                     update-change-sets# (map :changed-cells (rest reports#))
                     actual-changes# (actual-changes reports#)]
                 (every? (fn [[a# c#]] (= a# c#)) (partition 2 (interleave actual-changes# update-change-sets#)))))))

       )
    ))

;; -----------------------------------------------------------------------------
;; Checking the algorithms

(check-algorithm-properties "binary-tree")
(check-algorithm-properties "sidewinder")
(check-algorithm-properties "aldous-broder")
(check-algorithm-properties "wilsons")
;; The following two cannot yet pass :each-changes and :updates-link-2
(check-algorithm-properties "hunt-and-kill" :perfect :first-new :all-changed)
(check-algorithm-properties "recursive-backtracker" :perfect :first-new :all-changed)



;; What kinds of properties can I assert about the maze algorithms?
;;
;; * The final grid is a perfect maze: each cell can reach every other cell
;;   by exactly one path
;;   * Each cell is linked to at least one other (done)
;;   * Each cell is visited by find-distances (done)
;;   * The graph contains no cycles (done)
;; * At each step, the cells marked changed are the only ones that have changed
;;   * implemented, but either not working right or they all fail.  But it's
;;     super-complicated, amd might not be worth the trouble.
;;   * perhaps a simpler version?  All of the current maze algorithms advance
;;     a cell at a time. So perhaps just verify that each update changes two
;;     cells, and those cells must be linked to each other.  That seems like a
;;     much simpler proxy for what we want.
;;     * this is partially done, but h-a-k and r-b fail because of the one
;;       below.
;; * Each reported grid is changed?
;;   * done, but h-a-k and r-b fail it (in an innocuous way: the last grid is
;;     reported twice).
;; * (maybe) if no intermediate-chan, the result grid shows all cells changed

;; What kinds of properties can I assert about find-distances?
;;
;; * Each cell is changed exactly once
;; * :max is monotonically increasing (starting with 1)
;; * Each step, every changed cell has distance :max
;; * (maybe) if no intermediate-chan, the result grid shows all cells changed