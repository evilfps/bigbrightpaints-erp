# Async Loop Continuation Prompt (Copy/Paste)

Use the prompt below to resume the autonomous predeploy loop from the current stop point.

```text
Resume the async predeploy audit loop on branch `async-loop-predeploy-audit` in `/home/realnigga/Desktop/orchestrator_erp`.

Start procedure:
1) Read `asyncloop` fully.
2) Read `docs/ASYNC_LOOP_OPERATIONS.md`.
3) Continue from latest `active_in_progress` (do not reopen completed slices).

Mission:
- Continue toward staging/predeployment readiness with emphasis on accounting integrity, business-logic abuse resistance, security boundaries, cross-module invariants, duplicate-logic cleanup, and release safety.
- Prefer static review first, then minimal safe patches with targeted negative/idempotency tests.

Mandatory operating rules:
- Every code change must be committed.
- After every commit:
  1) run `codex review --commit <sha>` (capture final verdict when possible),
  2) run exactly one review-only subagent.
- Subagents are review-only; implementation remains in main agent.
- Keep backlog floor >= 3 ready slices in `asyncloop`.
- After closing one slice, immediately add another concrete ready slice.
- Use Flyway V2 only for any new migration/index work.

Current continuity anchors:
- Latest commits (newest first):
  - `f9fd898f` fix(factory): guard bulk-pack movement journal relink drift
  - `0355149b` fix(factory): link pack inventory movements to posted journals
  - `ea5de159` docs(async-loop): backfill M13-S5 evidence and queue rotation
  - `c50ade02` fix(catalog): avoid wildcard cache purge on unresolved brand
- Latest targeted verification:
  - `cd erp-domain && mvn -B -ntp -Dtest=BulkPackingImportedCatalogPackagingIT,ProductionCatalogRawMaterialInvariantIT test`
  - result: pass (10 tests, 0 failures, 0 errors)
- Latest active slice:
  - `M13-S8 catalog import stale-row cache cleanup acceptance test matrix (legacy drifted rows + retry replay)`

Immediate next actions:
1) Confirm no owned uncommitted changes exist.
2) Re-check pending `codex review` stream outcomes for `0355149b` / `f9fd898f` and keep ledger evidence aligned.
3) Continue `M13-S8`:
   - characterize stale-marker cleanup behavior for already drifted import/cache rows,
   - add acceptance tests for retry/replay on drifted records,
   - patch minimally if invariant violations are found.
4) Keep queue rotation healthy (1 in_progress + >=3 ready) and append all evidence to `asyncloop`.

Do not stop unless explicitly told to stop.
```
