# Accounting Domain Map — BigBright Paints ERP

> Auto-generated from codebase analysis on 2026-03-28

---

## 1. Module Structure

```
modules/accounting/
├── controller/          (6 files)
│   ├── AccountingController.java          — Main REST controller (~1150 lines)
│   ├── AccountingAuditTrailController.java
│   ├── AccountingConfigurationController.java
│   ├── OpeningBalanceImportController.java
│   ├── PayrollController.java
│   └── TallyImportController.java
├── domain/              (48 files — entities, enums, repositories)
├── dto/                 (76 files — request/response DTOs)
├── event/               (6 files — domain events & store)
├── internal/            (5 files — core implementations)
│   ├── AccountingCoreEngineCore.java      — Core engine (~334KB, business logic hub)
│   ├── AccountingFacadeCore.java          — Facade core (~88KB)
│   ├── AccountingPeriodServiceCore.java   — Period service core (~67KB)
│   ├── AccountingAuditTrailServiceCore.java
│   └── ReconciliationServiceCore.java
└── service/             (41 files — thin orchestration layers over internal/)
```

### Service Inheritance Chain

```
AccountingCoreEngineCore (internal — massive, contains all business logic)
  └── AccountingCoreEngine (abstract, wiring layer)
        └── AccountingCoreLogic (abstract, cross-cutting orchestration shell)
              ├── AccountingCoreService → AccountingService (top-level facade)
              ├── AccountingIdempotencyService (idempotency-aware routing)
              ├── AccountingFacade → AccountingFacadeCore (manual journals)
              ├── JournalEntryService
              ├── DealerReceiptService
              ├── SettlementService
              └── ... (other thin services)
```

---

## 2. Domain Models (Entities)

### Core Accounting Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **Account** | `accounts` | Chart of Accounts — hierarchical (parent/child), typed, company-scoped |
| **JournalEntry** | `journal_entries` | Double-entry journal header (reference_number unique per company) |
| **JournalLine** | `journal_lines` | Individual debit/credit lines per journal entry |
| **AccountingPeriod** | `accounting_periods` | Monthly period (year+month unique per company) |
| **AccountingPeriodSnapshot** | `accounting_period_snapshots` | Point-in-time trial balance snapshot on close |
| **AccountingPeriodTrialBalanceLine** | — | Per-account snapshot lines |
| **JournalReferenceMapping** | `journal_reference_mappings` | Legacy→canonical reference mapping (idempotency) |

### Partner Ledger (Sub-Ledger) Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **DealerLedgerEntry** | `dealer_ledger_entries` | AR sub-ledger (dealer receivables with aging/payment tracking) |
| **SupplierLedgerEntry** | `supplier_ledger_entries` | AP sub-ledger (supplier payables) |
| **PartnerSettlementAllocation** | `partner_settlement_allocations` | Settlement allocations (invoice ↔ payment matching) |

### Period Close & Reconciliation

| Entity | Table | Purpose |
|--------|-------|---------|
| **PeriodCloseRequest** | `period_close_requests` | Maker-checker close request |
| **ClosedPeriodPostingException** | — | Exceptions allowing posting to closed periods |
| **BankReconciliationSession** | — | Bank reconciliation workflow sessions |
| **BankReconciliationItem** | — | Individual items in bank reconciliation |
| **ReconciliationDiscrepancy** | — | Sub-ledger vs GL discrepancies |

### Import & Migration

| Entity | Table | Purpose |
|--------|-------|---------|
| **OpeningBalanceImport** | — | Opening balance import tracking (with idempotency) |
| **TallyImport** | — | Tally XML import tracking (with idempotency) |

### Event Store

| Entity | Table | Purpose |
|--------|-------|---------|
| **AccountingEvent** | `accounting_events` | Append-only audit event store |

---

## 3. Enums

### AccountType (Chart of Accounts)

| Value | Normal Balance | Affects Net Income |
|-------|---------------|-------------------|
| `ASSET` | Debit | No |
| `LIABILITY` | Credit | No |
| `EQUITY` | Credit | No |
| `REVENUE` | Credit | Yes |
| `EXPENSE` | Debit | Yes |
| `COGS` | Debit | Yes |
| `OTHER_INCOME` | Credit | Yes |
| `OTHER_EXPENSE` | Debit | Yes |

**Hierarchy**: Accounts have a `parent` → `hierarchyLevel` structure (1=Category, 2=Subcategory, 3=Detail, etc.)

