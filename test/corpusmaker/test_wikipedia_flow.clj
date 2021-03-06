(ns corpusmaker.test-wikipedia-flow
  (:use clojure.test
    cascading.clojure.io)
  (:require (cascading.clojure [api :as c])
    (clojure.contrib [duck-streams :as ds])
    (clojure.contrib [java-utils :as ju])
    (clj-json [core :as json])
    (corpusmaker [wikipedia :as w])))

(def *sample-dumpfile* "test/enwiki-20090902-pages-articles-sample.xml")

(defn up-title
  {:fn> ["up-title" "start-markup"]}
  [title markup]
  [(.toUpperCase title) (-> markup (.replace "\n" " ") (.substring 0 30))])

(deftest test-wikipedia-source
  ;; parse wikipedia XML dump to extract title and markup into transformed text lines
  (with-log-level :warn
    (with-tmp-files [sink-path (temp-path "corpusmaker-test-sink")]
      ;; create a flow from wikipedia raw format to text lines
      (let [flow
            (c/flow
              {"wikipedia" (w/wikipedia-tap *sample-dumpfile*)}
              (c/lfs-tap (c/text-line ["up-title" "start-markup"]) sink-path)
              (->
                (c/pipe "wikipedia")
                (c/map #'up-title :< ["title" "markup"] :fn> ["up-title" "start-markup"])))]
        ;; run the flow
        (c/exec flow)
        ;; parse check the output text file contents in the sink folder
        (let [output-lines (ds/read-lines (ju/file sink-path "part-00000"))]
          (is (= 4 (.size output-lines)))
          (is (= "ACCESSIBLECOMPUTING\t#REDIRECT [[Computer accessibi" (nth output-lines 0)))
          (is (= "ANARCHISM\t{{pp-move-indef}} {{Anarchism " (nth output-lines 1)))
          (is (= "AFGHANISTANHISTORY\t#REDIRECT [[History of Afghani" (nth output-lines 2)))
          (is (= "AUTISM\t<!-- NOTES: 1) Please follow t" (nth output-lines 3))))))))

(deftest test-filter-redirect
  (with-log-level :warn
    (with-tmp-files [sink-path (temp-path "corpusmaker-test-sink")]
      (let [flow
            (c/flow
              {"wikipedia" (w/wikipedia-tap *sample-dumpfile*)}
              (c/lfs-tap (c/text-line ["title"]) sink-path)
              (->
                (c/pipe "wikipedia")
                (c/filter #'w/no-redirect? :< "markup")))]
        (c/exec flow)
        (let [output-lines (ds/read-lines (ju/file sink-path "part-00000"))]
          (is (= 2 (.size output-lines)))
          (is (= "Anarchism" (first output-lines)))
          (is (= "Autism" (second output-lines))))))))

(deftest test-unigrams
  (with-log-level :warn
    (with-tmp-files [sink-path (temp-path "corpusmaker-test-sink")]
      (let [flow
            (c/flow
              {"wikipedia" (w/wikipedia-tap
                             *sample-dumpfile*
                             ["title", "markup"])}
              (c/lfs-tap (c/text-line ["title", "unigram"]) sink-path)
              (->
                (c/pipe "wikipedia")
                (c/filter #'w/no-redirect? :< "markup")
                (c/map #'w/parse-markup
                  :< "markup"
                  :fn> ["text", "links", "categories"]
                  :> ["title" "text"])
                (c/mapcat #'w/tokenize-text
                  :< "text"
                  :fn> "unigram"
                  :> ["title" "unigram"])))]
        (c/exec flow)
        (let [output-lines (ds/read-lines (ju/file sink-path "part-00000"))]
          (is (= 13087 (.size output-lines)))
          (is (= "Anarchism\tanarchism" (nth output-lines 0)))
          (is (= "Anarchism\tis" (nth output-lines 1)))
          (is (= "Anarchism\ta" (nth output-lines 2)))
          (is (= "Anarchism\tpolitical" (nth output-lines 3))))))))
