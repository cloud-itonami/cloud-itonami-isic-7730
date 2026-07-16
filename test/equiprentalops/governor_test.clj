(ns equiprentalops.governor-test
  "Pure unit tests of `equiprentalops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [equiprentalops.advisor :as adv]
            [equiprentalops.governor :as gov]
            [equiprentalops.store :as store]))

(def unit-1 {:asset-id "unit-1" :name "Mini excavator, 1.5t, diesel" :registered? true :verified? true})
(def unit-3 {:asset-id "unit-3" :name "Towable generator, awaiting inspection" :registered? true :verified? false})

(defn- clean-proposal [op asset-id]
  {:op op :asset-id asset-id :summary "s" :rationale "routine equipment rental operations coordination"
   :cites [asset-id] :effect :propose :value {} :confidence 0.85})

(deftest asset-unregistered-is-hard
  (testing "no asset record at all -> HARD hold"
    (let [s (store/mem-store {"unit-1" unit-1})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "unknown-unit") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:asset-unverified} (map :rule (:violations verdict)))))))

(deftest asset-unverified-is-hard
  (testing "asset registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"unit-3" unit-3})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "unit-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:asset-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"unit-1" unit-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-maintenance-inspection "unit-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"unit-1" unit-1})
          verdict (gov/check {} nil (clean-proposal :sell-unit "unit-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest reclear-without-inspection-is-hard-and-permanent
  (testing "a proposal that claims to directly certify a unit safe to re-rent without inspection is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"unit-1" unit-1})
          poisoned (assoc (clean-proposal :flag-equipment-safety-concern "unit-1")
                          :rationale "certify this unit safe to re-rent without inspection immediately"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest finalize-safety-clearance-content-is-hard
  (testing "a proposal that claims to directly finalize the safety clearance is HARD-blocked, same as re-rent-without-inspection"
    (let [s (store/mem-store {"unit-1" unit-1})
          poisoned (assoc (clean-proposal :schedule-maintenance-inspection "unit-1")
                          :rationale "finalize the safety clearance for unit-1 and return it to the rental fleet"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-equipment-safety-authority-content-is-hard
  (testing "a proposal that claims to override the equipment safety authority is HARD-blocked"
    (let [s (store/mem-store {"unit-1" unit-1})
          poisoned (assoc (clean-proposal :schedule-maintenance-inspection "unit-1")
                          :value {:decision "override the equipment safety authority hold on unit-1"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-equipment-safety-concern-is-not-scope-excluded
  (testing "flagging observed defect/damage/malfunction concerns (including a mention of a failed post-return inspection as raw observation, not a finalized clearance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"unit-1" unit-1})
          concern (assoc (clean-proposal :flag-equipment-safety-concern "unit-1")
                         :value {:concern "hydraulic hose leak observed on unit-7; unit failed the post-return safety inspection"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (defect, damage, failed inspection) is exactly what this op exists to surface"))))

(deftest guest-facing-default-advisor-proposals-never-scope-excluded-end-to-end
  (testing "CRITICAL regression guard, full governor/check path: every default mock-advisor
    proposal for every allowed op is governor-clean of scope-exclusion (the earlier bug
    class where a bare-noun term list self-tripped on the advisor's own legitimate
    disclaimer copy)"
    (let [s (store/mem-store {"unit-1" unit-1})]
      (doseq [op [:log-rental-record :schedule-maintenance-inspection
                  :coordinate-fleet-restock :flag-equipment-safety-concern]]
        (let [p (adv/infer nil {:op op :asset-id "unit-1" :patch {}})
              verdict (gov/check {:asset-id "unit-1"} nil p s)]
          (is (not (some #{:scope-excluded} (map :rule (:violations verdict))))
              (str "default proposal for op " op " must not self-trip scope-exclusion")))))))

(deftest equipment-safety-concern-always-escalates-even-when-otherwise-clean
  (testing ":flag-equipment-safety-concern is always high-stakes/escalate, regardless of confidence"
    (let [s (store/mem-store {"unit-1" unit-1})
          concern (assoc (clean-proposal :flag-equipment-safety-concern "unit-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-fleet-restock-always-escalates
  (testing "a coordinate-fleet-restock proposal above the cost threshold escalates even when governor-clean and high confidence"
    (let [s (store/mem-store {"unit-1" unit-1})
          expensive (assoc (clean-proposal :coordinate-fleet-restock "unit-1")
                           :value {:estimated-cost 15000} :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-fleet-restock-does-not-force-escalation
  (testing "a coordinate-fleet-restock proposal under the cost threshold is not forced to escalate on cost grounds alone"
    (let [s (store/mem-store {"unit-1" unit-1})
          routine (assoc (clean-proposal :coordinate-fleet-restock "unit-1")
                        :value {:estimated-cost 300} :confidence 0.9)
          verdict (gov/check {} nil routine s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates any otherwise-clean proposal"
    (let [s (store/mem-store {"unit-1" unit-1})
          uncertain (assoc (clean-proposal :log-rental-record "unit-1") :confidence 0.4)
          verdict (gov/check {} nil uncertain s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest clean-high-confidence-proposal-is-ok
  (testing "a clean, high-confidence, low-cost, registered-asset proposal is fully ok"
    (let [s (store/mem-store {"unit-1" unit-1})
          clean (clean-proposal :log-rental-record "unit-1")
          verdict (gov/check {} nil clean s)]
      (is (true? (:ok? verdict)))
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict))))))
