(ns datalevin.test.entity
  (:require
    [#?(:cljs cljs.reader :clj clojure.edn) :as edn]
    #?(:cljs [cljs.test :as t :refer-macros [is deftest testing]]
       :clj  [clojure.test :as t :refer [is deftest testing]])
    [datalevin.core :as d]
    [datalevin.server :as s]
    [datalevin.util :as u]
    [datalevin.constants :as c]
    [datalevin.test.core :as tdc])
  (:import [java.util UUID]))

(t/use-fixtures :once tdc/no-namespace-maps)

(deftest test-entity
  (let [db (-> (d/empty-db nil {:aka {:db/cardinality :db.cardinality/many}})
               (d/db-with [{:db/id 1, :name "Ivan", :age 19, :aka ["X" "Y"]}
                           {:db/id 2, :name "Ivan", :sex "male", :aka ["Z"]}
                           [:db/add 3 :huh? false]]))
        e  (d/entity db 1)]
    (is (= (:db/id e) 1))
    (is (identical? (d/entity-db e) db))
    (is (= (:name e) "Ivan"))
    (is (= (e :name) "Ivan"))                               ; IFn form
    (is (= (:age e) 19))
    (is (= (:aka e) #{"X" "Y"}))
    (is (= true (contains? e :age)))
    (is (= false (contains? e :not-found)))
    (is (= (into {} e)
           {:name "Ivan", :age 19, :aka #{"X" "Y"}}))
    (is (= (into {} (d/entity db 1))
           {:name "Ivan", :age 19, :aka #{"X" "Y"}}))
    (is (= (into {} (d/entity db 2))
           {:name "Ivan", :sex "male", :aka #{"Z"}}))
    (let [e3 (d/entity db 3)]
      (is (= (into {} e3) {:huh? false}))                   ; Force caching.
      (is (false? (:huh? e3))))

    (is (= (pr-str (d/entity db 1)) "{:db/id 1}"))
    (is (= (pr-str (let [e (d/entity db 1)] (:unknown e) e)) "{:db/id 1}"))
    ;; read back in to account for unordered-ness
    (is (= (edn/read-string (pr-str (let [e (d/entity db 1)] (:name e) e)))
           (edn/read-string "{:name \"Ivan\", :db/id 1}")))
    (d/close-db db)))

(deftest test-transactable-entity
  (let [db  (-> (d/empty-db nil {:user/handle  #:db {:valueType :db.type/string
                                                     :unique    :db.unique/identity}
                                 :user/friends #:db{:valueType   :db.type/ref
                                                    :cardinality :db.cardinality/many}}
                            {:auto-entity-time? true})
                (d/db-with [{:user/handle  "ava"
                             :user/friends [{:user/handle "fred"}
                                            {:user/handle "jane"}]}]))
        ava (d/entity db [:user/handle "ava"])]
    (testing "cardinality/one"
      (testing "nil attr"
        (is (nil? (:user/age ava))))
      (testing "add/assoc attr"
        (let [ava-with-age (assoc ava :user/age 42)]
          (is (= 42 (:user/age ava-with-age)) "lookup works on tx stage")
          (is (nil? (:user/age ava)) "is immutable")
          (testing "and transact"
            (let [db-ava-with-age        (d/db-with db [(-> ava-with-age
                                                            (d/add :user/foo "bar"))])
                  ava-db-entity-with-age (d/entity db-ava-with-age [:user/handle "ava"])]
              (is (= 42 (:user/age ava-db-entity-with-age)) "value was transacted into db")
              (is (= "bar" (:user/foo ava-db-entity-with-age)) "value was transacted into db")
              (testing "update attr"
                (let [ava-with-age    (update ava-db-entity-with-age :user/age inc)
                      ava-with-points (-> ava-with-age
                                          (assoc :user/points 100)
                                          (update :user/points inc))]
                  (is (= 43 (:user/age ava-with-age)) "update works on entity")
                  (is (= 101 (:user/points ava-with-points)) "update works on stage")
                  (testing "and transact"
                    (let [db-ava-with-age (d/db-with db [ava-with-points])
                          ava-db-entity   (d/entity db-ava-with-age [:user/handle "ava"])]
                      (is (= 43 (:user/age ava-db-entity)) "value was transacted into db")
                      (is (= 101 (:user/points ava-db-entity)) "value was transacted into db")))))))))
      (testing "retract/dissoc attr"
        (let [ava        (d/entity db [:user/handle "ava"])
              dissoc-age (-> ava
                             (dissoc :user/age)
                             (d/retract :user/foo))]
          (is (= 43 (:user/age ava)) "has age")
          (is (nil? (:user/age dissoc-age)))
          (is (nil? (:user/foo dissoc-age)))
          (testing "and transact"
            (let [db-ava-with-age      (d/db-with db [(dissoc ava :user/age)])
                  ava-db-entity-no-age (d/entity db-ava-with-age [:user/handle "ava"])]
              (is (nil? (:user/age ava-db-entity-no-age)) "attrs was retracted from db"))))))

    (testing "cardinality/many"
      (testing "add/retract"
        (let [find-fred             (fn [ent]
                                      (some
                                        #(when (= (:user/handle %) "fred") %)
                                        (:user/friends ent)))
              fred                  (find-fred ava)
              ava-no-fred-friend    (d/retract ava :user/friends fred)
              db-no-fred            (d/db-with db [ava-no-fred-friend])
              ava-db-no-fred-friend (d/entity db-no-fred [:user/handle "ava"])]
          (is (some? fred) "fred is a friend")
          (is (nil? (find-fred ava-db-no-fred-friend)) "fred is not a friend anymore :(")
          ;; ava and fred make up
          (let [ava-friends-with-fred (d/add ava-db-no-fred-friend :user/friends fred)]
            ; tx-stage does not handle cardinality properly yet:
            ;(is (some? (find-fred ava-friends-with-fred))) ;; fails
            (let [db-with-friends (d/db-with db [ava-friends-with-fred])
                  ava             (d/entity db-with-friends [:user/handle "ava"])]
              (is (some? (find-fred ava)) "officially friends again"))))))

    (d/close-db db)))

(deftest test-entity-refs
  (let [db (-> (d/empty-db nil {:father   {:db/valueType :db.type/ref}
                                :children {:db/valueType   :db.type/ref
                                           :db/cardinality :db.cardinality/many}})
               (d/db-with
                 [{:db/id 1, :children [10]}
                  {:db/id 10, :father 1, :children [100 101]}
                  {:db/id 100, :father 10}
                  {:db/id 101, :father 10}]))
        e  #(d/entity db %)]

    (is (= (:children (e 1)) #{(e 10)}))
    (is (= (:children (e 10)) #{(e 100) (e 101)}))

    (testing "empty attribute"
      (is (= (:children (e 100)) nil)))

    (testing "nested navigation"
      (is (= (-> (e 1) :children first :children) #{(e 100) (e 101)}))
      (is (= (-> (e 10) :children first :father) (e 10)))
      (is (= (-> (e 10) :father :children) #{(e 10)}))

      (testing "after touch"
        (let [e1  (e 1)
              e10 (e 10)]
          (d/touch e1)
          (d/touch e10)
          (is (= (-> e1 :children first :children) #{(e 100) (e 101)}))
          (is (= (-> e10 :children first :father) (e 10)))
          (is (= (-> e10 :father :children) #{(e 10)})))))

    (testing "backward navigation"
      (is (= (:_children (e 1)) nil))
      (is (= (:_father (e 1)) #{(e 10)}))
      (is (= (:_children (e 10)) #{(e 1)}))
      (is (= (:_father (e 10)) #{(e 100) (e 101)}))
      (is (= (-> (e 100) :_children first :_children) #{(e 1)}))
      )
    (d/close-db db)))

(deftest test-entity-misses
  (let [db (-> (d/empty-db nil {:name {:db/unique :db.unique/identity}})
               (d/db-with [{:db/id 1, :name "Ivan"}
                           {:db/id 2, :name "Oleg"}]))]
    (is (nil? (d/entity db nil)))
    (is (nil? (d/entity db "abc")))
    (is (nil? (d/entity db :keyword)))
    (is (nil? (d/entity db [:name "Petr"])))
    (is (= 777 (:db/id (d/entity db 777))))
    (is (thrown-msg? "Lookup ref attribute should be marked as :db/unique: [:not-an-attr 777]"
                     (d/entity db [:not-an-attr 777])))
    (d/close-db db)))

(deftest test-transactable-entity-with-remote-store
  (let [server (s/create {:port c/default-port
                          :root (u/tmp-dir
                                  (str "entity-test-" (UUID/randomUUID)))})
        _      (s/start server)
        db     (-> (d/empty-db "dtlv://datalevin:datalevin@localhost/entity-test"
                               {:user/handle  #:db {:valueType :db.type/string
                                                    :unique    :db.unique/identity}
                                :user/friends #:db{:valueType   :db.type/ref
                                                   :cardinality :db.cardinality/many}}
                               {:auto-entity-time? true})
                   (d/db-with [{:user/handle  "ava"
                                :user/friends [{:user/handle "fred"}
                                               {:user/handle "jane"}]}]))
        ava    (d/entity db [:user/handle "ava"])]
    (testing "cardinality/one"
      (testing "nil attr"
        (is (nil? (:user/age ava))))
      (testing "add/assoc attr"
        (let [ava-with-age (assoc ava :user/age 42)]
          (is (= 42 (:user/age ava-with-age)) "lookup works on tx stage")
          (is (nil? (:user/age ava)) "is immutable")
          (testing "and transact"
            (let [db-ava-with-age        (d/db-with db [(-> ava-with-age
                                                            (d/add :user/foo "bar"))])
                  ava-db-entity-with-age (d/entity db-ava-with-age [:user/handle "ava"])]
              (is (= 42 (:user/age ava-db-entity-with-age)) "value was transacted into db")
              (is (= "bar" (:user/foo ava-db-entity-with-age)) "value was transacted into db")
              (testing "update attr"
                (let [ava-with-age    (update ava-db-entity-with-age :user/age inc)
                      ava-with-points (-> ava-with-age
                                          (assoc :user/points 100)
                                          (update :user/points inc))]
                  (is (= 43 (:user/age ava-with-age)) "update works on entity")
                  (is (= 101 (:user/points ava-with-points)) "update works on stage")
                  (testing "and transact"
                    (let [db-ava-with-age (d/db-with db [ava-with-points])
                          ava-db-entity   (d/entity db-ava-with-age [:user/handle "ava"])]
                      (is (= 43 (:user/age ava-db-entity)) "value was transacted into db")
                      (is (= 101 (:user/points ava-db-entity)) "value was transacted into db")))))))))
      (testing "retract/dissoc attr"
        (let [ava        (d/entity db [:user/handle "ava"])
              dissoc-age (-> ava
                             (dissoc :user/age)
                             (d/retract :user/foo))]
          (is (= 43 (:user/age ava)) "has age")
          (is (nil? (:user/age dissoc-age)))
          (is (nil? (:user/foo dissoc-age)))
          (testing "and transact"
            (let [db-ava-with-age      (d/db-with db [(dissoc ava :user/age)])
                  ava-db-entity-no-age (d/entity db-ava-with-age [:user/handle "ava"])]
              (is (nil? (:user/age ava-db-entity-no-age)) "attrs was retracted from db"))))))

    (testing "cardinality/many"
      (testing "add/retract"
        (let [find-fred             (fn [ent]
                                      (some
                                        #(when (= (:user/handle %) "fred") %)
                                        (:user/friends ent)))
              fred                  (find-fred ava)
              ava-no-fred-friend    (d/retract ava :user/friends fred)
              db-no-fred            (d/db-with db [ava-no-fred-friend])
              ava-db-no-fred-friend (d/entity db-no-fred [:user/handle "ava"])]
          (is (some? fred) "fred is a friend")
          (is (nil? (find-fred ava-db-no-fred-friend)) "fred is not a friend anymore :(")
          ;; ava and fred make up
          (let [ava-friends-with-fred (d/add ava-db-no-fred-friend :user/friends fred)]
            ; tx-stage does not handle cardinality properly yet:
            ;(is (some? (find-fred ava-friends-with-fred))) ;; fails
            (let [db-with-friends (d/db-with db [ava-friends-with-fred])
                  ava             (d/entity db-with-friends [:user/handle "ava"])]
              (is (some? (find-fred ava)) "officially friends again"))))))

    (d/close-db db)
    (s/stop server)))

(deftest test-entity-equality
  (let [db1 (-> (d/empty-db)
                (d/db-with [{:db/id 1, :name "Ivan"}]))
        e1  (d/entity db1 1)
        db2 (d/db-with db1 [])
        db3 (d/db-with db2 [{:db/id 2, :name "Oleg"}])]

    (testing "Two entities are equal if they have the same :db/id"
      (is (= e1 e1))
      (is (= e1 (d/entity db1 1)))

      (testing "and refer to the same database"
        (is (not= e1 (d/entity db2 1)))
        (is (not= e1 (d/entity db3 1)))))))
