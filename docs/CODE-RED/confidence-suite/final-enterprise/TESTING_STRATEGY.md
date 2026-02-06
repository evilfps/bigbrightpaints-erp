# Final Enterprise Truth-Suite Strategy

This folder is the final strategy snapshot for authoritative deploy-readiness tests.

## Scope

- Authoritative test package:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/**`
- Authoritative policy docs:
  - `docs/CODE-RED/**`
- Primary runtime truth:
  - production code + Flyway schema.

## Gate ownership

1. `gate-fast`
- critical invariants + changed-file coverage.

2. `gate-core`
- critical + concurrency + reconciliation truth tests.

3. `gate-release`
- strict verify + fresh/upgrade migration matrix + scan hard-fail.

4. `gate-reconciliation`
- operational-to-financial truth checks with mismatch artifact.

5. `gate-quality`
- mutation threshold + rolling flake-rate threshold + catalog governance.

## Failure semantics

- Any invariant mismatch, drift-scan hit, or required-threshold miss is `NO-GO`.
- No legacy test file can be promoted as truth evidence for these gates.
