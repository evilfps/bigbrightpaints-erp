# Deep Debugging Plan (Production Confidence)

This plan is grounded in what the repo already proves via automated tests + runbooks, and extends it into a repeatable, production-like verification sequence.

Scope: `erp-domain` (Spring Boot) with Postgres + Flyway, RabbitMQ, and multi-module ERP domains.

---

## A) What we already know is true (based on existing tests/invariants)

The following test classes already execute against real Postgres via Testcontainers (`com.bigbrightpaints.erp.test.AbstractIntegrationTest`) and provide strong correctness signals.

### Golden-path invariants (cross-module)

- `com.bigbrightpaints.erp.invariants.ErpInvariantsSuiteIT`
  - Guarantees: end-to-end “golden paths” for Record-to-Report, Order-to-Cash, Procure-to-Pay, Produce-to-Stock, and Hire-to-Pay.
  - Explicitly asserts (examples):
    - journals balance within tolerance
    - journal ↔ source-document linkage exists and is same-company
    - dispatch and settlements are idempotent on replay (no duplicate movements/journals)
    - stock does not go negative for the exercised SKUs
    - dealer ledger entries contain invoice metadata and update after settlement
    - payroll advance deductions and postings stay consistent and reversible

- `com.bigbrightpaints.erp.invariants.ErpInvariantAssertions`
  - Guarantees: reusable invariant checks used by the invariants suite:
    - `assertJournalBalanced(entryId)` (debits==credits within 0.01)
    - `assertJournalLinkedTo(sourceType, sourceId)` for `INVOICE`, `PURCHASE`, `PAYROLL_RUN`, `PACKAGING_SLIP`, `INVENTORY_MOVEMENT` (and verifies same-company linkage via `JournalEntryRepository.findCompanyIdById`)
    - `assertReversalCreatesBalancedInverse(originalEntryId)` (reversal is balanced and per-account net is inverted)
    - `assertNoNegativeStock(companyId, skuOrProductCode)` for finished goods + raw materials
    - `assertSubledgerReconciles(controlAccountId, asOfDate)` (dealer/supplier ledgers reconcile to AR/AP control account via `TemporalBalanceService`)

### Accounting core controls

- `com.bigbrightpaints.erp.e2e.accounting.PeriodCloseLockIT`
  - Guarantees:
    - closing a period changes status to `CLOSED` and produces a posted closing JE
    - posting while locked/closed is rejected (even with admin override in the “closed” test)
    - reopening requires a reason, clears `closingJournalEntryId`, and restores posting ability

- `com.bigbrightpaints.erp.e2e.accounting.ReconciliationControlsIT`
  - Guarantees:
    - inventory reconciliation variance reaches zero after seeding the inventory control via a JE
    - AR reconciliation and AP reconciliation report `isReconciled=true` and `variance=0` for a seeded company (and return non-zero account/partner counts)

- `com.bigbrightpaints.erp.e2e.accounting.SettlementE2ETest`
  - Guarantees:
    - dealer settlements with discount/write-off/FX adjustments post a balanced journal
    - allocation rows persist and link to the invoice + journal
    - settlement validation rejects discounts without discount account
    - idempotency key reuses the existing settlement + prevents duplicate allocations

### Sales / O2C correctness signals

- `com.bigbrightpaints.erp.e2e.sales.GstInclusiveRoundingIT`
  - Guarantees:
    - GST-inclusive order computation stays internally consistent: `total == subtotal + gstTotal` and line totals align to paise rounding rules (including `gstRoundingAdjustment`)

- `com.bigbrightpaints.erp.e2e.sales.DealerLedgerIT`
  - Guarantees:
    - dealer ledger endpoint returns running balance and stable references for entries

### Purchasing / P2P correctness signals

- `com.bigbrightpaints.erp.e2e.accounting.ProcureToPayE2ETest`
  - Guarantees:
    - purchase creates RM batches + movements with correct reference type and journal linkage
    - supplier settlement clears (or reduces) outstanding and is reflected in domain state
    - purchase return records a `RETURN` movement and reduces stock

- `com.bigbrightpaints.erp.e2e.accounting.SupplierStatementAgingIT`
  - Guarantees:
    - supplier statement + aging endpoints reconcile to purchase/settlement activity for the period window

### Inventory / costing correctness signals

- `com.bigbrightpaints.erp.e2e.inventory.DispatchConfirmationIT`
  - Guarantees:
    - dispatch confirmation consumes stock and transitions packaging slips as expected (`DISPATCHED`, plus backorder behavior)
    - reserved stock and current stock evolve consistently through ship/backorder-clear flows

