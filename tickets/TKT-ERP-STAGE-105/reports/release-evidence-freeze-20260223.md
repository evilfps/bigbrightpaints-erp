# TKT-ERP-STAGE-105 Release Evidence Freeze (2026-02-23)

## Scope
- Canonical release-candidate evidence freeze for:
  - `CANONICAL_HEAD_SHA=29ffe36a1c97740dff1275fe164c6c26b11e4d24`
  - `RELEASE_ANCHOR_SHA=e829707475567476bbea113f01200fc59f48d0d6`
- Immutable gate logs + checksums captured under:
  - `bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-105/release-evidence/artifacts/gate-ledger/29ffe36a1c97740dff1275fe164c6c26b11e4d24`

## Commands Run
1. `DIFF_BASE=e829707475567476bbea113f01200fc59f48d0d6 GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
2. `bash scripts/gate_core.sh`
3. `bash scripts/gate_reconciliation.sh`
4. `bash scripts/gate_release.sh`
5. `shasum -a 256 artifacts/gate-ledger/29ffe36a1c97740dff1275fe164c6c26b11e4d24/*.log > artifacts/gate-ledger/29ffe36a1c97740dff1275fe164c6c26b11e4d24/SHA256SUMS`

## Gate Outcomes
- `gate_fast`: PASS (`323` tests, `0` failures, `0` errors)
- `gate_core`: PASS (`344` tests, `0` failures, `0` errors)
- `gate_reconciliation`: PASS (`247` tests, `0` failures, `0` errors)
- `gate_release`: PASS (`344` strict truthsuite tests, migration matrix fresh+upgrade PASS, predeploy scans zero findings)

## Frozen SHA256 Evidence
- `f34bd7496a394f96835eb542312629f5b9eab0a31cd15403d3a7e4492572ec2c` `gate_fast.log`
- `f23dabd241e8d765d33d3db930593513e0a4133ba2889bddbdffd7f9d60035a2` `gate_core.log`
- `ba5ddbfd054ebe00417a457cbf27fc1cdda8bd52f67155873e5e01fa0f7eba9a` `gate_reconciliation.log`
- `5ee0cdd9c7bf80bf0fa41bb8ce64709bf3994f0e7a68fdd58825683f3ee4ae27` `gate_release.log`

## Frozen Artifact Hashes
- `055c07572ec339bc3dfc245c3e0ce0466f39d8e68097cb11b92207a61273f998` `artifacts/gate-release/migration-matrix.json`
- `a702d134b8d4fc3b17e620d34c7a6bfbfd6521e139e3972fc80b758b0cdaa097` `artifacts/gate-release/rollback-rehearsal-evidence.json`
- `4c58a403d181a474b8f7eedc17af91706799315c167eb4dc705f72ecacdb8107` `artifacts/gate-reconciliation/reconciliation-summary.json`

## Notes
- `gate_fast` changed-file coverage enforcement was skipped with reason `no_runtime_source_changes` (expected for docs-only delta from anchor to head).
- Existing uninterrupted full regression proof remains valid on runtime-equivalent code (`1876` tests, `0` failures, `0` errors; completed `2026-02-23T20:47:07+05:30`).
