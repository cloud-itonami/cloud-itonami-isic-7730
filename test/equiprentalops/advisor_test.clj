(ns equiprentalops.advisor-test
  "Unit tests of `equiprentalops.advisor` proposal generation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [equiprentalops.advisor :as adv]
            [equiprentalops.governor :as gov]
            [equiprentalops.store :as store]))

(def db (store/seed-db))

(deftest propose-rental-record-shape
  (testing "rental-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-rental-record
                           :asset-id "unit-1"
                           :patch {:renter "Acme Contracting" :checkout "2026-07-14"}})]
      (is (= :log-rental-record (:op p)))
      (is (= "unit-1" (:asset-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :asset-id)))))

(deftest propose-maintenance-inspection-shape
  (testing "maintenance-inspection proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-maintenance-inspection
                           :asset-id "unit-2"
                           :patch {:item "canopy frame check" :urgency "routine"}})]
      (is (= :schedule-maintenance-inspection (:op p)))
      (is (= "unit-2" (:asset-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-fleet-restock-shape
  (testing "fleet-restock proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-fleet-restock
                           :asset-id "unit-1"
                           :patch {:item "bucket teeth" :quantity 6 :estimated-cost 300}})]
      (is (= :coordinate-fleet-restock (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-equipment-safety-concern-shape
  (testing "equipment-safety-concern proposal always proposes, never actuates"
    (let [p (adv/infer db {:op :flag-equipment-safety-concern
                           :asset-id "unit-1"
                           :patch {:concern "hydraulic hose leak observed"}})]
      (is (= :flag-equipment-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-rental-record :schedule-maintenance-inspection
                :coordinate-fleet-restock :flag-equipment-safety-concern]]
      (let [p (adv/infer db {:op op :asset-id "unit-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-rental-record :schedule-maintenance-inspection
                :coordinate-fleet-restock :flag-equipment-safety-concern]]
      (let [p (adv/infer db {:op op :asset-id "unit-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest unknown-op-returns-empty-proposal
  (testing "an op outside the four-op set produces an unrecognized (empty) proposal shape, left for the governor to reject"
    (let [p (adv/infer db {:op :not-a-real-op :asset-id "unit-1" :patch {}})]
      (is (empty? p)))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "CRITICAL regression guard: this fleet has repeatedly hit a bug class where a
    governor scope-exclusion term phrased as a bare noun (e.g. \"safety\") accidentally
    matches the mock advisor's own DEFAULT rationale/disclaimer text for a legitimate,
    allowed proposal, causing the actor to self-block on its own happy path. Every
    default (non-poisoned, non-out-of-scope) proposal from every op in the closed
    allowlist, with a variety of realistic patches, must NEVER trip
    `equiprentalops.governor/scope-excluded-terms`."
    (doseq [op [:log-rental-record :schedule-maintenance-inspection
                :coordinate-fleet-restock :flag-equipment-safety-concern]
            patch [{}
                   {:renter "Acme Contracting" :checkout "2026-07-14"}
                   {:item "hydraulic hose post-return inspection" :urgency "routine"}
                   {:item "replacement bucket teeth" :quantity 6 :estimated-cost 300}
                   {:concern "hydraulic hose leak observed after last return" :confidence 0.92}
                   {:concern "brake failure reported on return; unit taken out of rotation"}]]
      (let [p (adv/infer db {:op op :asset-id "unit-1" :patch patch})
            blob (str/lower-case
                  (pr-str (select-keys p [:op :summary :rationale :cites :value])))]
        (is (not (some #(str/includes? blob %) gov/scope-excluded-terms))
            (str "default advisor proposal for op " op " with patch " (pr-str patch)
                 " must never contain a scope-excluded term -- self-trip on the happy path"))))))