- `com.bigbrightpaints.erp.e2e.inventory.InventoryGlReconciliationIT`
  - Guarantees:
    - RM receipt posts inventory/AP movement and corresponding ledger updates
    - FG shipment cost uses dynamic costing layers (not hardcoded) and can be posted as inventory relief + COGS
    - inventory adjustments post a journal and move inventory balance into the variance account

### Factory / Production correctness signals

- `com.bigbrightpaints.erp.invariants.ErpInvariantsSuiteIT` (production golden path)
  - Guarantees:
    - production log + packing create inventory movements and finished goods stock increments, and preserve traceability by reference ids (e.g., `reference_type=PRODUCTION_LOG`)

- `com.bigbrightpaints.erp.e2e.production.FactoryPackagingCostingIT`
  - Guarantees:
    - production/packing costing posts balanced journals and links movements to their journals for production and packing references

- `com.bigbrightpaints.erp.e2e.accounting.WipToFinishedCostIT`
  - Guarantees:
    - WIP→FG completion journals and basic GL sign sanity hold across RM/WIP/FG/COGS in a simplified scenario

### HR / Payroll correctness signals

- `com.bigbrightpaints.erp.invariants.ErpInvariantsSuiteIT` (hire-to-pay golden + reversal)
  - Guarantees:
    - payroll run state progression via API: calculate → approve → post → mark-paid
    - posting journals balance and include advance clearing where applicable
    - reversal of payroll JE creates a balanced inverse

- `com.bigbrightpaints.erp.e2e.accounting.PayrollBatchPaymentIT`
  - Guarantees:
    - payroll batch processing produces payroll run + lines and a linked journal id; gross/net math matches expectations

### Auth / Admin / Security correctness signals

- `com.bigbrightpaints.erp.modules.auth.AuthControllerIT`
  - Guarantees:
    - login works, `/api/v1/auth/me` returns roles + permissions
    - refresh token cannot be reused after logout revocation

- `com.bigbrightpaints.erp.modules.auth.AuthHardeningIT`
  - Guarantees:
    - account lockout triggers after repeated failed login attempts; correct password is rejected during lock window

- `com.bigbrightpaints.erp.modules.auth.MfaControllerIT`
  - Guarantees:
    - MFA setup + activation work; login requires TOTP or recovery code; recovery codes are single-use

- `com.bigbrightpaints.erp.modules.admin.AdminUserSecurityIT`
  - Guarantees:
    - `/api/v1/admin/users` requires authentication and admin role
    - cross-company admin updates are blocked

### Orchestrator / outbox correctness signals

- `com.bigbrightpaints.erp.orchestrator.OrchestratorControllerIT`
  - Guarantees:
    - orchestrator order approval produces an outbox event
    - orchestrator payroll run produces payroll run records

- `com.bigbrightpaints.erp.orchestrator.service.CommandDispatcherTest`
  - Guarantees:
    - policy enforcement is invoked and domain events are enqueued with trace context

- `com.bigbrightpaints.erp.orchestrator.service.IntegrationCoordinatorTest`
  - Guarantees:
    - auto-approval state transitions are idempotent (no replay of reserved steps)
    - order status updates are consistent and journaling ids are stored on the order when created

### Performance guardrails (practical production confidence)

- `com.bigbrightpaints.erp.performance.PerformanceBudgetIT`
  - Guarantees:
    - sales order listing query-count is bounded (protects against N+1 regressions)
    - balance sheet endpoint completes within a time budget (3s in test env)

- `com.bigbrightpaints.erp.performance.PerformanceExplainIT`
  - Guarantees:
    - EXPLAIN plans are produced for hot queries (manual inspection aid; not an automatic “index used” assertion)

### API surface snapshot (drift detection)

- `com.bigbrightpaints.erp.OpenApiSnapshotIT`
  - Guarantees:
    - `/v3/api-docs` serves successfully and `openapi.json` can be regenerated.
  - Operational note: this test writes `openapi.json` at repo root; expect the worktree to become dirty if the spec changed.

---

## B) Confidence Ladder (verification levels)

Treat this as a strict ladder: do not proceed to the next level until the current level passes with the stated criteria.

### Level 0 — compile + checkstyle baseline

**Commands**
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`

**Pass criteria**
- Maven exits `0` for both commands.
- No compilation errors.
- Checkstyle runs in advisory mode and reports violations; record the count as a baseline signal. (Running without `-Dcheckstyle.failOnViolation=false` is expected to fail when violations exist.)

**Logs that prove success**
- Maven “BUILD SUCCESS”
- Checkstyle summary line including total violations (trend this number; don’t treat it as pass/fail today).

### Level 1 — full test suite

**Commands**
- `mvn -f erp-domain/pom.xml test`

**Pass criteria**
- Surefire: `Failures 0, Errors 0` (skips are acceptable only if already documented, e.g., disabled tests).
- Testcontainers Postgres starts successfully (Docker required).

**Logs that prove success**
- Surefire summary: `Tests run: …, Failures: 0, Errors: 0`
- Testcontainers container start messages (Postgres 16-alpine)

### Level 2 — focused module suites (Sales, Purchasing, Payroll, Production, Auth)

Run these to isolate regressions to a domain area quickly.

**Sales (Order-to-Cash)**
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test`

