# Async Loop Operations Runbook

Last reviewed: 2026-02-15
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
- Subagents are for commit review only. Main implementation/audit work stays in
  the primary agent.
- Maintain backlog floor: at least 3 `ready` slices in `asyncloop`.
- After a completed slice, immediately add a new concrete slice.
- Orchestrator routing/review must follow `agents/orchestrator-layer.yaml`.
- Decisions must be proof-backed (tests/guards/traces), not assumption-backed.
- Scope priority source is `docs/ERP_STAGING_MASTER_PLAN.md`.

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