### AccountingPeriodStatus

| Value | Meaning |
|-------|---------|
| `OPEN` | Normal posting allowed |
| `LOCKED` | Soft lock, restricted posting |
| `CLOSED` | Hard close, no posting (with exceptions) |

### PeriodCloseRequestStatus

| Value | Meaning |
|-------|---------|
| `PENDING` | Submitted, awaiting approval |
| `APPROVED` | Approved → period closed |
| `REJECTED` | Rejected, period stays open |

### JournalEntryType

| Value | Meaning |
|-------|---------|
| `AUTOMATED` | System-generated from business events |
| `MANUAL` | User-created manual journal |

### JournalCorrectionType

| Value | Meaning |
|-------|---------|
| `REVERSAL` | Creates a reversing entry (original preserved) |
| `VOID` | Voids the original entry |

### CostingMethod

| Value |
|-------|
| `FIFO` |
| `LIFO` |
| `WEIGHTED_AVERAGE` |

### GstRegistrationType

| Value |
|-------|
| `REGULAR` |
| `COMPOSITION` |
| `UNREGISTERED` |

### PartnerType

| Value |
|-------|
| `DEALER` |
| `SUPPLIER` |

### ReconciliationDiscrepancyType

| Value |
|-------|
| `AR` |
| `AP` |
| `INVENTORY` |
| `GST` |

### ReconciliationDiscrepancyStatus

| Value |
|-------|
| `OPEN` |
| `ACKNOWLEDGED` |
| `ADJUSTED` |
| `RESOLVED` |

### ReconciliationDiscrepancyResolution

| Value |
|-------|
| `ADJUSTMENT_JOURNAL` |
| `WRITE_OFF` |
| `ACKNOWLEDGED` |

---

## 4. API Endpoints

All endpoints are under `/api/v1/accounting` (except Tally import at `/api/v1/migration`).

### Chart of Accounts

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/accounts` | List all accounts | ADMIN, ACCOUNTING |
| `POST` | `/accounts` | Create account | ADMIN, ACCOUNTING |
| `GET` | `/accounts/tree` | Chart of accounts tree | ADMIN, ACCOUNTING |
| `GET` | `/accounts/tree/{type}` | Tree filtered by AccountType | ADMIN, ACCOUNTING |
| `GET` | `/default-accounts` | Get company default accounts | ADMIN, ACCOUNTING |
| `PUT` | `/default-accounts` | Update company default accounts | ADMIN, ACCOUNTING |

### Journal Entries

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/journal-entries` | List journal entries (filterable) | ADMIN, ACCOUNTING |
| `POST` | `/journal-entries` | Create journal entry (with idempotency key) | ADMIN, ACCOUNTING |
| `POST` | `/journal-entries/{id}/reverse` | Reverse a journal entry | ADMIN, ACCOUNTING |
| `POST` | `/journal-entries/{id}/cascade-reverse` | Cascade reverse with related entries | ADMIN, ACCOUNTING |
| `GET` | `/journals` | List journals (with date/type/module filters) | ADMIN, ACCOUNTING |
| `POST` | `/journals/manual` | Create manual journal | ADMIN, ACCOUNTING |
| `POST` | `/journals/{id}/reverse` | Reverse journal (alternate path) | ADMIN, ACCOUNTING |

### Receipts & Payments

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/receipts/dealer` | Record dealer receipt | ADMIN, ACCOUNTING |
| `POST` | `/receipts/dealer/hybrid` | Record dealer split receipt | ADMIN, ACCOUNTING |
| `POST` | `/payroll/payments` | Record payroll payment | ADMIN, ACCOUNTING |

### Settlements

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/settlements/dealers` | Settle dealer invoices | ADMIN, ACCOUNTING |
| `POST` | `/dealers/{id}/auto-settle` | Auto-settle dealer | ADMIN, ACCOUNTING |
| `POST` | `/settlements/suppliers` | Settle supplier invoices | ADMIN, ACCOUNTING |
| `POST` | `/suppliers/{id}/auto-settle` | Auto-settle supplier | ADMIN, ACCOUNTING |

