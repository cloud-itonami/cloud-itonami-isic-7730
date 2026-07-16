(ns equiprentalops.store
  "SSoT for the ISIC-7730 machinery / equipment / tangible-goods rental
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a construction /
  event / agricultural equipment rental fleet: rental-record logging
  (checkout / return / inspection-note data), post-return maintenance
  inspection scheduling, rental-fleet procurement/replacement
  coordination, and equipment-safety-concern flagging (defect / damage /
  malfunction). It NEVER directly finalizes an equipment-safety-clearance
  decision (e.g. certifying a returned unit as safe to re-rent without
  inspection) -- see `equiprentalops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block,
  per this fleet's Wave 4 person-facing-service safety guardrail
  (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `units` directory keyed by `:asset-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified rental-asset record must exist before ANY
  proposal for that unit may ever commit or escalate --
  `equiprentalops.governor`'s `asset-unverified-violations` re-derives
  this from the unit's own `:registered?`/`:verified?` fields, never
  from a proposal's self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which unit a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (asset [s asset-id] "Registered rental-asset (equipment unit) record,
    or nil. Unit map: {:asset-id .. :name .. :registered? bool
    :verified? bool}.")
  (all-assets [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-assets [s assets] "replace/seed the unit directory (map asset-id->unit)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained rental-fleet directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:units
   {"unit-1" {:asset-id "unit-1" :name "Mini excavator, 1.5t, diesel"
              :registered? true :verified? true}
    "unit-2" {:asset-id "unit-2" :name "Event canopy tent, 6x12m, aluminum frame"
              :registered? true :verified? true}
    "unit-3" {:asset-id "unit-3" :name "Towable generator, 20kVA, awaiting post-return inspection"
              :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (asset [_ asset-id] (get-in @a [:units asset-id]))
  (all-assets [_] (sort-by :asset-id (vals (:units @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-assets [s assets] (when (seq assets) (swap! a assoc :units assets)) s))

(defn seed-db
  "A MemStore seeded with the demo rental-fleet directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `units` map (asset-id string ->
  unit map) -- the primary test/dev entry point. `units` may be empty
  (an unregistered-everywhere store)."
  [units]
  (->MemStore (atom {:units (or units {}) :ledger [] :coordination-log []})))