**Purchasing (Procure-to-Pay)**
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test`

**Payroll (Hire-to-Pay)**
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT test`

**Production (Factory/Produce-to-Stock)**
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test`

**Auth (Admin/Security)**
- `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test`

**Pass criteria**
- All selected tests pass with `Failures 0, Errors 0`.

**Logs that prove success**
- Surefire summary plus absence of stack traces in module suites.

### Level 3 — reconciliation controls / invariants

This is where ERP-grade confidence is won: you don’t just “create documents”, you prove they reconcile.

**Commands (tests)**
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ReconciliationControlsIT,InventoryGlReconciliationIT,PeriodCloseLockIT test`

**Prereq (compose DB, for API checks)**
- Seed a company + user + default accounts (needed for login and reconciliation endpoints):
  - `SQL_FILE=docs/ops_and_debug/seed_ops.sql`
  - `docker exec -e PGPASSWORD=erp -i erp_db psql -U erp -d erp_domain < "$SQL_FILE"`

**Commands (API, against a running app)**
- Month-end checklist: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/accounting/month-end/checklist"`
- Inventory valuation: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/reports/inventory-valuation"`
- Inventory reconciliation: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/reports/inventory-reconciliation"`
- Reconciliation dashboard: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/reports/reconciliation-dashboard?bankAccountId=1000"`

**Pass criteria**
- Month-end checklist reports no missing/unposted/unlinked items for the verified period window.
- Reconciliation endpoints return variance `<= 0.01` (see `erp-domain/docs/RECONCILIATION_CONTRACTS.md`).
- In tests: reconciliation and invariants suites pass.

**Logs/evidence that prove success**
- Checklist output shows “OK/zero” counts.
- Reconciliation API returns variance `0` (or within tolerance).
- SQL evidence (see module sections) finds no missing linkages and no unbalanced journals.

### Level 4 — docker-compose boot + health/readiness + smoke calls

This is the production-like confidence check: real DB, RabbitMQ, required config validation, and real HTTP calls.

**Commands**
- Boot (prod-like): `DB_PORT=55432 JWT_SECRET='…32+ bytes…' ERP_SECURITY_ENCRYPTION_KEY='…32+ bytes…' ERP_DISPATCH_DEBIT_ACCOUNT_ID=... ERP_DISPATCH_CREDIT_ACCOUNT_ID=... docker compose up -d --build`
- Health (liveness/readiness groups are exposed on the management port): `curl -fsS http://localhost:9090/actuator/health`
- Operator smoke script (auth + docs): `ERP_SMOKE_EMAIL=... ERP_SMOKE_PASSWORD=... ERP_SMOKE_COMPANY=... erp-domain/scripts/ops_smoke.sh`
  - If the script is not executable: `bash erp-domain/scripts/ops_smoke.sh`

**Pass criteria**
- `docker compose ps` shows all services healthy (`erp_db`, `erp_rabbit`, `erp_domain_app`).
- `/actuator/health` returns `status: UP` and advertises groups (readiness/liveness).
- Smoke script completes: health OK, `/v3/api-docs` OK, authenticated profile OK.

**Logs that prove success**
- App logs show Flyway migrate success and readiness UP.
- No sustained error loops for DB/Rabbit connectivity.

---

## C) Module-by-module plan

Each module section includes:
1) Critical behaviors to prove
2) Top failure modes to simulate (at least 5)
3) Exact commands to run
4) What evidence to collect
5) Go/No-Go criteria

### Sales (Order-to-Cash)

**1) Critical behaviors to prove**
- Order creation is idempotent (same idempotency key doesn’t create duplicates).
- Dispatch confirmation:
  - issues inventory exactly once
  - produces invoice + AR journal
  - (when configured) produces COGS/inventory relief journal(s)
- Dealer ledger entries exist for invoice AR and carry invoice metadata (invoice number, due date, payment status).
- Dealer settlement:
  - posts balanced journal (cash + AR + optional discount/write-off/FX)
  - updates invoice outstanding and dealer ledger payment status
  - is idempotent on replay
- Returns/credit notes create correctly linked reversing journals and any stock movements they imply.

