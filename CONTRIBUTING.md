# Contributing to cloud-itonami-isic-7730

Contributions should preserve the actor's scope: back-office equipment-rental
coordination only, with CRITICAL exclusions of directly finalizing an
equipment-safety-clearance decision (e.g. certifying a returned unit as safe to
re-rent without inspection) and overriding an equipment-safety-authority decision
(see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Directly finalize an equipment-safety-clearance decision.
- Override an equipment-safety-authority decision.

Contributions that cross these boundaries will be rejected.
