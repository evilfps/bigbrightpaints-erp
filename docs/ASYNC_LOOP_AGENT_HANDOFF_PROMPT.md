# Async Loop Continuation Prompt (Copy/Paste)

Use the prompt below to resume the same autonomous loop without losing context.

```text
Resume the async predeploy audit loop on branch `async-loop-predeploy-audit` in `/home/realnigga/Desktop/orchestrator_erp`.

Mission:
- Continue until ERP is staging/predeployment-ready with strong accounting trust and abuse resistance.
- Focus on static review first, then minimal safe patches with targeted invariant tests.
- Prioritize: accounting integrity, business-logic abuse, security boundaries, cross-module invariants, duplicate logic drift, and release readiness.

Mandatory operating rules:
- Read `asyncloop` first and continue the latest `active_in_progress` slice.
- Follow `docs/ASYNC_LOOP_OPERATIONS.md`.
- Keep backlog floor >= 3 ready slices; whenever one slice is completed, add a new concrete slice.
- Every code change must be committed.
- After every commit:
  1) run `codex review --commit <sha>` if available,
  2) spawn exactly one review-only subagent for deep regression review.
- Do not use subagents for implementation work.
- Use Flyway V2 only for new migrations (`db/migration_v2`, `flyway_schema_history_v2`).
- If user asks questions mid-loop, answer briefly and continue work immediately.

Current context to start from:
- Last completed code commit in dispatch/invoice linkage chain: `09af3991`
  - enforces single-slip gate in `InvoiceService.issueInvoiceForOrder` before writing `order.fulfillmentInvoiceId`.
  - includes regression test that keeps `fulfillmentInvoiceId` unset for multi-slip existing-invoice flow.
- Prior related commits:
  - `521d8e07` gates order-level invoice marker writes to single-slip in dispatch paths.
  - `26ddee67` blocks order-level AR linkage drift on multi-slip replay.
  - `ea3bd276` recomputes single-slip status at decision points.
  - `e277cde0` anchored replay overrides with required reason.
  - `8b7b3e73` closes multi-slip order-journal replay bypass and preserves override audit metadata.
  - `9a8c2c42` adds replay mismatch test for existing AR journal totals.
- Latest targeted verification already passing:
  - `mvn -B -ntp -Dtest=SalesServiceTest,InvoiceServiceTest test` (30 tests, 0 failures).

Immediate next actions:
1) Confirm there are no uncommitted owned changes from the previous step.
2) Ensure `asyncloop` review evidence for `09af3991` is complete and consistent.
3) Continue active slice `M5-S5`:
   - TOCTOU risk reduction for single-vs-multi slip marker decisions,
   - stale order-level marker cleanup/backfill strategy for drifted records.
4) Commit each fix with targeted tests, then run commit review + review subagent.
5) Append every step to `asyncloop` with timestamp, elapsed runtime, evidence, and replenished queue.

Do not stop unless explicitly told to stop.
```