**2) Top failure modes to simulate**
- Duplicate order on retry (idempotency key ignored) → multiple `sales_orders` / double reservation.
- Dispatch replay creates duplicate inventory movements or duplicate journals.
- Rounding drift between line totals, invoice totals, and journal totals (GST-inclusive edge cases).
- Dealer settlement replay creates duplicate allocations or posts a second settlement JE.
- Dealer ledger missing `invoiceNumber`/`dueDate` (aging becomes wrong) or points to wrong journal.
- Cross-company leakage: same JWT used with wrong `X-Company-Id` can read/write another company’s orders/ledger.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test`
- After docker-compose boot:
  - Login token:
    - `TOKEN=$(curl -fsS -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$ERP_SMOKE_EMAIL\",\"password\":\"$ERP_SMOKE_PASSWORD\",\"companyCode\":\"$ERP_SMOKE_COMPANY\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)[\"accessToken\"])')`
  - Create order (example payload must align with your catalog + dealer ids): `curl -fsS -X POST "$BASE_URL/api/v1/sales/orders" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $ERP_SMOKE_COMPANY" -H 'Content-Type: application/json' -d '{...}'`
  - Dispatch confirm: `curl -fsS -X POST "$BASE_URL/api/v1/sales/dispatch/confirm" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $ERP_SMOKE_COMPANY" -H 'Content-Type: application/json' -d '{...}'`
  - Dealer settlement: `curl -fsS -X POST "$BASE_URL/api/v1/accounting/settlements/dealers" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $ERP_SMOKE_COMPANY" -H 'Content-Type: application/json' -d '{...}'`

**4) What evidence to collect**
- DB linkage (Postgres inside compose):
  - Find company id: `SELECT id, code FROM companies WHERE code = '<COMPANY_CODE>';`
  - Missing links:
    - `SELECT id, invoice_number FROM invoices WHERE company_id=<CID> AND journal_entry_id IS NULL;`
    - `SELECT id, slip_number FROM packaging_slips WHERE company_id=<CID> AND (journal_entry_id IS NULL OR cogs_journal_entry_id IS NULL) AND status='DISPATCHED';`
    - `SELECT id, journal_entry_id, invoice_number FROM dealer_ledger_entries WHERE company_id=<CID> AND journal_entry_id IS NULL;`
  - Balanced journals (tolerance 0.01):
    - `SELECT je.id, je.reference_number, SUM(jl.debit) debits, SUM(jl.credit) credits, (SUM(jl.debit)-SUM(jl.credit)) diff FROM journal_entries je JOIN journal_lines jl ON jl.journal_entry_id=je.id WHERE je.company_id=<CID> GROUP BY je.id HAVING ABS(SUM(jl.debit)-SUM(jl.credit)) > 0.01;`
  - Idempotency duplicates:
    - `SELECT company_id, reference_number, COUNT(*) FROM journal_entries GROUP BY company_id, reference_number HAVING COUNT(*) > 1;`
    - `SELECT company_id, invoice_number, COUNT(*) FROM invoices GROUP BY company_id, invoice_number HAVING COUNT(*) > 1;`
- App logs:
  - dispatch confirmation logs + any “skipped due to already dispatched” messages on replay
  - settlement idempotency logs (should reuse existing)

**5) Go/No-Go criteria**
- No missing journal links for dispatched slips and issued invoices.
- No unbalanced journals.
- No duplicate journal reference numbers per company.
- Dealer settlement is idempotent (no duplicate allocations for the same idempotency key).
- Inventory does not go negative for the sold SKUs.

---

### Purchasing / Procure-to-Pay

**1) Critical behaviors to prove**
- Purchase creates raw material batches and `RECEIPT` movements with correct reference type.
- Purchase posting produces a balanced JE and links `raw_material_purchases.journal_entry_id`.
- Supplier settlement:
  - creates allocations linking purchase(s) ↔ settlement JE
  - updates outstanding correctly
  - is idempotent on replay by `idempotencyKey`
- Purchase return:
  - creates `RETURN` movement
  - reduces raw material stock
  - links a return JE and balances (Dr AP / Cr inventory per current contract)

**2) Top failure modes to simulate**
- Duplicate supplier settlement creates duplicate allocations / double AP clearing.
- Purchase created without journal link or movement journal id (breaks audit trail).
- Invoice number uniqueness not enforced → duplicates drift AP and supplier statements.
- Returns reduce stock but don’t post accounting reversal (inventory vs GL drift).
- Reference ids exceed length limits (known: references shortened in stabilization work).
- Cross-company purchase/settlement access using mismatched `X-Company-Id`.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test`
- After docker-compose boot:
  - Create supplier: `curl -fsS -X POST "$BASE_URL/api/v1/suppliers" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" -H 'Content-Type: application/json' -d '{...}'`
  - Create purchase: `curl -fsS -X POST "$BASE_URL/api/v1/purchasing/raw-material-purchases" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" -H 'Content-Type: application/json' -d '{...}'`
  - Supplier settlement: `curl -fsS -X POST "$BASE_URL/api/v1/accounting/settlements/suppliers" -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" -H 'Content-Type: application/json' -d '{...}'`

