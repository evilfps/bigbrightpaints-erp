# Local Testing Confidence Ladder

- `testing/local/confidence-lanes.json` is the authoritative lane contract. `scripts/validate_confidence_lanes.py` fail-closes if the repo stops declaring what local, PR, main, staging, and canary each must prove, or if the deployable-state / broken-test policy drifts.
- `commands.test-dev-smoke` / `bash scripts/dev_smoke.sh` is the default edit/run loop for the `local` decision point: safe to continue coding, not safe to merge or deploy.
- `commands.test-local-guard` / `bash scripts/local_guard.sh` is the stronger local pre-push lane. It still only proves local confidence, but it must cover every stable critical non-flaky truthsuite test and enforce quarantine metadata.
- `commands.test-pr-ci` (`bash scripts/gate_fast.sh`) is the `pr` decision point: safe to merge. `commands.test-baseline-core` (`bash scripts/gate_core.sh`) is the `main` decision point: safe to integrate. `commands.test-release-local` / `bash scripts/verify_local.sh` plus `scripts/gate_release.sh` carry the `staging`/deployable-state contract.
- `Deployable` is concrete: app boots, migrations run, auth works, tenant isolation holds, one core O2C path works, one core P2P/accounting path works, health/readiness evidence is real, and rollback is possible.
- Broken tests must be classified as `product-bug`, `bad-test`, or `infra-coupled` before they affect a lane. Quarantine is allowed only with owner, repro, start, expiry, classification, and `action=quarantine` metadata in `scripts/test_quarantine.txt`; otherwise the expected path is fix, delete, or demote.
- Fast lanes remain reserved for stable, business-critical proof. Slow or infra-heavy evidence belongs in stronger lanes or advisory observation loops instead of the default developer loop.
