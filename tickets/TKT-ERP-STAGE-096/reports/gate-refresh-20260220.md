# Gate Refresh Evidence - TKT-ERP-STAGE-096

- captured_at_utc: 2026-02-20T10:15:36Z
- canonical_head_sha: `edc7cd7439bff5a83d5055057814dc65fb056b60`
- release_anchor_sha: `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`

## Local Baseline Run (macOS workstation, pre-sync head)

Head:
- `a6f74013cd852d6160fedb283db29988637b7eba`

Results:
- `gate_fast`: `FAIL` (`line_ratio=0.3163`, `branch_ratio=0.3320`)
- `gate_core`: `PASS`
- `gate_reconciliation`: `PASS`
- `gate_release`: `FAIL` (`psql` connection refused at `127.0.0.1:5432`)

Evidence directory:
- `artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`

## Authoritative SSH Run (asus-tuf-tail-ip, latest head)

Remote workspace:
- `/home/realnigga/tmp/bigbrightpaints-erp-gate096`

Execution context:
- Postgres container `bbp-gate-pg` provisioned on SSH host with `55432 -> 5432`.
- Canonical head fast-forwarded to `edc7cd7439bff5a83d5055057814dc65fb056b60`.

Results:
- `gate_fast`: `FAIL` (`exit=1`, `line_ratio=0.3163664839467502`, `branch_ratio=0.33203125`)
- `gate_core`: `PASS` (`exit=0`)
- `gate_reconciliation`: `PASS` (`exit=0`)
- `gate_release`: `PASS` (`exit=0`)

Remote evidence directory:
- `/home/realnigga/tmp/bigbrightpaints-erp-gate096/artifacts/gate-ledger/edc7cd7439bff5a83d5055057814dc65fb056b60`

Remote log checksums:
- `717b2011d5adb0cc204d1da2d2c488d104dcc4c0e0703e320393a40941617f72  gate_core.log`
- `960d58dd82392d513bdf4ce9af61c7dea311ac7aab0645d3879cf661b7c81823  gate_fast.log`
- `468b006c5004cb639759407ad0a32df5e45cbb8df6c76aa91fe7bdbbe436b85e  gate_reconciliation.log`
- `1ea19607fab6421eb559b2484a9ec3f3982f186b852e5be4a90c7af0ffa82fee  gate_release.log`

## Blocking Condition

1. `gate_fast` changed-files coverage does not meet release thresholds on the current anchor (`line_ratio=0.3163`, `branch_ratio=0.3320`).