**4) What evidence to collect**
- DB linkage:
  - `SELECT id, invoice_number FROM raw_material_purchases WHERE company_id=<CID> AND journal_entry_id IS NULL;`
  - `SELECT id, reference_type, reference_id, journal_entry_id FROM raw_material_movements WHERE journal_entry_id IS NULL;`
  - `SELECT id FROM supplier_ledger_entries WHERE company_id=<CID> AND journal_entry_id IS NULL;`
  - `SELECT id FROM partner_settlement_allocations WHERE company_id=<CID> AND journal_entry_id IS NULL;`
- Supplier statements/aging:
  - API: `/api/v1/accounting/statements/suppliers/{supplierId}`
  - API: `/api/v1/accounting/aging/suppliers/{supplierId}?asOf=YYYY-MM-DD`
- AP control vs subledger:
  - Run `ReconciliationControlsIT` or call reconciliation dashboard endpoint.

**5) Go/No-Go criteria**
- Purchases, returns, settlements all have journal links and balanced journals.
- Supplier statement closing balance matches purchase total minus settlements within tolerance.
- No duplicate allocations for the same settlement idempotency key.
- Reconciliation dashboard shows AP variance within 0.01.

---

### Inventory

**1) Critical behaviors to prove**
- Finished goods and raw materials maintain non-negative stock where configured.
- Inventory movements are ordered, idempotent under retries, and reference the correct source (order/packing/purchase).
- Costing is consistent: dispatch uses dynamic costing layers and links to journal entries when posted.
- Inventory adjustments move value from inventory to variance accounts and are journal-linked.
- Inventory valuation reconciles to the inventory control account (within tolerance).

**2) Top failure modes to simulate**
- Negative `finished_goods.current_stock` or negative `reserved_stock` after concurrent reservations/dispatch.
- Duplicate movement rows for the same reference (retry not idempotent).
- Missing `inventory_movements.journal_entry_id` (traceability gap).
- Incorrect unit cost (e.g., zero/negative) after revaluation or landed cost adjustments.
- Inventory GL drift: valuation report differs from inventory control account > 0.01.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT,DispatchConfirmationIT,LandedCostRevaluationIT,RevaluationCogsIT,ReconciliationControlsIT test`
- API (running app):
  - Stock summary: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/raw-materials/stock/inventory"`
  - Inventory valuation: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/reports/inventory-valuation"`
  - Inventory reconciliation: `curl -fsS -H "Authorization: Bearer $TOKEN" -H "X-Company-Id: $COMPANY" "$BASE_URL/api/v1/reports/inventory-reconciliation"`

**4) What evidence to collect**
- DB non-negativity:
  - `SELECT id, product_code, current_stock, reserved_stock FROM finished_goods WHERE current_stock < 0 OR reserved_stock < 0;`
  - `SELECT id, sku, current_stock FROM raw_materials WHERE current_stock < 0;`
- Movement idempotency:
  - `SELECT reference_type, reference_id, movement_type, COUNT(*) FROM inventory_movements GROUP BY reference_type, reference_id, movement_type HAVING COUNT(*) > 1;`
- Movement traceability:
  - `SELECT id, reference_type, reference_id FROM inventory_movements WHERE journal_entry_id IS NULL AND movement_type IN ('ISSUE','RECEIPT');`

**5) Go/No-Go criteria**
- No negative stock/reserved stock rows.
- Inventory reconciliation variance within tolerance.
- No duplicate movements per reference in the exercised flows.
- Movement rows that should be journal-linked have non-null `journal_entry_id`.

---

### Factory / Production

**1) Critical behaviors to prove**
- Production log consumes raw materials (stock decreases) and records RM movements with correct references.
- Packing produces finished goods batches, records inventory movements, and preserves cost basis/unit cost.
- Packaging mappings drive the correct packaging-material consumption behavior.
- WIP/semi-finished/FG journal linkages exist where posting is enabled.

