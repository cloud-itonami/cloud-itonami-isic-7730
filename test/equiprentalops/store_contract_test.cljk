(ns equiprentalops.store-contract-test
  "Contract tests for `equiprentalops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [equiprentalops.store :as store]))

(deftest mem-store-asset-lookup
  (testing "MemStore can store and retrieve assets by ID (string keys)"
    (let [units {"u1" {:asset-id "u1" :name "Unit 1" :registered? true :verified? true}}
          s (store/mem-store units)]
      (is (some? (store/asset s "u1")))
      (is (nil? (store/asset s "u99"))))))

(deftest mem-store-all-assets
  (testing "MemStore returns all assets in sorted order"
    (let [units {"u2" {:asset-id "u2" :name "Unit 2"}
                 "u1" {:asset-id "u1" :name "Unit 1"}
                 "u3" {:asset-id "u3" :name "Unit 3"}}
          s (store/mem-store units)
          all-u (store/all-assets s)]
      (is (= 3 (count all-u)))
      (is (= "u1" (:asset-id (first all-u))))
      (is (= "u3" (:asset-id (last all-u)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-rental-record :asset-id "u1" :value {:renter "test"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-assets
  (testing "MemStore with-assets replaces the unit directory"
    (let [s (store/mem-store {})
          new-units {"u1" {:asset-id "u1" :name "Unit 1"}}]
      (is (= 0 (count (store/all-assets s))))
      (store/with-assets s new-units)
      (is (= 1 (count (store/all-assets s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo units"
    (let [s (store/seed-db)]
      (is (> (count (store/all-assets s)) 0))
      (is (some? (store/asset s "unit-1")))
      (is (some? (store/asset s "unit-2")))
      (is (some? (store/asset s "unit-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for asset-id"
    (let [demo (store/demo-data)
          units (:units demo)]
      (doseq [[k v] units]
        (is (string? k) "keys must be strings")
        (is (string? (:asset-id v)) "asset-id must be string")
        (is (= k (:asset-id v)) "key must match asset-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
