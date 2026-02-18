# Async Loop Operations Runbook

Last reviewed: 2026-02-17
Owner: Orchestrator Agent

This runbook defines the non-stop autonomous workflow used in this repository
to move the ERP toward staging/predeployment readiness.

## Mission
- Reach deployment-safe behavior with emphasis on:
  - accounting correctness and idempotency,
  - business-logic abuse resistance,
  - cross-module invariant safety,
  - security boundaries (auth/RBAC/company isolation),
  - migration/runtime release readiness.

## Hard Rules
- Work on branch `async-loop-predeploy-audit`.
- Keep all code/schema changes inside `erp-domain` unless a gate script/doc update
  is strictly required.
- Use Flyway V2 only for new migration work:
  - location: `erp-domain/src/main/resources/db/migration_v2`
  - history table: `flyway_schema_history_v2`
- Every change must be committed (no loose code changes left behind).
- After every runtime/config/schema/test commit:
  - run commit review (`codex review --commit <sha>` when available),
  - spawn one review-only subagent for deep regression/abuse review.
- Docs-only commit exception:
  - skip commit review/subagent,
  - run `bash ci/lint-knowledgebase.sh` and log pass status.
  - `lint-knowledgebase` now fail-closes if ticket status metadata drifts (`scripts/check_ticket_status_parity.py`).
- Subagents are for commit review only. Main implementation/audit work stays in
  the primary agent.
- Maintain backlog floor: at least 3 `ready` slices in `asyncloop`.
- After a completed slice, immediately add a new concrete slice.
- Orchestrator routing/review must follow `agents/orchestrator-layer.yaml`.
- Decisions must be proof-backed (tests/guards/traces), not assumption-backed.
- Scope priority source is `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md`.

## Section 14.3 Final Gate Protocol (Stage-065 One-SHA Closure)
When closing the async-loop final ledger gate (ERP Staging Plan Section 14.3) for `TKT-ERP-STAGE-065`:
1. Refresh to integration `HEAD` and pin:
   - `RELEASE_HEAD_SHA=$(git rev-parse HEAD)`
   - immutable `RELEASE_ANCHOR_SHA` for diff-based validation.
2. Run strict fast-lane validation with the pinned anchor and enforce non-vacuous changed-file coverage (release validation mode fails closed if coverage is vacuous):
   - `RELEASE_ANCHOR_SHA=<40-char-sha> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
   - Optional compatibility form (must resolve to the same commit): `DIFF_BASE=<RELEASE_ANCHOR_SHA> RELEASE_ANCHOR_SHA=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
   - `gate_fast` release mode now fails closed when the anchor is missing, not a fixed 40-character SHA, or when `DIFF_BASE` and `RELEASE_ANCHOR_SHA` do not match.
   - Capture `artifacts/gate-fast/changed-coverage.json` showing `"vacuous": false` as part of the ledger evidence.
3. Execute the remaining ledger gates without changing `HEAD`:
   - `bash scripts/gate_core.sh`
   - `bash scripts/gate_reconciliation.sh`
   - `bash scripts/gate_release.sh`
4. Enforce the runtime quarantine contract before accepting `gate_reconciliation`/`gate_release` outcomes:
   - `scripts/test_quarantine.txt` entries use: `<test_path> | owner=<owner> | repro=<repro> | start=YYYY-MM-DD | expiry=YYYY-MM-DD`.
   - Required keys are `owner`, `repro`, `start`, and `expiry`.
   - `expiry` must be `>= start` and `<= start + 14 calendar days`; missing/invalid/expired metadata fails closed and blocks Section 14.3 closure.
5. For all four gates, append one-SHA proof fields in `asyncloop`:
   - `gate_name`
   - `head_sha_before` and `head_sha_after`
   - `exit_code`
   - log + artifact paths
6. Fail closed if any recorded SHA differs from `RELEASE_HEAD_SHA`; mixed-SHA evidence is invalid for Stage-065 closure.
7. Rotate `RELEASE_ANCHOR_SHA` only after all ledger gates pass and one-SHA evidence is recorded.
8. Before ticket closure, run `python3 scripts/check_ticket_status_parity.py` (or `bash ci/lint-knowledgebase.sh`) so `ticket.yaml`, `SUMMARY.md`, and `TIMELINE.md` status markers cannot drift.

## Section 14.4 Deterministic Verify Strategy (High-Signal Lanes)
For autonomous codex-exec operation, use the smallest fail-closed lane that matches slice risk:
1. `docs_lane` (docs-only): run `bash ci/lint-knowledgebase.sh` only.
2. `fast_lane` (default code slices): run `bash scripts/gate_fast.sh`.
3. `strict_lane` (accounting/auth/migrations/orchestrator semantics): run `bash scripts/gate_fast.sh` then `bash scripts/gate_core.sh`.
4. `ledger_lane` (Section 14.3 closure only): run anchored `gate_fast` release mode plus `gate_core`, `gate_reconciliation`, and `gate_release` on the same `HEAD`.

Lane rules:
- Promote lanes only when changed-path risk or failure class requires stronger proof.
- Do not run `gate_reconciliation`/`gate_release` for routine slices.
- No blind reruns: rerun a lane only after a concrete change (code/config/docs/quarantine metadata).
- Flake and quarantine policies stay fail-closed; never bypass with ad-hoc retries.