**2) Top failure modes to simulate**
- Production consumption does not reduce RM stock (or reduces the wrong material).
- Packing creates FG stock without consuming required packaging material (or consumes double).
- Duplicate packing movements/journals on repeated requests.
- Unit cost becomes zero/negative or diverges from expected cost allocation.
- Cross-company mixing: production log in one company writes movements in another.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test`
- API (running app):
  - Create production log: `POST $BASE_URL/api/v1/factory/production/logs`
  - Create packing record: `POST $BASE_URL/api/v1/factory/packing-records`
  - Packaging mappings: `GET $BASE_URL/api/v1/factory/packaging-mappings/active`

**4) What evidence to collect**
- DB link checks (examples):
  - `SELECT id, production_code FROM production_logs WHERE company_id=<CID> ORDER BY created_at DESC LIMIT 20;`
  - `SELECT id, reference_type, reference_id FROM inventory_movements WHERE reference_type IN ('PRODUCTION_LOG','PACKING_RECORD') AND journal_entry_id IS NULL;`
  - `SELECT reference_type, reference_id, COUNT(*) FROM raw_material_movements WHERE reference_type IN ('PRODUCTION_LOG','PACKING_RECORD') GROUP BY reference_type, reference_id;`

**5) Go/No-Go criteria**
- RM stock decreases and FG stock increases in the production cycle.
- Movements exist for production + packing references and are correctly journal-linked where expected.
- No duplicate movements/journals for a single production code.

---

### HR / Payroll

**1) Critical behaviors to prove**
- Payroll run state machine holds: `DRAFT → CALCULATED → APPROVED → POSTED → PAID`.
- Calculation totals are consistent with stored lines and posting journals.
- Posting creates a balanced JE and links it to `payroll_runs.journal_entry_id` (and/or `journal_entry_ref_id`).
- Advances are handled consistently (deduction cap, clearing postings, and balance reduction at payment).
- Reversal produces a balanced inverse and remains traceable.

**2) Top failure modes to simulate**
- Preview/calculation drift vs posted run totals.
- Posting date invalid (“future date”) due to period-end in the future.
- Payroll journal posts net pay incorrectly (missing advances clearing line).
- Mark-paid changes status but does not clear employee advances / does not persist payment reference.
- Duplicate payroll posting on retry creates multiple journals for one run.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT test`
- API (running app):
  - Create payroll run: `POST $BASE_URL/api/v1/payroll/runs`
  - Calculate: `POST $BASE_URL/api/v1/payroll/runs/{id}/calculate`
  - Approve: `POST $BASE_URL/api/v1/payroll/runs/{id}/approve`
  - Post: `POST $BASE_URL/api/v1/payroll/runs/{id}/post`
  - Mark paid: `POST $BASE_URL/api/v1/payroll/runs/{id}/mark-paid`

**4) What evidence to collect**
- DB linkage:
  - `SELECT id, status, journal_entry_id, journal_entry_ref_id FROM payroll_runs WHERE company_id=<CID> ORDER BY id DESC LIMIT 20;`
  - `SELECT id, payroll_run_id, gross_pay, net_pay, advance_deduction FROM payroll_run_lines ORDER BY id DESC LIMIT 50;`
  - `SELECT id, payroll_run_id FROM attendance WHERE payroll_run_id IS NULL AND company_id=<CID>;` (after posting, the used attendance rows should link)
- Journal balancing:
  - Use the “unbalanced journals” query from Sales section scoped to payroll references (e.g., `reference_number LIKE 'PAYROLL-%'`).

**5) Go/No-Go criteria**
- Payroll runs have journal links after posting and are balanced.
- Line totals match run totals and advance handling is consistent end-to-end.
- Mark-paid transitions to `PAID` and advances are reduced as expected.

---

### Accounting core

**1) Critical behaviors to prove**
- Double-entry integrity for every posting (balanced journals, no sign mistakes).
- Stable, unique reference numbers per company (idempotent reuse where intended).
- Period controls (open/close/lock/reopen) enforce posting constraints.
- Reversals are traceable and invert the original posting by account.
- Subledgers (dealer/supplier) reconcile to AR/AP control accounts within tolerance.

