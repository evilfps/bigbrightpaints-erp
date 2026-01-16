# Newcomer Guide — BigBright ERP Backend

This guide is for:
- **New developers** onboarding to the codebase
- **Operators/users** who need a high-level understanding of what the system does and what “must be true”

Repo focus: the `erp-domain` service (Spring Boot + Postgres + Flyway; RabbitMQ for orchestration/outbox).

---

## A) What this ERP does (plain language)

This backend is an ERP “system of record” for a manufacturing + distribution business. It coordinates:
- **Sales**: dealers, sales orders, dispatch/shipping, invoicing, collections/settlements
- **Purchasing**: suppliers, raw material purchases/receipts, supplier settlements/payments, purchase returns
- **Inventory**: raw materials + finished goods stock, movements, adjustments, valuation/reconciliation
- **Factory/Production**: production logs (consumption), packing records (finished goods), costing signals
- **Payroll**: employees, attendance, payroll runs, payroll posting and payment marking
- **Accounting**: chart of accounts, journals, period close/lock/reopen, statements/aging, reconciliation controls
- **Security/multi-company**: JWT auth, role/permission checks, company boundary via `X-Company-Id`

The key ERP promise is **traceability**: every business event (invoice, dispatch, purchase, payroll run) produces **linked accounting + inventory state** that can be audited and reconciled.

---

## B) Portals / user roles concept

Even if you only see a backend repo, the system assumes “portals” (or responsibility areas). A single user can have multiple roles.

- **Admin portal**
  - Manages users, roles/permissions, company configuration, system settings, licensing
  - Typical roles/permissions: `ROLE_ADMIN`
- **Accounting portal**
  - Posts and reviews journals, runs settlements, runs statements/aging, performs reconciliation, closes periods
  - Typical roles: `ROLE_ACCOUNTING`
- **Sales portal**
  - Manages dealers, orders, credit limit overrides, dispatch approvals, sales returns/credit notes
  - Typical roles: `ROLE_SALES` plus scoped permissions like `orders.approve`
- **Manufacturing portal**
  - Runs production logs and packing; manages packaging mappings; monitors stock/costing flows
  - Typical roles: `ROLE_FACTORY` plus scoped permissions like `factory.dispatch`
- **Dealer portal**
  - Dealer self-service: view invoices, statements, aging, and account position (no admin powers)
  - Typical roles: `ROLE_DEALER`

Multi-company: most requests are scoped by the header `X-Company-Id` (company **code**), and the backend enforces “no cross-company reads/writes”.

---

## C) Core flows explained (plain language + technical mapping)

In each flow:
- “User actions” describe what operators do.
- “Records created” describes what the system stores.
- “Technical mapping” points to endpoints and key code areas.
- “Invariants” are the consistency rules that must remain true.

### 1) Order → Dispatch → Invoice → Journal → Dealer Ledger → Settlement

**What the user does**
1) Sales creates an order for a dealer.
2) Order is confirmed/reserved (stock is held).
3) Dispatch/shipping is confirmed (stock leaves inventory).
4) An invoice is issued (dealer now owes money).
5) Dealer pays (settlement/receipt recorded).

**Records created**
- Sales order: `SalesOrder` (`sales_orders`)
- Packaging/dispatch slip: `PackagingSlip` (`packaging_slips`)
- Inventory movements (issues/receipts): `InventoryMovement` (`inventory_movements`)
- Invoice: `Invoice` (`invoices`)
- Journal entry + lines: `JournalEntry`, `JournalLine` (`journal_entries`, `journal_lines`)
- Dealer ledger entries: `DealerLedgerEntry` (`dealer_ledger_entries`)
- Settlement allocations: `PartnerSettlementAllocation` (`partner_settlement_allocations`)

**Technical mapping**
- Endpoints:
  - Create order: `POST /api/v1/sales/orders`
  - Confirm order: `POST /api/v1/sales/orders/{id}/confirm`
  - Dispatch confirm: `POST /api/v1/sales/dispatch/confirm`
  - Dealer settlement: `POST /api/v1/accounting/settlements/dealers`
  - Dealer statement/aging: `GET /api/v1/accounting/statements/dealers/{dealerId}`, `GET /api/v1/accounting/aging/dealers/{dealerId}`
- Code:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**` (slips, movements, dispatch)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**` (journals, settlements, ledgers)

**What must stay consistent (invariants)**
- Dispatch must be **idempotent**: a retry cannot double-issue stock or double-post journals.
- Invoice must link to its journal (`invoices.journal_entry_id`).
- Packaging slip must link to invoice and journals after dispatch (`packaging_slips.invoice_id`, `journal_entry_id`, `cogs_journal_entry_id`).
- Journals must be balanced (debits==credits within tolerance).
- Dealer ledger must reference the same journal and invoice identifiers used in accounting (aging depends on this).

---

### 2) Purchase → Receipt → AP → Journal → Supplier Ledger → Settlement → Return

