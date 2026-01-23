# Overnight Hardening Plan (7–8 hours)

## Goal
Ship the AR/AP per-party improvements safely while continuously hunting for adjacent flaws and regressions.

## Constraints / guardrails
- Follow `docs/codex-cloud-ci-debugging-plan.md` for CI failures and long-running tasks.
- No history rewrite or destructive git commands.
- No new accounting “features” while fixing accounting logic; stick to correctness/stability.
- Use async execution for long tasks (see commands below) to avoid blocking.

## High-level phases (timeboxed)

### Phase 0 — Setup & baseline capture (0:00–0:30)
- Record current git status and diff snapshot for traceability.
- Confirm target scope: AR/AP per-party permissions + filters + mapping exposure + dealer order filters.
- Update/verify OpenAPI changes if the API spec is a deployment artifact.
- Prep async test runner commands and log locations.

### Phase 1 — Implement required API + auth changes (0:30–2:00)
- **Dealers**: add `ROLE_ACCOUNTING` for list/search/create/update + ledger/aging/invoices.
- **Dealer alias endpoints**: add `ROLE_ACCOUNTING` on `/api/v1/sales/dealers` + `/sales/dealers/search` or remove alias dependency.
- **Journal entries**: add `supplierId` filter with dealer/supplier mutual exclusivity enforcement.
- **Purchasing lists**: add optional `supplierId` to purchase orders, goods receipts, and raw material purchases.
- **Dealer response mapping**: expose `receivableAccountId/Code` in dealer list/search/response payloads.
- **Sales orders**: add optional `dealerId` filter + allow `ROLE_ACCOUNTING` read-only access to list endpoint.

### Phase 2 — Static audit pass (2:00–3:30)
- Permission matrix audit: list all routes touched, verify intended roles only.
- Data integrity audit: confirm supplier/dealer lookups are scoped by company.
- Code scan for edge cases:
  - `rg -n "dealerId|supplierId|receivableAccount"` for related endpoints.
  - Validate any `Optional`/null assumptions in new paths.
- Review for N+1 or lazy-loading regressions in list endpoints.

### Phase 3 — Rigorous testing (3:30–5:30)
- **Async full suite** (per CI entrypoint):
  - `nohup mvn -B -ntp verify > /tmp/ci-verify.log 2>&1 & echo $! > /tmp/ci-verify.pid`
  - Tail: `tail -n 120 /tmp/ci-verify.log`
  - Check: `ps -p $(cat /tmp/ci-verify.pid)`
- **Targeted tests** (when failures appear):
  - `mvn -Dtest=<FailingTest> test`
  - Follow CI debugging checklist in `docs/codex-cloud-ci-debugging-plan.md`.
- **Accounting verification**: walk the checklist in `docs/codex-cloud-ci-debugging-plan.md` (AR/AP aging, statements, idempotency, period lock).

### Phase 4 — Fix + regression loop (5:30–7:30)
- For each failure:
  1. Identify first failing test and capture stack trace.
  2. Classify failure (logic vs nondeterministic vs infra vs data).
  3. Reproduce minimally.
  4. Implement fix + add/adjust tests.
  5. Re-run focused tests; then re-check full suite if time permits.
- Re-run permissions + data integrity audit after each fix.

### Phase 5 — Final validation & packaging (7:30–8:00)
- Verify API responses for dealer/supplier filters with a few manual requests.
- Ensure no uncommitted changes.
- Summarize changes and test results for handoff.

## Continuous “find-and-fix” loop (run until time budget)
1. **Scan**: new endpoints + adjacent modules for similar patterns.
2. **Test**: run focused tests or small integration flows.
3. **Fix**: implement minimal corrections + tests.
4. **Validate**: re-run relevant tests and cross-check accounting checklist.
5. **Repeat**.

## Risk hotspots to keep cycling on
- Dealer/supplier access control boundaries.
- Journal entry filtering and period lock enforcement.
- Discount/FX settlement correctness and cash math.
- Raw material purchase + goods receipt supplier linkage.
- Statement vs aging alignment and cross-ledger reconciliation.

## Expected deliverables by morning
- All required backend changes merged and verified.
- A clean CI run (or documented failures with exact stack traces and root causes).
- Summary of any remaining known issues and next steps.
