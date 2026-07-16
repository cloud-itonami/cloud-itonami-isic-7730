(ns equiprentalops.phase-test
  "Unit tests of `equiprentalops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [equiprentalops.phase :as phase]))

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-rental-record :schedule-maintenance-inspection
                :coordinate-fleet-restock :flag-equipment-safety-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-rental-record-only
  (testing "phase 1 allows only rental-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-rental-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-maintenance-inspection} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-rental-record :schedule-maintenance-inspection :coordinate-fleet-restock]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-safety ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-rental-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-maintenance-inspection} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-fleet-restock} :commit)]
      (is (= :commit disposition)))))

(deftest equipment-safety-concern-holds-when-not-enabled
  (testing ":flag-equipment-safety-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-equipment-safety-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-equipment-safety-concern yet"))))))

(deftest equipment-safety-concern-escalates-when-enabled
  (testing ":flag-equipment-safety-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-equipment-safety-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate equipment-safety-concerns regardless of governor disposition"))))

(deftest equipment-safety-concern-never-in-any-auto-set
  (testing "structural invariant: :flag-equipment-safety-concern is never a member of any phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-equipment-safety-concern))
          (str "phase " ph " must never auto-commit flag-equipment-safety-concern")))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-rental-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