### Credit/Debit Notes & Adjustments

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/credit-notes` | Post credit note | ADMIN, ACCOUNTING |
| `POST` | `/debit-notes` | Post debit note | ADMIN, ACCOUNTING |
| `POST` | `/accruals` | Post accrual | ADMIN, ACCOUNTING |
| `POST` | `/bad-debts/write-off` | Write off bad debt | ADMIN, ACCOUNTING |

### Sales Returns

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/sales/returns` | List sales returns | ADMIN, ACCOUNTING, SALES |
| `POST` | `/sales/returns/preview` | Preview sales return | ADMIN, ACCOUNTING |
| `POST` | `/sales/returns` | Record sales return | ADMIN, ACCOUNTING |

### Accounting Periods

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/periods` | List periods | ADMIN, ACCOUNTING |
| `POST` | `/periods` | Create or update period | ADMIN, ACCOUNTING |
| `PUT` | `/periods/{id}` | Update period | ADMIN, ACCOUNTING |
| `POST` | `/periods/{id}/lock` | Lock period | ADMIN, ACCOUNTING |
| `POST` | `/periods/{id}/request-close` | Request close (maker) | ADMIN, ACCOUNTING |
| `POST` | `/periods/{id}/approve-close` | Approve close (checker) | ADMIN |
| `POST` | `/periods/{id}/reject-close` | Reject close (checker) | ADMIN |
| `POST` | `/periods/{id}/reopen` | Reopen closed period | **SUPER_ADMIN** |
| `GET` | `/month-end/checklist` | Get month-end checklist | ADMIN, ACCOUNTING |
| `POST` | `/month-end/checklist/{periodId}` | Update checklist | ADMIN, ACCOUNTING |

### GST / Tax

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/gst/return` | Generate GST return | ADMIN, ACCOUNTING |
| `GET` | `/gst/reconciliation` | GST reconciliation report | ADMIN, ACCOUNTING |

### Bank Reconciliation

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/reconciliation/bank` | Legacy bank reconciliation | ADMIN, ACCOUNTING |
| `POST` | `/reconciliation/bank/sessions` | Start reconciliation session | ADMIN, ACCOUNTING |
| `PUT` | `/reconciliation/bank/sessions/{id}/items` | Update session items | ADMIN, ACCOUNTING |
| `POST` | `/reconciliation/bank/sessions/{id}/complete` | Complete session | ADMIN, ACCOUNTING |
| `GET` | `/reconciliation/bank/sessions` | List sessions | ADMIN, ACCOUNTING |
| `GET` | `/reconciliation/bank/sessions/{id}` | Get session detail | ADMIN, ACCOUNTING |

### Sub-Ledger & Inter-Company Reconciliation

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/reconciliation/subledger` | Sub-ledger reconciliation report | ADMIN, ACCOUNTING |
| `GET` | `/reconciliation/discrepancies` | List discrepancies | ADMIN, ACCOUNTING |
| `POST` | `/reconciliation/discrepancies/{id}/resolve` | Resolve discrepancy | ADMIN, ACCOUNTING |
| `GET` | `/reconciliation/inter-company` | Inter-company reconciliation | ADMIN, ACCOUNTING |

### Statements & Aging

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/statements/suppliers/{id}` | Supplier statement | ADMIN, ACCOUNTING |
| `GET` | `/statements/suppliers/{id}/pdf` | Supplier statement PDF | ADMIN |
| `GET` | `/aging/suppliers/{id}` | Supplier aging | ADMIN, ACCOUNTING |
| `GET` | `/aging/suppliers/{id}/pdf` | Supplier aging PDF | ADMIN |

### Inventory Accounting

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/inventory/landed-cost` | Record landed cost | ADMIN, ACCOUNTING |
| `POST` | `/inventory/revaluation` | Revalue inventory | ADMIN, ACCOUNTING |
| `POST` | `/inventory/wip-adjustment` | Adjust WIP | ADMIN, ACCOUNTING |

### Temporal Queries

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/accounts/{id}/balance/as-of` | Balance as of date | ADMIN, ACCOUNTING |
| `GET` | `/trial-balance/as-of` | Trial balance as of date | ADMIN, ACCOUNTING |
| `GET` | `/accounts/{id}/activity` | Account activity report | ADMIN, ACCOUNTING |
| `GET` | `/accounts/{id}/balance/compare` | Compare balances between dates | ADMIN, ACCOUNTING |

### Audit & Configuration

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/audit/digest` | Audit digest (deprecated) | ADMIN |
| `GET` | `/audit/digest.csv` | Audit digest CSV (deprecated) | ADMIN |
| `GET` | `/audit/transactions` | Transaction audit list | ADMIN, ACCOUNTING |
| `GET` | `/audit/transactions/{id}` | Transaction audit detail | ADMIN, ACCOUNTING |
| `GET` | `/audit-trail` | Full audit trail | ADMIN, ACCOUNTING |
| `GET` | `/date-context` | Accounting date context | ADMIN, ACCOUNTING |
| `GET` | `/configuration/health` | Configuration health check | ADMIN, ACCOUNTING |

