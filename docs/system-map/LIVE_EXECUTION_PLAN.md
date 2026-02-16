# Live Execution Plan (Async-Loop)

Last updated: 2026-02-16
Mode: Continuous

## Goal
- Complete async-loop closure with final ledger gates green and reviewer queue fully drained.

## Active constraints
- Reviewer subagent dispatch currently blocked by external cap (`agent thread limit reached (max 6)`).
- Direct `codex review --commit <sha>` fallback is active while the cap persists.
- Current live review in flight: `none (queue clear as of 77eba61a)`.

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
4. Current note: latest anchored `gate_fast` truth tests passed; changed-files coverage remains open on active diff-base (`line=0.3856`, `branch=0.4783`) and is being handled as explicit coverage-uplift work.

### Lane C: Consistency hardening (throughput lane)
1. Prefer small, evidence-backed slices with low blast radius.
2. Prioritize dedupe and role-parity consistency (dealer/supplier behavior symmetry).
3. Keep v2 contract stability: no public API naming drift.
4. Canonicalize internal mismatch metadata toward `partner*` while preserving external role-specific payload compatibility.

## Exit criteria
- Pending code-review queue is empty (all code commits reviewed).
- No unresolved high/critical findings remain.
- Final ledger gates pass on same closure evidence set.
