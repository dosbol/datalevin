(ns datalevin.test.conn
  (:require
   #?(:cljs [cljs.test    :as t :refer-macros [is deftest]]
      :clj  [clojure.test :as t :refer        [is deftest]])
   [datalevin.core :as d]
   [datalevin.db :as db]
   [datalevin.constants :as c]
   [datalevin.util :as u])
  (:import [java.util Date UUID]))

(deftest test-close
  (let [conn (d/create-conn)]
    (is (not (d/closed? conn)))
    (d/close conn)
    (is (d/closed? conn))))

(deftest test-update-schema
  (let [conn1 (d/create-conn)
        s     {:a/b {:db/valueType :db.type/string}}
        s1    {:c/d {:db/valueType :db.type/string}}
        txs   [{:c/d "cd" :db/id -1}
               {:a/b "ab" :db/id -2}]
        conn2 (d/create-conn nil s)]
    (is (= (d/schema conn2) (d/update-schema conn1 s)))
    (d/update-schema conn1 s1)
    (is (= (d/schema conn1) (-> (merge c/implicit-schema s s1)
                                (assoc-in [:a/b :db/aid] 3)
                                (assoc-in [:c/d :db/aid] 4))))
    (d/transact! conn1 txs)
    (is (= 2 (count (d/datoms @conn1 :eavt))))

    (is (thrown-with-msg? Exception #"Cannot delete attribute"
                          (d/update-schema conn1 {} #{:c/d})))

    (d/transact! conn1 [[:db/retractEntity 1]])
    (is (= (d/schema conn2)
           (d/update-schema conn1 {} #{:c/d})
           (d/schema conn1)))

    (d/update-schema conn1 nil nil {:a/b :e/f})
    (is (= (d/schema conn1) (assoc c/implicit-schema :e/f
                                   {:db/valueType :db.type/string :db/aid 3})))

    (d/close conn1)
    (d/close conn2)))

(deftest test-update-schema-1
  (let [conn (d/create-conn)]
    (d/update-schema conn {:things {}})
    (is (= (d/schema conn) (-> c/implicit-schema
                               (assoc-in [:things :db/aid] 3))))
    (d/update-schema conn {:stuff {}})
    (is (= (d/schema conn) (-> c/implicit-schema
                               (assoc-in [:things :db/aid] 3)
                               (assoc-in [:stuff :db/aid] 4))))
    (d/update-schema conn {} [:things])
    (is (= (d/schema conn) (-> c/implicit-schema
                               (assoc-in [:stuff :db/aid] 4))))
    (d/update-schema conn {:things {}})
    (is (= (d/schema conn) (-> c/implicit-schema
                               (assoc-in [:stuff :db/aid] 4)
                               (assoc-in [:things :db/aid] 5))))))

(deftest test-update-schema-ensure-no-duplicate-aids
  (let [conn (d/create-conn)]
    (d/update-schema conn {:up/a {}})
    (d/transact! conn [{:foo 1}])
    (let [aids (map :db/aid (vals (d/schema conn)))]
      (is (= (count aids) (count (set aids))))
      (d/close conn))))

(deftest test-ways-to-create-conn-1
  (let [conn (d/create-conn)]
    (is (= #{} (set (d/datoms @conn :eavt))))
    (is (= c/implicit-schema (db/-schema @conn)))
    (d/close conn)))

(deftest test-ways-to-create-conn-2
  (let [schema { :aka { :db/cardinality :db.cardinality/many :db/aid 3}}
        conn   (d/create-conn nil schema)]
    (is (= #{} (set (d/datoms @conn :eavt))))
    (is (= (db/-schema @conn) (merge schema c/implicit-schema)))
    (d/close conn)))

(deftest test-ways-to-create-conn-3
  (let [datoms #{(d/datom 1 :age  17)
                 (d/datom 1 :name "Ivan")}

        conn (d/conn-from-datoms datoms)]
    (is (= datoms (set (d/datoms @conn :eavt))))
    (is (= (d/schema conn) (db/-schema @conn)))
    (d/close conn))

  (let [schema { :aka { :db/cardinality :db.cardinality/many :db/aid 1}}
        datoms #{(d/datom 1 :age  17)
                 (d/datom 1 :name "Ivan")}

        conn (d/conn-from-datoms datoms nil schema)]
    (is (= datoms (set (d/datoms @conn :eavt))))
    (is (= (d/schema conn) (db/-schema @conn)))
    (d/close conn))

  (let [datoms #{(d/datom 1 :age  17)
                 (d/datom 1 :name "Ivan")}

        conn (d/conn-from-db (d/init-db datoms))]
    (is (= datoms (set (d/datoms @conn :eavt))))
    (is (= (d/schema conn) (db/-schema @conn)))
    (d/close conn))

  (let [schema { :aka { :db/cardinality :db.cardinality/many :db/aid 1}}
        datoms #{(d/datom 1 :age  17)
                 (d/datom 1 :name "Ivan")}

        conn (d/conn-from-db (d/init-db datoms nil schema))]
    (is (= datoms (set (d/datoms @conn :eavt))))
    (is (= (d/schema conn) (db/-schema @conn)))
    (d/close conn)))

(deftest test-recreate-conn
  (let [schema {:name          {:db/valueType :db.type/string}
                :dt/updated-at {:db/valueType :db.type/instant}}
        dir    (u/tmp-dir (str "recreate-conn-test-" (UUID/randomUUID)))
        conn   (d/create-conn dir schema)]
    (d/transact! conn [{:db/id         -1
                        :name          "Namebo"
                        :dt/updated-at (Date.)}])
    (d/close conn)

    (let [conn2 (d/create-conn dir schema)]
      (d/transact! conn2 [{:db/id         -2
                           :name          "Another name"
                           :dt/updated-at (Date.)}])
      (is (= 4 (count (d/datoms @conn2 :eavt))))
      (d/close conn2))
    (u/delete-files dir)))

(deftest test-get-conn
  (let [schema {:name          {:db/valueType :db.type/string}
                :dt/updated-at {:db/valueType :db.type/instant}}
        dir    (u/tmp-dir (str "get-conn-test-" (UUID/randomUUID)))
        conn   (d/get-conn dir schema)]
    (d/transact! conn [{:db/id         -1
                        :name          "Namebo"
                        :dt/updated-at (Date.)}])
    (d/close conn)

    (let [conn2 (d/get-conn dir schema)]
      (d/transact! conn2 [{:db/id         -2
                           :name          "Another name"
                           :dt/updated-at (Date.)}])
      (is (= 4 (count (d/datoms @conn2 :eavt))))
      (d/close conn2))
    (u/delete-files dir)))

(deftest test-with-conn
  (let [dir (u/tmp-dir (str "with-conn-test-" (UUID/randomUUID)))]
    (d/with-conn [conn dir]
      (d/transact! conn [{:db/id      -1
                          :name       "something"
                          :updated-at (Date.)}])
      (is (= 2 (count (d/datoms @conn :eav)))))
    (u/delete-files dir)))