### Data Import

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/opening-balances` | Import opening balances (CSV) | ADMIN |
| `POST` | `/migration/tally-import` | Import Tally XML | ADMIN |
| `POST` | `/payroll/payments/batch` | Batch payroll payments | ADMIN, ACCOUNTING |

---

## 5. Journal Generation Rules

### Reference Number Prefixes (by source event)

| Prefix | Source | Description |
|--------|--------|-------------|
| `JRN-{company}-{period}-` | `ReferenceNumberService.nextJournalReference()` | General auto-generated journal |
| `RCPT-{dealerCode}-` | `ReferenceNumberService.dealerReceiptReference()` | Dealer receipt |
| `SALE-{orderNumber}-` | `ReferenceNumberService.salesOrderReference()` | Sales order posting |
| `SUP-{supplierCode}-` | `ReferenceNumberService.supplierPaymentReference()` | Supplier payment |
| `PAYROLL-{period}-` | `ReferenceNumberService.payrollPaymentReference()` | Payroll |
| `RM-{batchCode}-` | `ReferenceNumberService.rawMaterialReceiptReference()` | Raw material receipt |
| `COST-ALLOC-{period}-` | `ReferenceNumberService.costAllocationReference()` | Cost allocation |
| `INVJ-{company}-{period}-` | `ReferenceNumberService.invoiceJournalReference()` | Invoice journal |
| `MANUAL-{date}-{nonce}` | `AccountingFacade` | Manual journal |
| `CRN-` | Credit notes | Credit note (sales return) |
| `SET-` / `SUP-SET-` | Auto-settlements | Dealer/supplier auto-settlement |
| `INV-REV-` | Invoice reversal | Invoice reversal |

### Business Event → Journal Mappings

| Business Event | Journal Pattern | Key Accounts | Module Source |
|---------------|-----------------|--------------|-------------|
| **Dealer Receipt** | Dr. Cash → Cr. Dealer Receivable | Cash/Bank, AR | `dealerReceiptService` |
| **Dealer Hybrid Receipt** | Dr. Cash (split) → Cr. AR, Cr. Discount | Cash, AR, Discount | `dealerReceiptService` |
| **Dealer Settlement** | Dr. Cash → Cr. AR, Cr. Discount, Dr. Write-off | Cash, AR, Discount, Write-off | `settlementService` |
| **Auto-Settle Dealer** | Same as settlement, FIFO allocation | Same | `settlementService` |
| **Supplier Payment** | Dr. AP → Cr. Cash | AP, Cash/Bank | `settlementService` |
| **Supplier Settlement** | Dr. AP → Cr. Cash, Cr. Discount, Cr. Write-off | AP, Cash, Discount | `settlementService` |
| **Auto-Settle Supplier** | Same as supplier settlement | Same | `settlementService` |
| **Credit Note** | Dr. Revenue/AR → Cr. Customer | Revenue, AR | `creditDebitNoteService` |
| **Debit Note** | Dr. Supplier/AP → Cr. Expense | AP, Expense | `creditDebitNoteService` |
| **Accrual** | Dr. Expense → Cr. Accrued Liability | Expense, Liability | `creditDebitNoteService` |
| **Bad Debt Write-off** | Dr. Bad Debt Expense → Cr. AR | Expense, AR | `creditDebitNoteService` |
| **Payroll Payment** | Dr. Salary Payable → Cr. Cash | Salary Payable, Cash | `accountingFacade` |
| **Sales Return** | Dr. Revenue → Cr. AR, Dr. COGS → Cr. Inventory | Revenue, AR, COGS, Inventory | `salesReturnService` |
| **Landed Cost** | Dr. Inventory → Cr. Cash/AP | Inventory, Cash | `inventoryAccountingService` |
| **Inventory Revaluation** | Dr. Inventory / Cr. Revaluation Gain (or reverse) | Inventory, Gain/Loss | `inventoryAccountingService` |
| **WIP Adjustment** | Dr. WIP / Cr. Raw Materials | WIP, Materials | `inventoryAccountingService` |
| **Period Close** | Closing journal (income→retained earnings) | Income Summary, RE | `accountingPeriodService` |
| **Period Reopen** | Reverse closing journal | Reverse of above | `accountingPeriodService` |
| **Inventory Valuation Changed** (event) | Dr. Inventory → Cr. Revaluation Gain (increase), reverse for decrease | Inventory, Gain/Loss | `InventoryAccountingEventListener` |
| **Inventory Movement** (event) | Dr. Dest Account → Cr. Source Account | Source/Dest accounts from event | `InventoryAccountingEventListener` |
| **Opening Balance Import** | Dr/Cr accounts per imported rows | Configured per import | `openingBalanceImportService` |
| **Tally Import** | Imported voucher journals | From Tally data | `tallyImportService` |

---

## 6. Chart of Accounts Structure

- **Hierarchy**: Parent-child via `parent_id` and `hierarchy_level`
  - Level 1 = Category (e.g., Current Assets)
  - Level 2 = Subcategory (e.g., Bank Accounts)
  - Level 3+ = Detail/Posting accounts
- **Leaf account check**: `isLeafAccount()` returns true for level ≥ 3
- **Balance validation**: Soft warnings for unusual balance signs (e.g., negative assets)
- **Company defaults**: Configurable default accounts for Inventory, COGS, Revenue, Discount, Tax
- **Tax accounts**: Company-level `gstInputTaxAccountId`, `gstOutputTaxAccountId`, `gstPayableAccountId`

---

## 7. Posting & Reversal Rules

### Journal Entry Lifecycle

```
DRAFT → POSTED
              ↓ (reverse)
           REVERSED (creates reversal journal entry)
              ↓ (void)
           VOIDED
