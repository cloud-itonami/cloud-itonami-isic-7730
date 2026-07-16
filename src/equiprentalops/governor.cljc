(ns equiprentalops.governor
  "EquipRentalGovernor -- the independent compliance layer that earns
  the EquipRentalOpsAdvisor the right to commit. The advisor has no
  notion of whether a rental-asset record is actually registered and
  verified, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area, so this MUST
  be a separate system able to *reject* a proposal and fall back to
  HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (rental-record logging, post-return maintenance-inspection scheduling,
  rental-fleet restock/replacement coordination, equipment-safety-concern
  flagging). It NEVER performs or authorizes:
    - directly finalizing an equipment-safety-clearance decision (e.g.
      certifying a returned unit as safe to re-rent without inspection)
    - overriding an equipment-safety-authority decision
    - any other equipment-safety-authority action

  This is the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500): equipment rental (power tools, construction/event
  equipment) has a direct end-user-safety dimension -- defective or
  uninspected equipment causing injury -- so the closed op allowlist
  NEVER includes any op that directly finalizes an
  equipment-safety-clearance decision; those are always either a hard
  permanent block or an always-escalate op, never auto-commit-eligible.
  `:flag-equipment-safety-concern` always escalates to human sign-off
  and is never a member of any phase's `:auto` set.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Asset unverified           -- the target rental-asset (equipment
                                      unit) record must exist AND be
                                      independently confirmed
                                      `:registered?`/`:verified?` in the
                                      store before ANY proposal for it
                                      may commit or even escalate. Never
                                      trusts a proposal's own claim
                                      about the asset -- re-derived from
                                      the asset's own store record, the
                                      same 'ground truth, not
                                      self-report' discipline every
                                      sibling actor's governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op is outside the closed
                                      four-op allowlist, or whose
                                      rationale, summary, citations or
                                      draft value touches directly
                                      finalizing an
                                      equipment-safety-clearance
                                      decision or overriding an
                                      equipment-safety-authority
                                      decision, is a HARD, PERMANENT
                                      block -- this actor's charter
                                      excludes that territory
                                      structurally, not as a rollout
                                      milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-equipment-safety-concern` (ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is), OR a `:coordinate-fleet-restock` proposal whose
  estimated cost exceeds `high-cost-threshold`.
  `equiprentalops.phase` independently agrees:
  `:flag-equipment-safety-concern` is never a member of any phase's
  `:auto` set either -- two layers, not one."
  (:require [clojure.string :as str]
            [equiprentalops.store :as store]))

(def confidence-floor 0.6)

(def high-cost-threshold
  "A `:coordinate-fleet-restock` proposal whose `:value
  :estimated-cost` exceeds this amount (USD) ALWAYS escalates to a
  human, regardless of confidence -- routine consumable/attachment
  restocks sit well under this, so this only catches unusually large
  procurement/replacement orders."
  2000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`).
  Per the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500), NO op in this set may directly finalize an
  equipment-safety-clearance decision -- every op here is `:effect
  :propose` only, and `:flag-equipment-safety-concern` always escalates
  rather than ever auto-committing."
  #{:log-rental-record :schedule-maintenance-inspection
    :coordinate-fleet-restock :flag-equipment-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-equipment-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing an
  equipment-safety-clearance decision (e.g. certifying a returned unit
  as safe to re-rent without inspection) or overriding an
  equipment-safety-authority decision. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  Deliberately phrased as EXECUTION/FINALIZATION phrases (verb + object),
  not bare nouns like \"safety\" or \"clearance\" alone -- a legitimate
  `:flag-equipment-safety-concern` proposal (or the other three ops'
  own routine disclaimer copy, which legitimately mentions concepts
  like re-rent eligibility and human sign-off) must be free to describe
  a defect, damage report, or malfunction without tripping this gate
  (see `equiprentalops.governor-test`'s own
  `legitimate-equipment-safety-concern-is-not-scope-excluded` and
  `equiprentalops.advisor-test`'s
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`);
  only a proposal that claims to *actually perform* the
  equipment-safety-authority action is blocked here."
  ["certify safe to re-rent without inspection" "certify this unit safe to re-rent without inspection"
   "certify as safe to re-rent without inspection" "clear this unit for re-rent without inspection"
   "clear for re-rent without inspection" "approve re-rent without inspection"
   "finalize the safety clearance" "finalize safety clearance" "finalize equipment safety clearance"
   "skip the inspection and re-rent" "bypass the inspection and re-rent" "re-rent without inspection"
   "override the equipment safety authority" "override equipment safety authority"
   "override the equipment safety clearance decision" "override equipment safety clearance decision"
   "bypass equipment safety authority" "bypass the equipment safety authority"
   "点検なしで再貸出を承認" "検査を省略して再貸出を承認" "安全クリアランスを確定" "安全クリアランス判定を確定"
   "機材安全当局の判断を上書き" "機材安全当局の決定を無視" "安全認定を省略して再貸出"])

;; ----------------------------- checks -----------------------------

(defn- asset-unverified-violations
  "The target rental-asset (equipment unit) record must exist AND be
  independently `:registered?`/`:verified?` in the store -- never trust
  the proposal's own `:asset-id` claim without a store lookup."
  [{:keys [asset-id]} st]
  (let [r (store/asset st asset-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :asset-unverified
        :detail (str asset-id " は未登録または未検証のレンタル資産 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly finalizing an
  equipment-safety-clearance decision or overriding an
  equipment-safety-authority decision, regardless of confidence or how
  clean every other check is. Evaluated UNCONDITIONALLY on every
  proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "点検なしでの再貸出安全クリアランスの確定/機材安全当局の判断の上書きは永久に禁止"}])))

(defn- high-cost-fleet-restock?
  "A `:coordinate-fleet-restock` proposal whose `:value
  :estimated-cost` exceeds `high-cost-threshold` ALWAYS escalates,
  regardless of confidence."
  [proposal]
  (and (= :coordinate-fleet-restock (:op proposal))
       (some-> (get-in proposal [:value :estimated-cost])
               (> high-cost-threshold))))

(defn check
  "Censors a EquipRentalOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [asset-id (or (:asset-id proposal) (:asset-id request))
        hard (into []
                   (concat (asset-unverified-violations {:asset-id asset-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-fleet-restock? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :asset-id   (:asset-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