**What the user does**
1) Purchasing records a supplier invoice (a purchase).
2) Stock is received into raw materials.
3) Supplier is paid (settlement/payment).
4) If goods are returned, a purchase return is recorded.

**Records created**
- Supplier: `Supplier` (`suppliers`)
- Purchase: `RawMaterialPurchase` (`raw_material_purchases`)
- Raw material batches/movements: `RawMaterialBatch`, `RawMaterialMovement` (`raw_material_batches`, `raw_material_movements`)
- AP journal entries: `JournalEntry`/`JournalLine`
- Supplier ledger entries: `SupplierLedgerEntry` (`supplier_ledger_entries`)
- Settlement allocations: `PartnerSettlementAllocation`

**Technical mapping**
- Endpoints:
  - Create supplier: `POST /api/v1/suppliers`
  - Create purchase: `POST /api/v1/purchasing/raw-material-purchases`
  - Supplier settlement: `POST /api/v1/accounting/settlements/suppliers`
  - Purchase return: `POST /api/v1/purchasing/raw-material-purchases/returns`
  - Supplier statement/aging: `GET /api/v1/accounting/statements/suppliers/{supplierId}`, `GET /api/v1/accounting/aging/suppliers/{supplierId}`
- Code:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**` (raw materials)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**` (AP, settlements, supplier ledger)

**What must stay consistent (invariants)**
- Purchase must link to its posting journal (`raw_material_purchases.journal_entry_id`).
- Receipt movements must exist and link to journals (`raw_material_movements.journal_entry_id`).
- Supplier settlements must be **idempotent** (replays don’t create duplicate allocations/journals).
- Supplier statement/aging must match the underlying postings within tolerance (0.01).

---

### 3) Production → Stock movements → Costing → Journals

**What the user does**
1) Factory records a production log (materials consumed, batch produced).
2) Factory records packing (finished goods packaged into units/sizes).
3) The system reflects raw material decreases and finished goods increases.

**Records created**
- Production log: `ProductionLog` (`production_logs`)
- Packing record: `PackingRecord` (`packing_records`)
- Finished good batches: `FinishedGoodBatch` (`finished_good_batches`)
- Inventory movements: `InventoryMovement` (`inventory_movements`) + RM movements (`raw_material_movements`)
- Optional journals for costing/WIP/FG transfers (depending on configuration/flow)

**Technical mapping**
- Endpoints:
  - Create production log: `POST /api/v1/factory/production/logs`
  - Create packing record: `POST /api/v1/factory/packing-records`
  - Packaging mappings: `GET /api/v1/factory/packaging-mappings/active`
