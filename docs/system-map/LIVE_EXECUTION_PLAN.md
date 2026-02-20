# Live Execution Plan (Async-Loop)

Last updated: 2026-02-20
Mode: Active (gate regression; deployment blocked)

## Goal
- Restore gate freshness and re-close completion gates on canonical head.
- Completion-gate board is pending and not safe-to-deploy due to 2026-02-20 gate failures (see `docs/system-map/COMPLETION_GATES_STATUS.md`).

## Current state (2026-02-20)
- Async-loop status: `ACTIVE`.
- Canonical head: `a6f74013cd852d6160fedb283db29988637b7eba` with anchor `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`.
- `gate_fast`: `FAIL` (changed-files coverage below threshold; line_ratio `0.3163`, branch_ratio `0.3320`).
- `gate_core`: `PASS`.
- `gate_reconciliation`: `PASS`.
- `gate_release`: `FAIL` (Postgres connection refused at `127.0.0.1:55432`).
- Evidence ledger:
  - local run: `artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`
  - ssh host run: `/home/realnigga/tmp/bigbrightpaints-erp-gate096/artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`

## Active constraints (refresh required)
- Review dispatch capacity last confirmed blocked by external cap (max 6) on 2026-02-16; recheck required.
- Direct commit review fallback last confirmed in use on 2026-02-16 while the cap persisted; recheck required.
- Review queue status last confirmed clear through `d618ee04` on 2026-02-16; recheck required.
- Release gate local prerequisite: ensure v2 matrix DB env is configured before rerun (`PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres`).

## Live plan lanes

### Lane A: Review queue drain (blocking lane)
1. Retry review dispatch each cycle.
2. While blocked, execute direct commit review fallback without timeout and log outcomes in `asyncloop`.
3. Record blocker + pending SHAs in `asyncloop`.
4. Apply fixes immediately for any reviewer finding once surfaced.

### Lane B: Gate freshness (safety lane)
1. Keep anchored `gate_fast` fresh on moving head.
2. Re-run `gate_core`, `gate_reconciliation`, and `gate_release` on cadence for staging evidence.
3. Record command outcomes in `asyncloop`.
4. Current note (2026-02-20): anchored gate set on canonical head `a6f74013cd852d6160fedb283db29988637b7eba` with anchor `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`:
   - `gate_fast` FAIL (changed-files coverage below threshold; line_ratio `0.3163`, branch_ratio `0.3320`)
   - `gate_core` PASS
   - `gate_reconciliation` PASS
   - `gate_release` FAIL (Postgres connection refused at `127.0.0.1:55432`)

### Lane C: Consistency hardening (throughput lane)
1. Prefer small, evidence-backed slices with low blast radius.
2. Prioritize dedupe and role-parity consistency (dealer/supplier behavior symmetry).
3. Keep v2 contract stability: no public API naming drift.
4. Canonicalize internal mismatch metadata toward `partner*` while preserving external role-specific payload compatibility.
5. Last confirmed 2026-02-16: `c676bee9` dedupes async command lease bootstrap in `CommandDispatcher` through shared `startLease(...)`, reducing divergence risk across approval/fulfillment/dispatch/payroll flows while preserving exactly-once semantics and canonical idempotency propagation.

### Lane D: Completion-gate consolidation (closure lane)
1. Convert module-level evidence into explicit completion-gate closure packs.
2. Keep `docs/system-map/COMPLETION_GATES_STATUS.md` current after each gate-affecting tranche.
3. Do not claim safe-to-deploy until all `5/5` completion gates are marked `CLOSED`.

## Exit criteria
- Pending code-review queue is empty (all code commits reviewed).
- No unresolved high/critical findings remain.
- Final ledger gates pass on same closure evidence set.
- M8/M9 async hardening lane is complete and recorded in `asyncloop`.

## Latest gate evidence (2026-02-20)
1. Canonical head: `a6f74013cd852d6160fedb283db29988637b7eba` with anchor `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`.
2. `gate_fast`: `FAIL` (changed-files coverage below threshold; line_ratio `0.3163`, branch_ratio `0.3320`).
3. `gate_core`: `PASS`.
4. `gate_reconciliation`: `PASS`.
5. `gate_release`: `FAIL` (Postgres connection refused at `127.0.0.1:55432`).
6. Evidence ledger:
   - local run: `artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`
   - ssh host run: `/home/realnigga/tmp/bigbrightpaints-erp-gate096/artifacts/gate-ledger/a6f74013cd852d6160fedb283db29988637b7eba`