## Section 14.5 Autonomous Operator Workflow (Codex-Exec)
1. Capture `VERIFY_HEAD_SHA=$(git rev-parse HEAD)` and lane base (`DIFF_BASE` or `RELEASE_ANCHOR_SHA`) before running checks.
2. Record selected lane (`docs_lane`, `fast_lane`, `strict_lane`, `ledger_lane`) in `asyncloop`.
3. Execute lane commands in deterministic order and store artifact paths under `artifacts/gate-*`.
4. Append command, exit status, SHA, and artifact references to `asyncloop` immediately after each run.
5. If a failure repeats on unchanged `HEAD`, fail closed, open a blocker entry, and escalate at `R2` instead of looping retries.
6. Before slice closure, run `bash ci/lint-knowledgebase.sh` so ticket metadata parity remains enforced.

## Section 14.6 Merge-Ready Ticket Sequencing and Deployment-Gate Discipline
For integration PR and merge-queue operation:
1. Sequence tickets by dependency evidence, not by local completion timestamp.
2. Before opening or updating a merge-ready PR, confirm base freshness against integration branch (`git fetch` + rebase/merge) and re-run required lane checks on the refreshed `HEAD`.
3. If PR conflict appears in shared workflow/rule files, fail closed:
   - mark ticket `blocked`,
   - capture conflicting paths + upstream SHA in evidence,
   - reopen only after replaying on the latest integration base.
4. Do not carry forward stale gate claims from pre-conflict SHAs; gate evidence is valid only for the post-resolution `HEAD`.
5. For deployment gating, only accept final Section 14.3 closure evidence produced on integration `HEAD` after merge sequencing is complete.

## Section 14.7 Stage-064 Migration/Rollback Rehearsal Parity
For `TKT-ERP-STAGE-064` before one-SHA release closure work:
1. Pin `RELEASE_CANDIDATE_SHA=$(git rev-parse HEAD)` and treat it as the rehearsal `release_anchor_sha`.
2. Run canonical migration rehearsal:
   - `bash scripts/release_migration_matrix.sh --migration-set v2`
3. Record rehearsal outputs under artifacts and append command + SHA evidence in `asyncloop`.
4. If a rollback drill is executed, log it using `docs/runbooks/rollback.md` evidence fields and keep `release_anchor_sha` identical to Step 1.
5. Fail closed if migration/rollback evidence references mixed SHAs; resolve parity before advancing to `TKT-ERP-STAGE-065`.

## Section 14.8 Stage-066 Final Go/No-Go Evidence Pack
When closing `TKT-ERP-STAGE-066`:
1. Pin immutable `GO_NO_GO_SHA=$(git rev-parse HEAD)` on integration head.
2. Verify prerequisite P0 tickets are fully merged and done:
   - `tickets/TKT-ERP-STAGE-061/ticket.yaml`
   - `tickets/TKT-ERP-STAGE-062/ticket.yaml`
   - `tickets/TKT-ERP-STAGE-065/ticket.yaml`
3. Run minimum closure checks on `GO_NO_GO_SHA`:
   - `bash ci/lint-knowledgebase.sh`
   - `bash ci/check-architecture.sh`
   - `bash ci/check-enterprise-policy.sh`
4. Record unresolved P0 blocker matrix in ticket evidence; closure is valid only when blocker count is exactly zero.
5. Update `docs/approvals/R2-CHECKPOINT.md` with scope, authority, expiry, and evidence tied to `GO_NO_GO_SHA`.
6. Treat this checkpoint as `R2 complete / R3 pending`; production go-live still requires explicit human `R3` decision.


## Execution Loop (One Iteration)
1. Pick highest-risk `in_progress` or top `ready` slice from `asyncloop`.
2. Perform static abuse-oriented review on impacted services/controllers/repos.
3. Implement minimal safe patch (prefer invariants + idempotency + validation).
4. Add targeted negative and replay tests.
5. Run focused Maven test matrix for changed flows.
6. Commit with:
  - concise subject,
  - bullet comments describing exactly what changed and why.
7. Run commit review + review subagent.
8. If review finds issues:
  - fix immediately,
  - re-test,
  - commit follow-up,
  - re-run review.
9. Append evidence to `asyncloop`:
  - commit id,
  - tests run/results,
  - review findings/disposition,
  - active slice + replenished queue.
10. Move to next slice without waiting for user prompts.

## Planning Contract
- Keep deep plan slices; avoid generic tasks.
- Slice format:
  - module and service names,
  - abuse vector,
  - invariant expected,
  - tests to prove closure.
- Example slice naming:
  - `M5-S3 dispatch/invoice/accounting exact-link invariant sweep`
  - `M2-S11 settlement replay mismatch matrix parity`

## Evidence Logging Contract (`asyncloop`)
For each commit, append:
- `Completed Slice (Committed)` with commit SHA and summary bullets.
- `Verification` commands and pass/fail totals.
- `Post-Commit Review Evidence`:
  - `codex review --commit <sha>` summary,
  - subagent id and findings.
- `Loop Update`:
  - `tracked_now_ts`,
  - `tracked_elapsed`,
  - current `active_in_progress`.
- `Queue Rotation (Auto-Replenish)` with at least 3 `ready` items.

## Recovery If Session Interrupts
1. Open `asyncloop` first.
2. Resume latest `active_in_progress`.
3. Check uncommitted changes:
  - if owned by current slice, continue and finish.
  - if unrelated unexpected edits appear, stop and report.
4. Continue commit/review cadence from latest recorded point.
5. If pausing mid-slice, append a `Pause Checkpoint` block in `asyncloop` with:
  - owned uncommitted file paths,
  - last targeted verification command and concrete failure line,
  - immediate next action to resume the same slice.

## Current High-Priority Continuation Targets
- M5 dispatch/invoice/accounting replay and double-post defenses.
- M2 settlement replay mismatch parity across all replay call sites.
- M6 duplicate idempotency helper consolidation map.
- M9 accounting query hotspot/index validation under replay-like load.
- M7 report-to-journal linkage traceability assertions.
