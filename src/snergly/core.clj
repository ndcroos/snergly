(ns snergly.core
  (:refer-clojure :exclude [parse-opts])
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [snergly.algorithms :refer :all]
            [snergly.grid :as grid])
  (:import [javax.imageio ImageIO]
           [java.io File]))

(def algorithms
  #{"binary-tree"
    "sidewinder"})

(defn parse-grid-size [spec]
  (condp re-matches spec
    #"^(\d{1,5})$"
    :>> (fn [match] (Integer/parseInt (match 1)))
    #"^(\d{1,5})x(\d{1,5})$"
    :>> (fn [match] (map #(Integer/parseInt %) (rest match)))
    (throw (Exception. "Grid size should be an integer (for a square grid) or a ROWSxCOLUMNS spec (e.g., 10x20)"))))

(defn usage [options-summary algorithms]
  (->> ["Snergly: run and print a maze generation algorithm."
        ""
        "Usage: lein run [options] algorithm"
        ""
        "Options:"
        options-summary
        ""
        "Algorithms:"
        (string/join \newline (map #(str "  " %) algorithms))
        "  all"]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(def cli-options
  [["-c" "--cell-size PIXELS" "Size of maze cells. Ignored when rendering as text."
    :default 10
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 256) "Must be a number between 0 and 256"]]
   ["-h" "--help"]
   ["-o" "--output FILENAME" "Write output to an image file (format defined by extension)"]
   ["-s" "--size DIMENS" "Grid size (e.g. 5 or 8x5)"
    :default [5 5]
    :parse-fn parse-grid-size
    :validate [(fn [[rows columns]]
                 (let [in-range #(< 1 % 10000)]
                   (and (in-range rows)
                        (in-range columns)))) "Grid dimensions must be numbers between 1 and 10,000"]]])

(defn alg-fn [name]
  (ns-resolve 'snergly.algorithms (symbol (str "maze-" name))))

(defn run-and-render [algorithm-name grid-size render-fn]
  (let [algorithm (alg-fn algorithm-name)]
    (render-fn (algorithm (apply grid/make-grid grid-size)))))

(defn write-grid-to-terminal [grid]
  (println (str "generated with " (:algorithm-name grid)))
  (grid/print-grid grid)
  (println))

(defn file-extension [filename]
  (re-find #"(?<=\.)[^.]+$" filename))

(defn write-grid-to-image-file [grid ^String filename cell-size]
  (let [image (grid/image-grid grid cell-size)
        format (file-extension filename)]
    (ImageIO/write image format (File. filename))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary algorithms))
      (not= (count arguments) 1) (exit 1 (usage summary algorithms))
      errors (exit 1 (error-msg errors))
      (and (= (first arguments) "all")
              (:output options)) (exit 1 (error-msg ["You can only write one maze to an output file; do not use '--output' and 'all' at the same time."])))
    ;; Execute program with options
    (let [algorithm-name (first arguments)
          output-file (:output options)
          render-fn (if output-file
                      #(write-grid-to-image-file % output-file (:cell-size options))
                      write-grid-to-terminal)]
      (cond
        (contains? algorithms algorithm-name)
        (run-and-render algorithm-name (:size options) render-fn)
        (= "all" algorithm-name)
        (doseq [algorithm-name algorithms]
          (run-and-render algorithm-name (:size options) render-fn))
        :else
        (println (str "not running unknown algorithm " algorithm-name))))))