```

### Posting Rules
- **Double-entry balance**: Total debits must equal total credits (tolerance = 0)
- **Each line**: Cannot have both debit > 0 AND credit > 0 on the same line
- **Amounts**: Cannot be negative
- **Must have at least one debit and one credit line**
- **Period check**: Entry date must fall within an OPEN (or LOCKED with exception) period
- **Currency**: INR by default, supports foreign currency with fx_rate
- **Source tracking**: `sourceModule` + `sourceReference` for audit trail

### Reversal Rules
- **Reversal** (`JournalCorrectionType.REVERSAL`): Creates a mirror journal entry (flipped debits/credits), linked via `reversalOf`
- **Void** (`JournalCorrectionType.VOID`): Marks original as voided with reason
- **Cascade Reverse**: Reverses the primary entry AND all related entries (e.g., COGS entry for a sales invoice)
- **Closing Journal Reversal**: On period reopen, the period-closing journal is reversed

---

## 8. Period Lock / Close Logic

### Period States

```
OPEN → LOCKED → CLOSED
  ↑                ↓ (SUPER_ADMIN)
  └──── REOPENED ──┘
```

### Lock
- Any ADMIN/ACCOUNTING user can lock
- Requires `lockReason`
- Sets `lockedAt`, `lockedBy`, `lockReason`

### Close (Maker-Checker Workflow)
1. **Request Close** (`/periods/{id}/request-close`) — ADMIN/ACCOUNTING creates `PeriodCloseRequest` (status: PENDING)
2. **Approve Close** (`/periods/{id}/approve-close`) — ADMIN approves
3. **Reject Close** (`/periods/{id}/reject-close`) — ADMIN rejects

### Pre-Close Checks (in AccountingPeriodServiceCore)
- **Unbalanced journals check**: No unbalanced journals can exist
- **Unlinked documents**: All documents must be linked
- **Unposted documents**: All documents must be posted
- **Uninvoiced receipts**: No uninvoiced goods receipts
- **Checklist validation**: Month-end checklist must be complete (unless force=true)
  - `inventoryReconciled`
  - `arReconciled`
  - `apReconciled`
  - `gstReconciled`
  - `reconciliationDiscrepanciesResolved`
- **Bank reconciliation**: `bankReconciled` flag on period
- **Inventory count**: `inventoryCounted` flag on period
- **Snapshot**: `AccountingPeriodSnapshotService.captureSnapshot()` captures trial balance
- **Closing journal**: Income summary → Retained earnings journal entry
- **Period status**: Set to `CLOSED`

### Reopen
- **SUPER_ADMIN only**
- Reverses the closing journal entry
- Deletes the period snapshot
- Resets period status to `OPEN`

### Closed Period Posting Exceptions
- `ClosedPeriodPostingException` entity allows exceptions for posting to closed periods

---

## 9. Settlement Logic

### Dealer Settlement
- **Input**: dealerId, cashAccountId, amount, allocations (invoiceId + amount), optional discount/write-off/FX accounts
- **Process**:
  1. Validate idempotency key
  2. Lock dealer row (pessimistic lock)
  3. Allocate payment to invoices (FIFO by due date, or explicit allocation)
  4. Create journal: Dr. Cash → Cr. AR (with discount/write-off lines)
  5. Update dealer ledger entries (mark invoices as PAID/PARTIAL)
  6. Create `PartnerSettlementAllocation` records
  7. Sync invoice outstanding amounts
  8. Update dealer `outstandingBalance`

### Supplier Settlement
- Same pattern as dealer but with supplier/AP accounts
- On-account settlements cannot include discount/write-off/FX adjustments

### Auto-Settlement
- Generates deterministic idempotency key from `partnerType|partnerId|cashAccountId|amount`
- FIFO allocation against outstanding invoices/purchases

---

## 10. Tax Posting (CGST/SGST/IGST)

### GstService (Calculation Engine)
- **Intra-state** (source == destination): Split total tax 50/50 → CGST + SGST
- **Inter-state** (source ≠ destination): Full tax → IGST
- **State code normalization**: 2-character uppercase codes
- **Rate validation**: 0–100%

### TaxService (Reporting & Reconciliation)
- **GST Return** (`generateGstReturn`):
  - Output Tax = sum of credits to output tax account (less debits)
  - Input Tax = sum of debits to input tax account (less credits)
  - Net Payable = Output Tax − Input Tax
- **GST Reconciliation** (`generateGstReconciliation`):
  - **Output Tax (Collected)**: Sums CGST/SGST/IGST from posted invoices
  - **Input Tax Credit**: Sums CGST/SGST/IGST from posted purchases (adjusted for returns)
  - **Net Liability**: Collected − ITC per component
  - Handles retained quantity ratio for partial purchase returns
- **Non-GST Mode**: If company `defaultGstRate` is 0, GST returns are zero. Errors if GST accounts are configured.

### Tax Account Configuration
- Per-company: `gstInputTaxAccountId`, `gstOutputTaxAccountId`, `gstPayableAccountId`
- Via `CompanyAccountingSettingsService.requireTaxAccounts()`

---

## 11. Idempotency Controls

### Multi-Layer Idempotency Architecture

1. **Reference Number Uniqueness**: `journal_entries` table has unique constraint on `(company_id, reference_number)`. If the same reference is used twice, the DB rejects it.

2. **Idempotency Key on DTOs**: Almost all mutation DTOs carry an `idempotencyKey` field:
   - `DealerReceiptRequest`, `DealerReceiptSplitRequest`, `DealerSettlementRequest`
   - `SupplierPaymentRequest`, `SupplierSettlementRequest`, `AutoSettlementRequest`
   - `CreditNoteRequest`, `DebitNoteRequest`, `AccrualRequest`, `BadDebtWriteOffRequest`
   - `ManualJournalRequest`, `LandedCostRequest`, `InventoryRevaluationRequest`, `WipAdjustmentRequest`

3. **HTTP Header Idempotency**: Controllers accept `Idempotency-Key` and `X-Idempotency-Key` headers, merged with body keys via `IdempotencyHeaderUtils`.

4. **IdempotencyReservationService**: Core engine uses a reservation pattern:
   - Reserve reference mapping before creating journal
   - If reservation finds existing entry, return existing (idempotent replay)
   - If concurrent save race, await journal creation and return
   - Timeout: 8 seconds with 50ms polling interval

5. **Deterministic Key Generation**: For auto-settlements and receipts without explicit keys:
   - Seed: `partnerType|partnerId|cashAccountId|amount`
   - Key: `IdempotencyUtils.sha256Hex(seed)` → deterministic, reproducible

6. **PartnerSettlementAllocation Idempotency**: 
   - `idempotencyKey` column with unique constraint
   - Lookup via `findByCompanyAndIdempotencyKey` for replay detection
   - Validates partner type, amount, and payload consistency on replay

7. **Import Idempotency**: `OpeningBalanceImport` and `TallyImport` use:
   - File hash (SHA-256) as idempotency key
   - `idempotency_key` + `idempotency_hash` columns
   - `IdempotencyReservationService.assertAndRepairSignature()` for integrity

8. **Reserved Reference Namespaces**: `AccountingFacade.isReservedReferenceNamespace()` prevents users from using system prefixes (JRN-, RCPT-, etc.) for manual journals.

---

## 12. Events Published

### Spring Application Events

| Event | Publisher | Listener | Purpose |
|-------|-----------|----------|---------|
| `JournalEntryPostedEvent` | `AccountingEventStore.recordJournalEntryPosted()` | `AccountingFacadeCore` (@EventListener) | Cache invalidation |
| `AccountCacheInvalidatedEvent` | Internal | Cache listeners | Account cache invalidation |

### Event Store Events (AccountingEventType)

| Event Type | Trigger |
|-----------|---------|
| `JOURNAL_ENTRY_CREATED` | On journal creation |
| `JOURNAL_ENTRY_POSTED` | On journal posting (with per-line events) |
| `JOURNAL_ENTRY_REVERSED` | On journal reversal |
| `JOURNAL_ENTRY_VOIDED` | On journal void |
| `ACCOUNT_CREATED` | On account creation |
| `ACCOUNT_BALANCE_ADJUSTED` | On balance correction |
| `ACCOUNT_DEBIT_POSTED` | Per journal line (debit) |
| `ACCOUNT_CREDIT_POSTED` | Per journal line (credit) |
| `PERIOD_OPENED` | On period creation/reopen |
| `PERIOD_LOCKED` | On period lock |
| `PERIOD_CLOSED` | On period close |
| `PERIOD_REOPENED` | On period reopen |
| `DEALER_RECEIPT_POSTED` | On dealer receipt |
| `SUPPLIER_PAYMENT_POSTED` | On supplier payment |
| `SETTLEMENT_ALLOCATED` | On settlement allocation |
| `BALANCE_CORRECTION` | On balance correction |
| `AUDIT_ADJUSTMENT` | On audit adjustment |

### Inventory → Accounting Event Bridge

| Event | Listener | Action |
|-------|----------|--------|
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener.onInventoryValuationChanged()` | Creates revaluation journal (Dr/Cr Inventory vs Gain/Loss account) |
| `InventoryMovementEvent` | `InventoryAccountingEventListener.onInventoryMovement()` | Creates movement journal (Dr dest → Cr source account), skipped for canonical workflow movements |