**2) Top failure modes to simulate**
- Unbalanced journals due to rounding or missing lines.
- Duplicate reference numbers creating non-idempotent duplicates.
- Posting into closed/locked periods succeeds (should be blocked).
- “Cascade reverse” reverses reversal entries (double reversal).
- Subledger reconciliation drift > 0.01 (dealer/supplier ledgers diverge from control).

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=PeriodCloseLockIT,ReconciliationControlsIT,SettlementE2ETest,JournalEntryE2ETest,CriticalAccountingAxesIT test`
- API (running app):
  - Period list/close/reopen: `/api/v1/accounting/periods`, `/api/v1/accounting/periods/{periodId}/close`, `/reopen`
  - Month-end checklist: `/api/v1/accounting/month-end/checklist`
  - Dealer statement/aging: `/api/v1/accounting/statements/dealers/{dealerId}`, `/api/v1/accounting/aging/dealers/{dealerId}`
  - Supplier statement/aging: `/api/v1/accounting/statements/suppliers/{supplierId}`, `/api/v1/accounting/aging/suppliers/{supplierId}`

**4) What evidence to collect**
- DB: unbalanced journals query (global and for a period window).
- DB: missing-link queries for invoices/purchases/payroll/adjustments.
- Period status: `SELECT id, status, closing_journal_entry_id FROM accounting_periods WHERE company_id=<CID> ORDER BY start_date DESC LIMIT 6;`
- Reference duplicates: `SELECT company_id, reference_number, COUNT(*) FROM journal_entries GROUP BY company_id, reference_number HAVING COUNT(*)>1;`

**5) Go/No-Go criteria**
- No unbalanced journals.
- No missing journal links for posted documents.
- Period close/lock/reopen behaves as tested and cannot be bypassed by non-admin.
- Reconciliation within tolerance for inventory, AR, and AP.

---

### Auth / Admin / Security

**1) Critical behaviors to prove**
- JWT login/refresh/logout are correct; refresh tokens are revoked and cannot be reused.
- MFA is enforceable and cannot be bypassed.
- RBAC is enforced for admin endpoints and sensitive actions.
- Company boundary is enforced; cross-company access is blocked.

**2) Top failure modes to simulate**
- Missing auth still allows access to admin endpoints.
- Dealer-role token can access admin routes (privilege escalation).
- Cross-company update/read succeeds by setting a different `X-Company-Id`.
- Logout does not revoke refresh tokens; old refresh token still works.
- MFA enabled but login succeeds without TOTP/recovery code.

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test`
- API (running app):
  - Login: `POST /api/v1/auth/login`
  - Refresh: `POST /api/v1/auth/refresh-token`
  - Logout: `POST /api/v1/auth/logout?refreshToken=...`
  - Profile/me: `GET /api/v1/auth/profile`, `GET /api/v1/auth/me`

**4) What evidence to collect**
- HTTP evidence: expected status codes (401/403 for protected routes; 428 for MFA challenge where designed).
- Audit logs (if enabled): verify admin actions create audit records and are company-scoped.

**5) Go/No-Go criteria**
- No unauthenticated access to protected endpoints.
- RBAC and company boundary blocks are enforced consistently.
- MFA and token lifecycle behave as tested (no bypass, no refresh reuse post-logout).

---

### Orchestrator / Outbox

**1) Critical behaviors to prove**
- Commands create outbox events and return trace ids.
- Outbox health endpoints report accurate counts (pending/retrying/dead-letter).
- Retry policy behaves as documented (max attempts, dead-letter behavior).
- Manual replay procedure is safe (at-least-once; relies on consumer idempotency).

**2) Top failure modes to simulate**
- Outbox backlog grows without processing (consumer down) and is not visible via health/metrics.
- Events get stuck in `RETRYING` with `next_attempt_at` never advancing.
- Dead-letter events accumulate silently (no operational visibility).
- Duplicate events on retries cause double side-effects (consumer not idempotent).
- Orchestrator endpoints succeed but do not persist outbox rows (lost events).

**3) Exact commands to run**
- Tests: `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test`
- API (running app):
  - Outbox health: `GET $BASE_URL/api/v1/orchestrator/health/events`
  - Integration health: `GET $BASE_URL/api/v1/orchestrator/health/integrations`
- DB (compose):
  - `SELECT status, dead_letter, COUNT(*) FROM orchestrator_outbox GROUP BY status, dead_letter ORDER BY status, dead_letter;`
  - Dead letters: `SELECT id, status, retry_count, last_error, next_attempt_at FROM orchestrator_outbox WHERE dead_letter = true ORDER BY created_at DESC LIMIT 50;`

**4) What evidence to collect**
- Outbox table counts and status distribution.
- Health endpoint output snapshot (pending/retrying/dead letters).
- App logs for retry attempts and last_error values.

**5) Go/No-Go criteria**
- Outbox health endpoints reflect reality (counts match DB).
- No uncontrolled growth of pending/retrying/dead-letter events under expected load.
- Manual replay steps are documented and verified in a non-prod environment.

---

## D) Operational confidence

### Flyway procedure validation

**What to check**
- Flyway runs successfully on boot.
- `flyway_schema_history` shows latest versions as `success=true`.
- No checksum drift surprises in existing DBs.

**How**
- Boot app: `DB_PORT=55432 JWT_SECRET='…' ERP_SECURITY_ENCRYPTION_KEY='…' docker compose up -d --build`
- Watch logs: `docker logs -f erp_domain_app | rg -n \"Flyway|Migrat\"`
- Verify history:
  - `docker exec -e PGPASSWORD=erp -i erp_db psql -U erp -d erp_domain -c \"SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;\"`

### Boot readiness

**What to check**
- `/actuator/health` is `UP` and readiness includes required config indicators in prod profile.

