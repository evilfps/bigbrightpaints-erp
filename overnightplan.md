# Comprehensive Continuous Hardening Plan (All Modules + Cross-Module)

## Purpose
Create a never-ending, exhaustive hardening loop that validates every module, every endpoint, and every cross-module flow, including algorithms and business logic, with milestone commits after each completed epic.

## Global invariants
- Double-entry integrity for all journal entries (base currency balancing, FX rounding rules).
- Idempotency for all posting flows (references/idempotency keys).
- Company isolation on all queries and writes.
- Period lock enforcement with documented override rules.
- Role-based access for every endpoint with least privilege.
- Reference sequencing is unique and auditable.
- Status transitions are valid and deterministic.

## Operating loop (never-ending)
1. Scan: read controllers/services/entities for the epic’s scope.
2. Test: run targeted tests or integration paths.
3. Fix: minimal changes + tests.
4. Verify: re-run tests and re-check invariants.
5. Commit: milestone commit with verification notes.
6. Repeat.

## Epics (exhaustive, module by module)

### Epic A — Auth + RBAC (auth, rbac, portal)
- Login/logout/refresh token flows.
- Role claim propagation to controllers/services.
- All endpoint `@PreAuthorize` coverage.
- Dealer portal isolation and leakage checks.
- Negative tests for forbidden roles.
- Milestone commit: `audit: auth-rbac`

### Epic B — Company + Admin config (company, admin)
- Company context resolution.
- Default accounts and system settings usage.
- Admin-only endpoints enforced.
- Seed/default account validations.
- Milestone commit: `audit: company-admin`

### Epic C — Accounting core algorithms (accounting)
- Journal entry balancing, FX math, rounding tolerance.
- Reference uniqueness and duplicate handling.
- Period close/lock/reopen flows.
- Statement/aging algorithms and bucket boundaries.
- GST return computations and account mapping.
- Reconciliation (GL vs subledger).
- Milestone commit: `audit: accounting-core`

### Epic D — Sales + Invoice workflows (sales, invoice)
- Dealer lifecycle + credit limit enforcement.
- Sales orders: status transitions, idempotency, dealer filters.
- Dispatch confirm: revenue, discount, tax, AR postings.
- Invoice generation, numbering, and reversal handling.
- Sales returns and credit note behavior.
- Milestone commit: `audit: sales-invoice`

### Epic E — Purchasing workflows (purchasing)
- Supplier lifecycle + credit limit enforcement.
- Purchase orders, goods receipts, raw material purchases.
- Supplier filters + pagination ordering.
- AP postings and payment settlements.
- Purchase returns and reversal correctness.
- Milestone commit: `audit: purchasing`

### Epic F — Inventory + Production (inventory, production, factory)
- Raw material and finished goods valuation.
- Inventory movement journal entries.
- Production consumption/output balancing.
- Batch/lot integrity and idempotency.
- Work-in-progress account impact.
- Milestone commit: `audit: inventory-production`

### Epic G — HR + Payroll (hr, accounting)
- Payroll calculations and journal entries.
- Employee lifecycle and payroll access.
- Period lock enforcement for payroll postings.
- Milestone commit: `audit: hr-payroll`

### Epic H — Reports (reports)
- Trial balance and P&L correctness.
- Aging vs statement vs subledger alignment.
- Performance and caching validation.
- Milestone commit: `audit: reports`

### Epic I — Data + Migrations (data, admin, accounting)
- Migration idempotency and compatibility.
- Seed data integrity (accounts, defaults, tax).
- Backward compatibility of DTO changes.
- Milestone commit: `chore: data-migrations`

### Epic J — API contract verification (openapi)
- Sync `openapi.json` with param/response changes.
- Backward compatibility checks.
- Contract tests for critical endpoints.
- Milestone commit: `chore: openapi`

### Epic K — Performance + Query shaping (all)
- Pagination ordering and filter correctness.
- N+1 detection and EntityGraph tuning.
- Index usage review for hot paths.
- Milestone commit: `perf: query-tuning`

## Cross-module flows (full end-to-end)

### O2C (Order-to-Cash)
- Sales order → dispatch → invoice → AR posting → receipt/settlement.
- Validate AR balances, statements, aging buckets, GL reconciliation.

### P2P (Procure-to-Pay)
- PO → goods receipt → raw material purchase → AP posting → supplier payment.
- Validate inventory valuation + AP balances + GL reconciliation.

### Production (Plan-to-Produce)
- BOM → production order → raw material consumption → finished goods output.
- Validate inventory movement and accounting impact.

