(ns datalevin.search-test
  (:require [datalevin.search :as sut]
            [datalevin.lmdb :as l]
            [datalevin.sparselist :as sl]
            [datalevin.util :as u]
            [clojure.string :as s]
            [clojure.test :refer [is deftest testing]])
  (:import [java.util UUID ]
           [datalevin.sparselist SparseIntArrayList]
           [datalevin.search SearchEngine IndexWriter]))

(deftest english-analyzer-test
  (let [s1 "This is a Datalevin-Analyzers test"
        s2 "This is a Datalevin-Analyzers test. "]
    (is (= (sut/en-analyzer s1)
           (sut/en-analyzer s2)
           [["datalevin-analyzers" 3 10] ["test" 4 30]]))
    (is (= (subs s1 10 (+ 10 (.length "datalevin-analyzers")))
           "Datalevin-Analyzers" ))))

(defn- add-docs
  [f engine]
  (f engine :doc0 "")
  (f engine :doc1 "The quick red fox jumped over the lazy red dogs.")
  (f engine :doc2 "Mary had a little lamb whose fleece was red as fire.")
  (f engine :doc3 "Moby Dick is a story of a whale and a man obsessed.")
  (f engine :doc4 "The robber wore a red fleece jacket and a baseball cap.")
  (f engine :doc5
     "The English Springer Spaniel is the best of all red dogs I know."))

(deftest blank-analyzer-test
  (let [blank-analyzer (fn [^String text]
                         (map-indexed (fn [i ^String t]
                                        [t i (.indexOf text t)])
                                      (s/split text #"\s")))
        lmdb           (l/open-kv (u/tmp-dir (str "analyzer-" (UUID/randomUUID))))
        engine         ^SearchEngine (sut/new-search-engine
                                       lmdb {:analyzer blank-analyzer})]
    (add-docs sut/add-doc engine)
    (is (= [[:doc1 [["dogs." [43]]]]]
           (sut/search engine "dogs." {:display :offsets})))
    (is (= [:doc5] (sut/search engine "dogs")))))

(deftest index-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "index-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-search-engine lmdb)]

    (is (= (sut/doc-count engine) 0))
    (is (not (sut/doc-indexed? engine :doc4)))
    (is (= (sut/doc-refs engine) []))

    (add-docs sut/add-doc engine)

    (is (sut/doc-indexed? engine :doc4))
    (is (not (sut/doc-indexed? engine :non-existent)))
    (is (not (sut/doc-indexed? engine "non-existent")))

    (is (= (sut/doc-count engine) 5))
    (is (= (sut/doc-refs engine) [:doc1 :doc2 :doc3 :doc4 :doc5]))

    (let [[tid mw ^SparseIntArrayList sl]
          (l/get-value lmdb (.-terms-dbi engine) "red" :string :term-info true)]
      (is (= (l/range-count lmdb (.-terms-dbi engine) [:all] :string) 32))
      (is (= (l/get-value lmdb (.-terms-dbi engine) "red" :string :int) tid))

      (is (sl/contains-index? sl 1))
      (is (sl/contains-index? sl 5))
      (is (= (sl/size sl) 4))
      (is (= (seq (.-indices sl)) [1 2 4 5]))

      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 1] :int-int)
             (sl/get sl 1)
             2))
      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 2] :int-int)
             (sl/get sl 2)
             1))

      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 3] :int-int)
             0))
      (is (nil? (sl/get sl 3)))

      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 4] :int-int)
             (sl/get sl 4)
             1))
      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 5] :int-int)
             (sl/get sl 5)
             1))

      (is (= (l/get-list lmdb (.-positions-dbi engine) [tid 5] :int-int :int-int)
             [[9 48]]))

      (is (= (l/get-value lmdb (.-docs-dbi engine) 1 :int :doc-info true) [7 :doc1]))
      (is (= (l/get-value lmdb (.-docs-dbi engine) 2 :int :doc-info true) [8 :doc2]))
      (is (= (l/get-value lmdb (.-docs-dbi engine) 3 :int :doc-info true) [6 :doc3]))
      (is (= (l/get-value lmdb (.-docs-dbi engine) 4 :int :doc-info true) [7 :doc4]))
      (is (= (l/get-value lmdb (.-docs-dbi engine) 5 :int :doc-info true) [9 :doc5]))
      (is (= (l/range-count lmdb (.-docs-dbi engine) [:all]) 5))

      (sut/remove-doc engine :doc1)

      (is (= (sut/doc-count engine) 4))
      (is (= (sut/doc-refs engine) [:doc2 :doc3 :doc4 :doc5]))

      (let [[tid mw ^SparseIntArrayList sl]
            (l/get-value lmdb (.-terms-dbi engine) "red" :string :term-info true)]
        (is (not (sut/doc-indexed? engine :doc1)))
        (is (= (l/range-count lmdb (.-docs-dbi engine) [:all]) 4))
        (is (not (sl/contains-index? sl 1)))
        (is (= (sl/size sl) 3))
        (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 1] :int-id) 0))
        (is (nil? (l/get-list lmdb (.-positions-dbi engine) [tid 1] :int-id :int-int))))

      (sut/clear-docs engine)
      (is (= (sut/doc-count engine) 0)))
    (l/close-kv lmdb)))