- Code:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/**` (catalog/products/brands)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**` (batches/movements)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**` (costing-related postings)

**What must stay consistent (invariants)**
- Production references must be traceable end-to-end:
  - `production_logs.production_code` is reused as movement reference ids in several flows.
- Movements must not duplicate on retries (idempotency).
- Costing signals (unit cost) must remain positive and traceable to movements/batches.

---

### 4) Payroll → Calculation → Posting → Payment/advance clearing → Reversal

**What the user does**
1) HR creates employees and records attendance.
2) Payroll run is created for a period.
3) Payroll is calculated, approved, and posted (accounting journal).
4) Payment is marked paid and advances are cleared.
5) If needed, accounting reversal is used for correction.

**Records created**
- Employee: `Employee` (`employees`)
- Attendance: `Attendance` (`attendance`)
- Payroll run + lines: `PayrollRun`, `PayrollRunLine` (`payroll_runs`, `payroll_run_lines`)
- Payroll journal: `JournalEntry`/`JournalLine`

**Technical mapping**
- Endpoints:
  - Employee: `POST /api/v1/hr/employees`
  - Attendance mark: `POST /api/v1/hr/attendance/mark/{employeeId}`
  - Payroll run flow: `POST /api/v1/payroll/runs`, then `/calculate`, `/approve`, `/post`, `/mark-paid`
- Code:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**` (posting + reversal)

**What must stay consistent (invariants)**
- Payroll state progression must be linear (no skipping steps).
- Posted payroll run must link to its journal (`payroll_runs.journal_entry_id` / `journal_entry_ref_id`).
- Journal must balance and reflect advances clearing where configured (`EMP-ADV` behavior in docs/tests).
- PF toggle: company-level `pfEnabled` (default true) controls PF withholding.
  - On: monthly STAFF lines with gross >= 15000 deduct 12% PF (rounded to 0 decimals); posting credits `PF-PAYABLE` and reduces `SALARY-PAYABLE`.
  - Off: PF deduction stays zero and no `PF-PAYABLE` line is posted.
- Payroll posting auto-creates missing payroll accounts (`SALARY-EXP`, `WAGE-EXP`, `SALARY-PAYABLE`, `EMP-ADV`, `PF-PAYABLE`) to keep workflows unblocked.

---

## D) Where things live in the codebase (for devs)

### Modules (main code)

All modules live under:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**`

Key packages you’ll use early:
- Sales: `.../modules/sales/**`
- Purchasing: `.../modules/purchasing/**`
- Inventory: `.../modules/inventory/**`
- Factory/Production: `.../modules/factory/**`, `.../modules/production/**`
- HR/Payroll: `.../modules/hr/**`
- Accounting + reports: `.../modules/accounting/**`, `.../modules/reports/**`
- Auth/Admin/RBAC/Company: `.../modules/auth/**`, `.../modules/admin/**`, `.../modules/rbac/**`, `.../modules/company/**`

Orchestration/outbox lives under:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`

Database migrations:
- `erp-domain/src/main/resources/db/migration/**` (Flyway forward-only)

OpenAPI spec snapshots:
- `openapi.json`
- `erp-domain/openapi.json`

### High-signal test suites (verification anchors)

When you’re unsure “what must remain true”, start here:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantAssertions.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PeriodCloseLockIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/ReconciliationControlsIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java`

Test infrastructure:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/test/AbstractIntegrationTest.java` (Testcontainers Postgres)

---

## E) How to run it locally (copy/paste)

### 1) Run via Docker Compose (recommended for “prod-like” behavior)

Prereqs:
- Docker + Docker Compose v2

Create a local env file:
- `cp .env.example .env`
- Edit at minimum:
  - `JWT_SECRET` (32+ bytes)
  - `ERP_SECURITY_ENCRYPTION_KEY` (32+ bytes)
  - Optional: set `DB_PORT=55432` if port 5432 is already in use

Boot:
- `DB_PORT=55432 JWT_SECRET='replace-me-with-32+bytes' ERP_SECURITY_ENCRYPTION_KEY='replace-me-with-32+bytes' docker compose up -d --build`

Health:
- `curl -s http://localhost:9090/actuator/health`

API docs:
- `curl -s http://localhost:8081/v3/api-docs >/dev/null && echo OK`

Smoke checks (health + docs + authenticated profile):
- `ERP_SMOKE_EMAIL=... ERP_SMOKE_PASSWORD=... ERP_SMOKE_COMPANY=... erp-domain/scripts/ops_smoke.sh`

Ports (defaults):
- App: `8081`
- Management/actuator: `9090`
- Postgres: `DB_PORT` → `5432` in container
- RabbitMQ: `5672` + management `15672`
- Mailhog: `1025` (SMTP) + `8025` (UI)

### 2) Run tests (highest confidence, requires Docker)

Prereqs:
- Java 21
- Docker (Testcontainers starts Postgres 16)

Run full tests:
- `mvn -f erp-domain/pom.xml test`

Compile only:
- `mvn -f erp-domain/pom.xml -DskipTests compile`

Note: tests include `OpenApiSnapshotIT` which writes `openapi.json` to repo root; expect your working tree to change if the API spec changes.

### 3) Required env vars (summary)

From `docker-compose.yml`, `.env.example`, and `erp-domain/src/main/resources/application-prod.yml`:
- Always required for prod-like boot:
  - `JWT_SECRET`
  - `ERP_SECURITY_ENCRYPTION_KEY`
- Required for strict prod profile validation:
  - `ERP_LICENSE_KEY` (and license metadata if `ERP_LICENSE_ENFORCE=true`)
  - `ERP_DISPATCH_DEBIT_ACCOUNT_ID`, `ERP_DISPATCH_CREDIT_ACCOUNT_ID` (to enable dispatch journals and avoid readiness `DOWN`)

---

## F) FAQ / Troubleshooting

### Docker permission issues
- Symptom: `permission denied` on `/var/run/docker.sock`
- Fix: ensure your user can run Docker (e.g., add to `docker` group) or run Docker commands with appropriate permissions.

### Testcontainers issues
- Symptom: tests hang or fail to start Postgres container
- Checklist:
  - Docker daemon is running
  - You can run `docker ps`
  - No corporate proxy blocks image pulls
  - If you see port conflicts, free ports or adjust your Docker setup

### Boot issues: `/actuator/health` shows `DOWN`
- Most common causes:
  - Missing `JWT_SECRET` or `ERP_SECURITY_ENCRYPTION_KEY`
  - Prod profile readiness includes required config and dispatch mapping checks (`application-prod.yml`)
  - Dispatch mapping IDs unset → readiness `DOWN` in prod-like runs

### Port conflicts (Postgres 5432 already used)
- Use a different host port:
  - `DB_PORT=55432 docker compose up -d --build`

### Checkstyle “violations” confusion
- Checkstyle is configured in advisory mode (it reports a large number of warnings but does not fail builds).
- Treat it as a trend signal, not a release gate, unless your team decides to enforce it later.

---

If you are debugging a live issue, use `docs/ops_and_debug/DEBUGGING_PLAN.md` as the step-by-step checklist for “what to run and what evidence to collect”.