### R2R (Record-to-Report)
- Period close → lock → reopen and reversal handling.
- Validate GL totals vs subledgers and statements.

### Returns + Reversals
- Sales return, purchase return, credit/debit notes.
- Verify reference integrity and reversal correctness.

## Algorithm & business-logic checkpoints (always-on)
- Pricing and discount allocation (line vs order).
- Tax computations (GST inclusive/exclusive, rate caps).
- FX conversion and settlement math.
- Aging bucket boundaries and due-date logic.
- Ledger running balance accuracy.
- Idempotency + reference generation rules.
- Period lock enforcement including overrides.

## Per-module endpoint checklist (every endpoint)

### accounting
- `journal-entries` list filters and exclusivity.
- `journal-entries` create: lines, accounts, period lock.
- `receipts`/`settlements`: cash allocation correctness.
- `statements`/`aging`: ledger math + PDF endpoints.
- `periods` close/lock/reopen + reversal entries.
- `gst` returns accuracy.

### sales
- `dealers` CRUD + receivable account exposure.
- `orders` list filters + status transitions.
- `dispatch` confirm: revenue/tax/discount accounting.
- `promotions`/`targets`/`credit-requests`: RBAC integrity.

### invoice
- Invoice creation, numbering, AR impact.
- Refunds/credit notes integration.

### purchasing
- `purchase-orders` list filter by supplier.
- `goods-receipts` list filter by supplier.
- `raw-material-purchases` list filter by supplier.
- Returns and AP reconciliation.

### inventory
- Movement valuation and batch integrity.
- Finished goods valuation + COGS postings.

### production/factory
- Production status transitions.
- Consumption vs output balance.

### hr/payroll
- Payroll entry accuracy + access checks.

### auth/rbac/portal
- Dealer portal isolation + admin-only endpoints.

### reports
- Trial balance + P&L alignment with GL.
- Aging/statement consistency.

## Testing matrix (repeat per epic)
- Unit tests for algorithms.
- Integration tests for flow integrity.
- Regression tests for fixed bugs.
- Contract tests for API changes.
- Performance checks for list endpoints.

## Milestone commit rule
- After each epic, commit with clear scope and verification notes.
- Never proceed to next epic without a commit.

## Execution log (2026-01-24)
- Async full suite: `mvn -B -ntp verify` → BUILD SUCCESS (268 run, 0 failures, 0 errors, 4 skipped, 08:54).
- Manual API smoke (seed profile): created dealer/supplier, verified dealer list includes receivable account fields, posted dealer/supplier journal entries, validated journal entry filters, and confirmed sales/purchasing list filters accept dealerId/supplierId.
- Staging boot (prod+seed + validation enabled): required `ERP_LICENSE_KEY` and dispatch account envs; added `V111__backfill_production_product_metadata.sql` + base currency seeding, then reran successfully (config health checks passed); login + dealer list verified.
- Async verify rerun: BUILD SUCCESS (268 run, 0 failures, 0 errors, 4 skipped, 07:38).
- Added 50 targeted unit tests for core utilities/DTOs + references; ran `mvn -B -ntp -Dtest=MoneyUtilsTest,PasswordUtilsTest,SystemRoleTest,SalesOrderReferenceTest,DashboardWindowTest,CompanyContextHolderTest,ApiResponseTest,PageResponseTest test` → BUILD SUCCESS.
- Bug hunt pass: scanned auth/password policy + sales reference utilities; flagged idempotency hash scale sensitivity (R-009) in addition to existing open item R-006.
- Added 25 more targeted unit/regression checks and ran `mvn -B -ntp -Dtest=PasswordPolicyTest,SalesOrderRequestTest,ErrorResponseTest,DomainEventTest,InventoryReferenceTest,ApiResponseTest,PageResponseTest,SalesOrderReferenceTest,DashboardWindowTest test` → BUILD SUCCESS.
- Added accounting business-logic unit tests (periods, accounts, journal entries, settlements, defaults) and ran `mvn -B -ntp -Dtest=AccountingPeriodTest,AccountTypeTest,JournalEntryTest,AccountTest,PartnerSettlementAllocationTest,CompanyDefaultAccountsServiceTest test` → BUILD SUCCESS (44 run).
- Async full suite with extended timeout: `nohup mvn -B -ntp -DforkedProcessTimeoutInSeconds=600 verify` → BUILD SUCCESS (385 run, 0 failures, 0 errors, 4 skipped, 07:30).
