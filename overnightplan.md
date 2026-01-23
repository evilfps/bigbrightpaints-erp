# Long-Run Hardening Plan (Continuous)

## Goal
Ship the AR/AP per-party improvements safely while continuously hunting for adjacent flaws and regressions, and unify accounting processes across modules.

## Constraints / guardrails
- Follow `docs/codex-cloud-ci-debugging-plan.md` for CI failures and long-running tasks.
- No history rewrite or destructive git commands.
- No new accounting “features” while fixing accounting logic; stick to correctness/stability.
- Use async execution for long tasks (see commands below) to avoid blocking.

## High-level phases (milestone-driven)

### Phase 0 — Setup & baseline capture
- Record current git status and diff snapshot for traceability.
- Confirm target scope: AR/AP per-party permissions + filters + mapping exposure + dealer order filters.
- Update/verify OpenAPI changes if the API spec is a deployment artifact.
- Prep async test runner commands and log locations.
- Establish milestone commit message conventions (e.g., `feat:`, `fix:`, `audit:`, `test:`).

### Phase 1 — Implement required API + auth changes
- **Dealers**: add `ROLE_ACCOUNTING` for list/search/create/update + ledger/aging/invoices.
- **Dealer alias endpoints**: add `ROLE_ACCOUNTING` on `/api/v1/sales/dealers` + `/sales/dealers/search` or remove alias dependency.
- **Journal entries**: add `supplierId` filter with dealer/supplier mutual exclusivity enforcement.
- **Purchasing lists**: add optional `supplierId` to purchase orders, goods receipts, and raw material purchases.
- **Dealer response mapping**: expose `receivableAccountId/Code` in dealer list/search/response payloads.
- **Sales orders**: add optional `dealerId` filter + allow `ROLE_ACCOUNTING` read-only access to list endpoint.

### Phase 2 — Static audit pass
- Permission matrix audit: list all routes touched, verify intended roles only.
- Data integrity audit: confirm supplier/dealer lookups are scoped by company.
- Code scan for edge cases:
  - `rg -n "dealerId|supplierId|receivableAccount"` for related endpoints.
  - Validate any `Optional`/null assumptions in new paths.
- Review for N+1 or lazy-loading regressions in list endpoints.
- Milestone commit: `audit: auth + data-scope pass`

### Phase 3 — Rigorous testing
- **Async full suite** (per CI entrypoint):
  - `nohup mvn -B -ntp verify > /tmp/ci-verify.log 2>&1 & echo $! > /tmp/ci-verify.pid`
  - Tail: `tail -n 120 /tmp/ci-verify.log`
  - Check: `ps -p $(cat /tmp/ci-verify.pid)`
- **Targeted tests** (when failures appear):
  - `mvn -Dtest=<FailingTest> test`
  - Follow CI debugging checklist in `docs/codex-cloud-ci-debugging-plan.md`.
- **Accounting verification**: walk the checklist in `docs/codex-cloud-ci-debugging-plan.md` (AR/AP aging, statements, idempotency, period lock).

### Phase 4 — Fix + regression loop
- For each failure:
  1. Identify first failing test and capture stack trace.
  2. Classify failure (logic vs nondeterministic vs infra vs data).
  3. Reproduce minimally.
  4. Implement fix + add/adjust tests.
  5. Re-run focused tests; then re-check full suite if time permits.
- Re-run permissions + data integrity audit after each fix.

### Phase 5 — Final validation & packaging
- Verify API responses for dealer/supplier filters with a few manual requests.
- Ensure no uncommitted changes.
- Summarize changes and test results for handoff.
- Milestone commit: `chore: final validation notes`

## Continuous “find-and-fix” loop (never-ending)
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

## Epic backlog (expand as needed)

### Epic A — Partner-centric AR/AP unification
- Verify all partner-linked endpoints accept partner filters consistently.
- Ensure dealer/supplier postings are always paired with correct subledger links.
- Enforce mutual exclusivity where dealer vs supplier filters can conflict.
- Add clear error messaging for invalid combinations.
- Milestone commit: `feat: partner filter consistency`

### Epic B — Accounting process gap closure
- Map end-to-end flows: O2C, P2P, returns, discounts, reversals, period close.
- Cross-check journal entry references against flow expectations.
- Validate that every business flow has a consistent posting pattern.
- Investigate any missing or mismatched account mappings.
- Milestone commit: `audit: accounting process map`

### Epic C — Discount/FX correctness hardening
- Reconcile discount postings with revenue/AR amounts for dispatch confirmations.
- Validate FX settlement cash math vs journal lines.
- Add tests for edge cases (negative discounts, tolerance bounds).
- Milestone commit: `fix: settlement/discount edge cases`

### Epic D — Inventory → accounting linkage validation
- Check inventory movements generate required journal entries.
- Verify COGS and inventory valuation postings on shipments and returns.
- Ensure idempotent references prevent duplicate postings.
- Milestone commit: `audit: inventory-accounting linkage`

### Epic E — Statement vs aging reconciliation
- Compare aging totals against statement closing balances for same period.
- Validate bucket boundaries and credit memo behavior.
- Add regression tests for aging/statement alignment.
- Milestone commit: `test: aging vs statement alignment`

### Epic F — Permissions + segregation audit
- Validate each role’s read/write boundaries per module.
- Ensure accounting has read-only access where required.
- Add tests for forbidden role access on sensitive endpoints.
- Milestone commit: `audit: rbac boundaries`

### Epic G — API contract verification
- Update `openapi.json` where responses/params changed.
- Validate response payloads are backward compatible where required.
- Add contract tests for critical endpoints.
- Milestone commit: `chore: openapi sync`

### Epic H — Regression test expansion
- Add focused tests for partner filters on journal entries and purchasing lists.
- Add tests for dealer receivable mapping exposure.
- Add tests for sales order dealer filtering.
- Milestone commit: `test: partner filter coverage`

### Epic I — Data quality + migration safety
- Review migrations and seeders for accounting defaults and vendor AR/AP setup.
- Validate existing records remain compatible with new response fields.
- Milestone commit: `chore: data compatibility`

### Epic J — Performance + query shaping
- Review list endpoints for pagination/ordering correctness.
- Check for N+1 patterns in partner-filtered queries.
- Add or adjust EntityGraphs where needed.
- Milestone commit: `perf: list endpoint tuning`

## Milestone commit rule
- After each epic or milestone, commit with a clear prefix and scope.
- No milestone proceeds without a commit and short notes on verification.
