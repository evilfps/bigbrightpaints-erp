# Gate Refresh Evidence - TKT-ERP-STAGE-096

- captured_at_utc: 2026-02-20T10:08:16Z
- canonical_head_sha: `a6f74013cd852d6160fedb283db29988637b7eba`
- release_anchor_sha: `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`

## Local Run (macOS workstation)

Command envelope:
- `DIFF_BASE=06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e GATE_FAST_RELEASE_VALIDATION_MODE=true RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_fast.sh`
- `RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_core.sh`
- `RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_reconciliation.sh`
- `PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_release.sh`

Results:
- `gate_fast`: `FAIL` (`line_ratio=0.3163`, `branch_ratio=0.3320`)
- `gate_core`: `PASS`
- `gate_reconciliation`: `PASS`
- `gate_release`: `FAIL` (`psql` connection refused at `127.0.0.1:5432`)

Evidence directory:
- `artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`

## SSH Run (asus-tuf-tail-ip)

Remote workspace:
- `/home/realnigga/tmp/bigbrightpaints-erp-gate096`

Command envelope:
- `DIFF_BASE=06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e GATE_FAST_RELEASE_VALIDATION_MODE=true RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_fast.sh`
- `RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_core.sh`
- `RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_reconciliation.sh`
- `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres RELEASE_HEAD_SHA=a6f74013cd852d6160fedb283db29988637b7eba GATE_CANONICAL_BASE_REF=harness-engineering-orchestrator bash scripts/gate_release.sh`

Results:
- `gate_fast`: `FAIL` (`exit=1`)
- `gate_core`: `PASS` (`exit=0`)
- `gate_reconciliation`: `PASS` (`exit=0`)
- `gate_release`: `FAIL` (`exit=2`, Postgres connection refused at `127.0.0.1:55432`)

Remote evidence directory:
- `/home/realnigga/tmp/bigbrightpaints-erp-gate096/artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`

Remote log checksums:
- `c75d9e3338e464c0bf07b74463aca89505b219d04a3ae9f2c82359a4b5ba6551  gate_core.log`
- `ae8119f1f97f94cdcc1eddc9a88c734b045dd32fd2425834f598548b393f079c  gate_fast.log`
- `f54491e18ec6680e353a14ab720c16aefb6a844840b4a6ae955816a8c5aa970a  gate_reconciliation.log`
- `aa196f23d1880de99e190c8979e50a3557e3c7f072aeb9c2a17d1b5d5b0e5b31  gate_release.log`

## Blocking Conditions

1. `gate_fast` changed-files coverage does not meet release thresholds on the current head/anchor pair.
2. `gate_release` cannot complete until Postgres connectivity is available on the configured host/port.
