# Local Testing Confidence Ladder

- `commands.test-dev-smoke` / `bash scripts/dev_smoke.sh` is the default edit/run loop. It validates the local manifests, then runs the curated `testing/local/manifests/dev-smoke.txt` truthsuite slice through the `dev-smoke` Maven profile for a fast cross-domain sanity pass.
- `commands.test-local-guard` / `bash scripts/local_guard.sh` is the pre-push local guard. It fail-closes on empty or drifting manifests, runs the existing catalog/flaky guards, and then executes every critical non-flaky truthsuite test from `testing/local/manifests/local-guard.txt` through the `local-guard` profile.
- `commands.test-pr-ci` keeps `bash scripts/gate_fast.sh` as the routed PR CI confidence baseline. Use it when you need the remote diff-based coverage / traceability behavior, not for every normal local edit loop.
- `commands.test-baseline-core`, `commands.test-baseline-reconciliation`, and `commands.test-baseline-quality` remain the heavier remote or scheduled layers. `commands.test-release-local` / `bash scripts/verify_local.sh` is the broad local release rehearsal, not the default pre-push habit.
- Manifest drift is intentional fail-closed behavior: if a critical truthsuite test is added/retagged without updating the local ladder manifests, `validate_local_test_manifests.py` blocks both local commands instead of silently weakening coverage.