**How**
- `curl -fsS http://localhost:9090/actuator/health`
- If readiness is `DOWN`, check required config in compose env:
  - `JWT_SECRET`, `ERP_SECURITY_ENCRYPTION_KEY`
  - in prod: `ERP_LICENSE_KEY`, dispatch mapping ids (`ERP_DISPATCH_DEBIT_ACCOUNT_ID`, `ERP_DISPATCH_CREDIT_ACCOUNT_ID`)

### Outbox health

**What to check**
- Pending/retrying/dead-letter counts are near zero for steady-state.

**How**
- API: `curl -fsS -H \"Authorization: Bearer $TOKEN\" -H \"X-Company-Id: $COMPANY\" \"$BASE_URL/api/v1/orchestrator/health/events\"`
- DB: `SELECT status, COUNT(*) FROM orchestrator_outbox GROUP BY status;`

### Backup/restore runbook checks (Postgres)

**What to check**
- Backup can be created from prod-like DB.
- Restore completes into a new DB and basic app checks succeed.

**How (docker-compose DB)**
- Backup:
  - `docker exec -e PGPASSWORD=erp erp_db pg_dump -U erp -d erp_domain --format=custom --no-owner --no-acl -f /tmp/erp_domain.dump`
  - `docker cp erp_db:/tmp/erp_domain.dump ./erp_domain.dump`
- Restore test:
  - `docker exec -e PGPASSWORD=erp erp_db createdb -U erp erp_domain_restore_test`
  - `docker exec -e PGPASSWORD=erp erp_db pg_restore -U erp -d erp_domain_restore_test /tmp/erp_domain.dump`
- Post-restore sanity:
  - `docker exec -e PGPASSWORD=erp erp_db psql -U erp -d erp_domain_restore_test -c \"SELECT COUNT(*) FROM flyway_schema_history;\"`

---

## E) Known noisy warnings (expected vs actionable)

These are commonly observed in test/boot logs. Treat them as signals only when they violate the “safe when” conditions.

### “Invalid company ID format: <COMPANY_CODE>”
- Why it happens: audit logging attempts to parse `CompanyContextHolder` as a numeric id; the system commonly stores company **code** (e.g., `ACME`) in that context (`erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/AuditService.java`).
- Safe when:
  - it appears in tests/dev logs and audit logging is not a release-critical deliverable for the run
  - the rest of the request succeeds and company scoping works
- Investigate when:
  - audit logs are required for compliance and `company_id` is expected to be populated
  - warnings appear for numeric ids too (could indicate corrupted context)

### “Unusual negative balance … for ASSET/EXPENSE/COGS account …”
- Why it happens: soft guard in `Account.validateBalanceUpdate` warns on unusual sign conventions but does not block (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/Account.java`).
- Safe when:
  - the negative is transient in tests or known scenarios (e.g., advances/clearing accounts) and reconciliations still pass
- Investigate when:
  - inventory/AR/AP control accounts go materially negative or drift persists after settlements/reversals
  - reconciliation variance exceeds tolerance

### “Dispatch debit/credit accounts not configured; … skipped”
- Why it happens: dispatch journal mapping requires `erp.dispatch.debit-account-id` and `erp.dispatch.credit-account-id`; without it, dispatch COGS postings are skipped (`erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java`).
- Safe when:
  - running tests where dispatch accounting is intentionally not enabled
- Investigate when:
  - running prod-like readiness or validating COGS/inventory relief postings (this becomes a No-Go)

### openhtmltopdf CSS/font warnings (PDF generation)
- Why it happens: HTML→PDF rendering logs CSS parsing and font cache warnings (invoice PDFs, payroll sheets).
- Safe when:
  - PDFs render correctly and business documents remain readable (visual inspection OK)
- Investigate when:
  - invoice/payslip PDFs are missing sections, formatting breaks compliance requirements, or rendering fails

### Testcontainers “auth config” warnings / dynamic agent loading notices
- Why it happens: local Docker/Testcontainers configuration and JVM agent behavior during tests.
- Safe when:
  - tests pass and containers start reliably; no connection flakiness
- Investigate when:
  - Postgres container fails to start, ports conflict, or tests become flaky

### Surefire “corrupted channel dumpstream” warning
- Why it happens: intermittent Maven Surefire logging channel corruption; recorded as a `.dumpstream` file (not necessarily a test failure).
- Safe when:
  - test suite still reports `Failures 0, Errors 0`
- Investigate when:
  - it coincides with hangs, missing surefire reports, or intermittent failures

### Sequence contention / duplicate key retries
- Why it happens: concurrent inserts into sequence/numbering tables under test concurrency or retry loops.
- Safe when:
  - retries succeed and idempotency assertions pass
- Investigate when:
  - duplicates leak into business keys (invoice numbers, journal reference numbers) or cause failed postings