(deftest search-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-search-engine lmdb)]
    (add-docs sut/add-doc engine)

    (is (= (sut/search engine "red fox") [:doc1 :doc4 :doc2 :doc5]))
    (is (= (sut/search engine "cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "notaword cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "fleece" {:display :offsets})
           [[:doc4 [["fleece" [22]]]] [:doc2 [["fleece" [29]]]]]))
    (is (= (sut/search engine "red fox" {:display :offsets})
           [[:doc1 [["fox" [14]] ["red" [10 39]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]
            [:doc5 [["red" [48]]]]]))
    (is (= (sut/search engine "red fox" {:doc-filter #(not= % :doc2)})
           [:doc1 :doc4 :doc5]))
    (is (= (sut/search engine "red dogs" {:display :offsets})
           [[:doc1 [["dogs" [43]] ["red" [10 39]]]]
            [:doc5 [["dogs" [52]] ["red" [48]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]]))
    (is (empty? (sut/search engine "solar")))
    (is (empty? (sut/search engine "solar wind")))
    (is (= (sut/search engine "solar cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (l/close-kv lmdb)))

(deftest search-143-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-143-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-search-engine lmdb)]

    (sut/add-doc engine 1 "a tent")
    (sut/add-doc engine 2 "tent")

    (is (= (sut/doc-count engine) 2))
    (is (= (sut/doc-refs engine) [1 2]))

    (let [[tid mw ^SparseIntArrayList sl]
          (l/get-value lmdb (.-terms-dbi engine) "tent" :string :term-info true)]
      (is (= (l/range-count lmdb (.-terms-dbi engine) [:all] :string) 1))
      (is (= (l/get-value lmdb (.-terms-dbi engine) "tent" :string :int) tid))
      (is (= mw 1.0))

      (is (sl/contains-index? sl 1))
      (is (= (sl/size sl) 2))
      (is (= (seq (.-indices sl)) [1 2]))

      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 1] :int-int)
             (sl/get sl 1)
             1))
      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 2] :int-int)
             (sl/get sl 2)
             1))

      (is (= (l/list-count lmdb (.-positions-dbi engine) [tid 3] :int-int)
             0))
      (is (nil? (sl/get sl 3)))

      (is (= (l/get-list lmdb (.-positions-dbi engine) [tid 1] :int-int :int-int)
             [[1 2]]))

      (is (= (l/get-value lmdb (.-docs-dbi engine) 1 :int :doc-info true) [1 1]))
      (is (= (l/get-value lmdb (.-docs-dbi engine) 2 :int :doc-info true) [1 2]))
      (is (= (l/range-count lmdb (.-docs-dbi engine) [:all]) 2))
      )

    (is (= (sut/search engine "tent") [2 1]))
    (l/close-kv lmdb)))

