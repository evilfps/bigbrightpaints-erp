# Accounting Internals

## Folder Map

- `modules/accounting/controller`
  Purpose: HTTP transport for journals, receipts, settlements, period close, reconciliation, statements, audit, setup, payroll, and imports.
- `modules/accounting/service`
  Purpose: public service layer; mixes canonical wrappers with legacy fan-out services.
- `modules/accounting/internal`
  Purpose: canonical business engines.
- `modules/accounting/domain`
  Purpose: journal, period, subledger, discrepancy, and import persistence truth.
- `modules/accounting/dto`
  Purpose: request/response models for posting, settlement, period, and setup.
- `modules/accounting/event`
  Purpose: accounting event persistence plus the inventory-to-accounting listener boundary.

## Canonical Engines

- `AccountingCoreEngineCore`
  Purpose: canonical GL writer for journal creation, reversal, receipts, settlements, payroll, and inventory adjustments.
- `AccountingFacadeCore`
  Purpose: canonical request-shaping and reference-policy layer over the engine.
- `AccountingPeriodServiceCore`
  Purpose: canonical period-close and reopen state machine.
- `ReconciliationServiceCore`
  Purpose: canonical reconciliation and discrepancy engine.
- `AccountingAuditTrailServiceCore`
  Purpose: canonical transaction-audit read model.

## Wrapper Layers

- thin wrappers:
  - `AccountingCoreEngine`
  - `AccountingCoreLogic`
  - `AccountingCoreService`
  - `AccountingFacade`
  - `AccountingPeriodService`
  - `ReconciliationService`
  - `AccountingAuditTrailService`
  - `AccountingIdempotencyService`
- broad fan-out shell:
  - `AccountingService`

## Major Workflows

### Journal Creation

```text
AccountingController
  -> AccountingService / JournalEntryService / AccountingFacade
  -> AccountingFacadeCore
  -> AccountingCoreEngineCore.createJournalEntry(...)
  -> JournalEntry + JournalLine + account balances + event trail
```

Key methods:
- `AccountingFacade.createManualJournal`
- `JournalEntryService.createStandardJournal`
- `JournalEntryService.createManualJournalEntry`
- `AccountingCoreEngineCore.createJournalEntry`

### Reversal

```text
AccountingController.reverse / cascadeReverse
  -> JournalEntryService.reverseJournalEntry(...)
  -> AccountingCoreEngineCore.reverseJournalEntry(...)
  -> AccountingComplianceAuditService.recordJournalReversal(...)
```

### Settlement and Receipt Execution

```text
AccountingController
  -> DealerReceiptService / SettlementService
  -> AccountingIdempotencyService
  -> AccountingCoreEngineCore.recordDealerReceipt / settle* / recordSupplierPayment
  -> PartnerSettlementAllocation + subledger sync + invoice/purchase state update
```

### Audit and Event Trail

```text
AccountingAuditTrailController
  -> AuditTrailQueryService
  -> AccountingAuditTrailServiceCore

AccountingCoreEngineCore
  -> AccountingEventStore
  -> strict event persistence when enabled
```

## What Works

- one real canonical posting engine exists
- period close and reconciliation each have one real canonical core
- audit trail has a canonical transaction-detail read model
- reports and portals already depend mostly on accounting read models, not on their own duplicated persistence

## Duplicates and Bad Paths

- `AccountingCoreEngine` / `AccountingCoreLogic` / `AccountingCoreService` are wrapper-only layers
- `AccountingService` duplicates the public API of specialized services
- `BankReconciliationSessionService.reconcileLegacy(...)` is an explicit old/new bridge
- deprecated digest endpoints are still live on `AccountingController`
- `JournalReferenceResolver` still walks direct -> legacy -> canonical mappings

## Review Hotspots

- `AccountingCoreEngineCore`
- `AccountingFacadeCore`
- `AccountingPeriodServiceCore`
- `ReconciliationServiceCore`
- `AccountingAuditTrailServiceCore`
- `AccountingService` override block
- `InventoryAccountingEventListener`
