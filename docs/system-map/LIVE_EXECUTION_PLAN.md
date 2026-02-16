# Live Execution Plan (Async-Loop)

Last updated: 2026-02-16
Mode: Completed (operator stop authorized after M8/M9 closure)

## Goal
- Complete async-loop closure with final ledger gates green and reviewer queue fully drained.
- Completion-gate board is `5/5` CLOSED on current head evidence (see `docs/system-map/COMPLETION_GATES_STATUS.md`).

## Final closure state (2026-02-16)
- Async-loop status: `COMPLETE`.
- M8/M9 status: `CLOSED` on validated head (`d618ee04`).
- Latest code review: `codex review --commit d618ee04` -> no functional regression findings.
- Review queue status: clear through `d618ee04`.

## Active constraints
- Reviewer subagent dispatch currently blocked by external cap (`agent thread limit reached (max 6)`).
- Direct `codex review --commit <sha>` fallback is active while the cap persists.
- Current live review in flight: `none (queue clear as of c676bee9)`.
- Release gate local prerequisite is now explicit: run `gate_release` against local v2 matrix DB env (`PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres`).

## Live plan lanes

### Lane A: Review queue drain (blocking lane)
1. Retry review-subagent dispatch each cycle.
2. While blocked, execute direct `codex review --commit <sha>` fallback without timeout and log outcomes in `asyncloop`.
3. Record blocker + pending SHAs in `asyncloop`.
4. Apply fixes immediately for any reviewer finding once surfaced.

### Lane B: Gate freshness (safety lane)
1. Keep anchored `gate_fast` fresh on moving head.
2. Re-run `gate_core`, `gate_reconciliation`, and `gate_release` on cadence for staging evidence.
3. Record command outcomes in `asyncloop`.
4. Current note: anchored gate set is green on moving head (`599f7e43`) with diff-base `06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e`:
   - `gate_fast` PASS (`line=1.0000`, `branch=0.9245`)
   - `gate_core` PASS
   - `gate_reconciliation` PASS (`114/114`)
   - `gate_release` PASS on local v2 matrix env (`127.0.0.1:55432`)

### Lane C: Consistency hardening (throughput lane)
1. Prefer small, evidence-backed slices with low blast radius.
2. Prioritize dedupe and role-parity consistency (dealer/supplier behavior symmetry).
3. Keep v2 contract stability: no public API naming drift.
4. Canonicalize internal mismatch metadata toward `partner*` while preserving external role-specific payload compatibility.
5. Latest landed slice: `c676bee9` dedupes async command lease bootstrap in `CommandDispatcher` through shared `startLease(...)`, reducing divergence risk across approval/fulfillment/dispatch/payroll flows while preserving exactly-once semantics and canonical idempotency propagation.

### Lane D: Completion-gate consolidation (closure lane)
1. Convert module-level evidence into explicit completion-gate closure packs.
2. Keep `docs/system-map/COMPLETION_GATES_STATUS.md` current after each gate-affecting tranche.
3. Do not claim safe-to-deploy until all `5/5` completion gates are marked `CLOSED`.

## Exit criteria
- Pending code-review queue is empty (all code commits reviewed).
- No unresolved high/critical findings remain.
- Final ledger gates pass on same closure evidence set.
- M8/M9 async hardening lane is complete and recorded in `asyncloop`.

## Latest closure evidence (2026-02-16)
1. `DIFF_BASE=06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh` -> PASS (`line=1.0000`, `branch=0.9245`)
2. `DIFF_BASE=06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_core.sh` -> PASS
3. `bash scripts/gate_reconciliation.sh` -> PASS
4. `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres bash scripts/gate_release.sh` -> PASS (`release_migration_matrix OK`, predeploy scans zero findings)
5. `cd erp-domain && mvn -B -ntp -Dtest=TS_RuntimeOrchestratorExecutableCoverageTest,TS_RuntimeAccountingReplayConflictExecutableCoverageTest test` -> PASS (`36/36`)
6. `DIFF_BASE=06d85e792d2a80cd9fc1f8e5dc15d6dfa15dd93e GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh` -> PASS (`line_ratio=0.9964`, `branch_ratio=0.9216`) after M8/M9 final dedupe tranche (`d618ee04`)