### Event Store Features
- **Append-only** audit log (not source of truth for closed-period reporting)
- **Balance replay**: `replayBalanceAsOf()` / `replayBalanceAsOfDate()` reconstructs balances from events
- **Correlation ID**: Groups all events from a single transaction
- **Sequence numbers**: Per-aggregate, with retry on contention
- **Micrometer metrics**: `erp.business.journals.created` counter (total + by company)

---

## 13. Key Architectural Patterns

### Layered Service Architecture
The accounting module uses a "core in `internal/`, thin shell in `service/`" pattern:
- `internal/AccountingCoreEngineCore.java` (334KB) contains all business logic
- `service/*.java` files are thin orchestration layers that inject and delegate
- This allows different services to share core logic while adding their own concerns (e.g., idempotency routing in `AccountingIdempotencyService`)

### Company Multi-Tenancy
- Every entity has a `company` reference
- `CompanyContextService.requireCurrentCompany()` provides current tenant
- `CompanyClock` / `CompanyTime` for tenant-aware timestamps

### Pessimistic Locking
- Critical operations use `lockByCompanyAndId()` on partners, journals, and periods
- Prevents concurrent modifications during settlement, period close

### Audit Trail
- Comprehensive audit via `AuditService`, `AccountingAuditService`, and `AccountingEventStore`
- `AuditDigestScheduler` for periodic audit digests
- Transaction-level audit trail with `AccountingTransactionAuditDetailDto`

### Reference Number System
- `ReferenceNumberService` generates sequential, prefixed references
- `JournalReferenceMapping` supports legacy reference migration
- `JournalReferenceResolver` for canonical lookup

### Temporal Balance Queries
- `TemporalBalanceService` provides:
  - Balance as of a specific date
  - Trial balance as of a date
  - Account activity reports (period)
  - Balance comparisons between dates
- Uses journal lines (primary source) + period snapshots (for closed periods)
