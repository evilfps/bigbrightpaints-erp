# Lane 08 confidence lanes and deployability contract

- base branch: `Factory-droid`
- scope: testing/workflow/governance surfaces only
- authoritative machine-readable contract: [`testing/local/confidence-lanes.json`](../../../../testing/local/confidence-lanes.json)
- workflow validator: [`scripts/validate_confidence_lanes.py`](../../../../scripts/validate_confidence_lanes.py)

## Decision points

### Local — safe to continue coding

- commands: `test-dev-smoke`, `test-local-guard`
- proves: the active slice still passes stable, deterministic, business-critical truthsuite proof and the local manifests still fail closed
- must not claim: merge confidence, deployability, or broad infra-heavy proof

### PR — safe to merge

- command: `test-pr-ci`
- proves: routed PR CI passes with changed-files traceability, catalog/quarantine guards, and critical truthsuite coverage
- must not claim: staging/deployability proof or rely on local reruns as a replacement for CI evidence

### Main — safe to integrate

- command: `test-baseline-core`
- proves: critical + concurrency + reconciliation truthsuite coverage and hard contract/coverage guards are green on the integration line
- must not claim: that the candidate artifact is deployable without staging evidence

### Staging — safe to deploy

- commands: `test-release-local`, `scripts/gate_release.sh`
- proves: the release-grade lane passes and the deployable-state contract is attached to the candidate artifact
- deployable means: app boots, migrations run, auth works, tenant isolation holds, one core O2C path works, one core P2P/accounting path works, health/readiness is real, and rollback is possible

### Canary — safe under real traffic

- required signals: live telemetry, rollback readiness, lane-owner acknowledgement
- proves: the already-staged artifact remains healthy under a narrow real-traffic slice before wider rollout
- must not claim: that canary can replace staging or excuse missing rollback ownership

## Broken-test handling

- Every failing/flaky test touched by this lane must be classified as exactly one of:
  - `product-bug`
  - `bad-test`
  - `infra-coupled`
- Then choose one explicit path:
  - `fix`
  - `quarantine`
  - `delete`
  - `demote`
- `scripts/test_quarantine.txt` is only for the `quarantine` path. Entries must include owner, repro notes, start date, expiry, classification, and `action=quarantine`.
- Quarantined tests must stay out of `gate-fast`, `gate-core`, `gate-release`, and `gate-reconciliation` lane membership.

## Fast-lane rule

Fast lanes remain reserved for stable, business-critical proof. Slow, infra-heavy, or low-signal evidence moves to stronger lanes or advisory observation loops instead of bloating the default local or PR path.