(deftest multi-domains-test
  (let [lmdb    (l/open-kv (u/tmp-dir (str "search-multi" (UUID/randomUUID))))
        engine1 ^SearchEngine (sut/new-search-engine lmdb)
        engine2 ^SearchEngine (sut/new-search-engine lmdb {:domain "another"})]
    (sut/add-doc engine1 1 "hello world")
    (sut/add-doc engine1 2 "Mars is a red planet")
    (sut/add-doc engine1 3 "Earth is a blue planet")
    (add-docs sut/add-doc engine2)

    (is (empty? (sut/search engine1 "solar")))
    (is (empty? (sut/search engine2 "solar")))
    (is (= (sut/search engine1 "red") [2]))
    (is (= (sut/search engine2 "red") [:doc1 :doc4 :doc2 :doc5]))
    (l/close-kv lmdb)))

(deftest index-writer-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-" (UUID/randomUUID))))
        writer ^IndexWriter (sut/search-index-writer lmdb)]
    (add-docs sut/write writer)
    (sut/commit writer)

    (let [engine (sut/new-search-engine lmdb)]
      (is (= (sut/search engine "cap" {:display :offsets})
             [[:doc4 [["cap" [51]]]]])))
    (l/close-kv lmdb)))

(deftest search-kv-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-kv-" (UUID/randomUUID))))
        engine (sut/new-search-engine lmdb)]
    (l/open-dbi lmdb "raw")
    (l/transact-kv
      lmdb
      [[:put "raw" 1 "The quick red fox jumped over the lazy red dogs."]
       [:put "raw" 2 "Mary had a little lamb whose fleece was red as fire."]
       [:put "raw" 3 "Moby Dick is a story of a whale and a man obsessed."]])
    (doseq [i [1 2 3]]
      (sut/add-doc engine i (l/get-value lmdb "raw" i)))

    (is (not (sut/doc-indexed? engine 0)))
    (is (sut/doc-indexed? engine 1))

    (is (= 3 (sut/doc-count engine)))
    (is (= [1 2 3] (sut/doc-refs engine)))

    (is (= (sut/search engine "lazy") [1]))
    (is (= (sut/search engine "red" ) [1 2]))
    (is (= (sut/search engine "red" {:display :offsets})
           [[1 [["red" [10 39]]]] [2 [["red" [40]]]]]))
    (testing "update"
      (sut/add-doc engine 1 "The quick fox jumped over the lazy dogs.")
      (is (= (sut/search engine "red" ) [2])))
    (testing "parallel update"
      (dorun (pmap #(sut/add-doc engine %1 %2)
                   [2 1 4]
                   ["May has a little lamb."
                    "The quick red fox jumped over the lazy dogs."
                    "do you know the game truth or dare <p>What's your biggest fear? I want to see if you could tell me the truth :-)</p>"]))
      (is (= (sut/search engine "red" ) [1]))
      (is (= (sut/search engine "truth" ) [4])))

    (testing "duplicated docs"
      (sut/add-doc engine 5
                   "Pricing how much is the price for each juji Whats the price of using juji for classes Hello, what is the price of Juji? <p>You can create me or any of my brothers and sisters FREE. You can also chat with us privately FREE, as long as you want.</p><p><br></p><p>If you wish to make me or any of my sisters or brothers public so we can chat with other folks, I'd be happy to help you find the right price package.</p>")
      (sut/add-doc engine 4
                   "do you know the game truth or dare <p>What's your biggest fear? I want to see if you could tell me the truth :-)")
      (is (= (sut/search engine "truth" ) [4])))

    (l/close-kv lmdb)))
