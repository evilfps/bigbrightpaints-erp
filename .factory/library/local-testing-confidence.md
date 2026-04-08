# Local Testing Confidence Ladder

- `testing/local/confidence-lanes.json` is the authoritative lane contract. `scripts/validate_confidence_lanes.py` fail-closes if the repo stops declaring what local, PR, main, staging, and canary each must prove, or if the deployable-state / broken-test policy drifts.
- Retired helper scripts (`scripts/dev_smoke.sh`, `scripts/local_guard.sh`) stay retired. Use manifest-backed lane commands instead.
- Local lane (`safe to continue coding`): `test-dev-smoke`, `test-local-guard` (`commands.test-dev-smoke`, `commands.test-local-guard`).
- PR lane (`safe to merge`): `test-pr-ci` (`commands.test-pr-ci`, alias `commands.gate-fast`).
- Main lane (`safe to integrate`): `test-baseline-core` (`commands.test-baseline-core`, alias `commands.gate-core`).
- Staging lane (`safe to deploy`): `test-release-local` (`commands.test-release-local`, alias `commands.release-proof`) plus `commands.gate-release`; keep `commands.verify-local` and `commands.strict-runtime-smoke-check` for strict runtime/deployable-state evidence.
- `Deployable` is concrete: app boots, migrations run, auth works, tenant isolation holds, one core O2C path works, one core P2P/accounting path works, health/readiness evidence is real, and rollback is possible.
- Broken tests must be classified as `product-bug`, `bad-test`, or `infra-coupled` before they affect a lane. Quarantine is allowed only with owner, repro, start, expiry, classification, and `action=quarantine` metadata in `scripts/test_quarantine.txt`; otherwise the expected path is fix, delete, or demote.
- Fast lanes remain reserved for stable, business-critical proof. Slow or infra-heavy evidence belongs in stronger lanes or advisory observation loops instead of the default developer loop.
