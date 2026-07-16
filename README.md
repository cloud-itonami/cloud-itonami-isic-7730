# cloud-itonami-isic-7730

**Renting and leasing of other machinery, equipment and tangible goods n.e.c.** — ISIC Rev.4 class 7730.

A coordination-only actor for construction, event, and agricultural equipment rental fleets, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-rental-record, schedule-maintenance-inspection, coordinate-fleet-restock, flag-equipment-safety-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Asset verified** — target rental-asset (equipment unit) record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly finalizing an equipment-safety-clearance decision (e.g. certifying a returned unit as safe to re-rent without inspection) and overriding an equipment-safety-authority decision are permanently blocked. This actor never has the authority to directly finalize an equipment-safety-clearance decision — see CRITICAL below.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: rental-record logging only (approval-gated)
  - Phase 2: + maintenance-inspection scheduling, fleet-restock coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (equipment-safety concerns always escalate; over-threshold fleet-restock proposals always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL — scope

This is a back-office equipment-rental operations-coordination actor, **not** an equipment-safety-clearance authority. It coordinates back-office logistics only. It **NEVER**:

- Directly finalizes an equipment-safety-clearance decision (e.g. certifying a returned unit as safe to re-rent without inspection).
- Overrides an equipment-safety-authority decision.

`flag-equipment-safety-concern` only ever *surfaces* an observed concern (defect, damage, malfunction) for a human to triage — it is never a member of any phase's `:auto` set, at any phase, and it is always escalated to human sign-off. A `coordinate-fleet-restock` proposal whose estimated cost exceeds the governor's cost threshold ($2000) is likewise always escalated to a human, regardless of confidence.

This is the Wave 4 person-facing-service safety guardrail (ADR-2607152500): equipment rental (power tools, construction/event equipment) has a direct end-user-safety dimension — defective or uninspected equipment causing injury — so the closed op allowlist NEVER includes any op that directly finalizes an equipment-safety-clearance decision; those are always either a hard permanent block or an always-escalate op, never auto-commit-eligible.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/equiprentalops/governor_test.clj` — unit tests of governor hard checks and scope exclusion (including a dedicated end-to-end regression guard that default advisor proposals never self-trip scope-exclusion)
- `test/equiprentalops/advisor_test.clj` — advisor proposal shape and consistency (including a dedicated regression guard that every default mock-advisor proposal, across a variety of realistic patches, never self-trips the governor's scope-exclusion check)
- `test/equiprentalops/phase_test.clj` — rollout phase logic
- `test/equiprentalops/governor_contract_test.clj` — full graph integration, audit trail
- `test/equiprentalops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `equiprentalops.store` — SSoT (MemStore, String-keyed asset directory, append-only ledger)
- `equiprentalops.advisor` — contained intelligence node (mock + real-LLM seam)
- `equiprentalops.governor` — independent compliance layer
- `equiprentalops.phase` — staged rollout (0→3)
- `equiprentalops.operation` — langgraph-clj StateGraph
- `equiprentalops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and ADR-2620773000 for design decisions.
