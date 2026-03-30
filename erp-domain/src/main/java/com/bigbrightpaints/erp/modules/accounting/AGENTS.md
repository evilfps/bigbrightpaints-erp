# Accounting Module

Last reviewed: 2026-03-30

## Overview

The accounting module owns financial posting, corrections, period controls, settlement truth, and formal reporting boundaries. It is the financial truth boundary for the entire ERP.

## What This Module Owns

- **Journal entries and lines** — the canonical financial record.
- **Chart of accounts** — account hierarchy and classification.
- **Partner ledgers** — `DealerLedgerEntry` (AR) and `SupplierLedgerEntry` (AP) tracking per-partner financial position.
- **Bank reconciliation sessions** — matching bank statements against ledger entries.
- **Accounting periods** — period open/close/lock/reopen with maker-checker workflow.
- **Reconciliation** — discrepancy resolution and control account matching.
- **Settlements and corrections** — notes, reversals, and explicit correction paths (see Posted-Truth Rules below).
- **Data imports** — opening balances (CSV), Tally XML import.
- **Payroll posting seam** — receives payroll journal posting from the HR module.

## Primary Controllers

- `AccountingController` — journal CRUD, account management, **period lifecycle** (list/create/update/close/lock/reopen/request-close/approve-close/reject-close), GST returns, reconciliation, bank reconciliation sessions, statements, and settlement surfaces.
- `OpeningBalanceImportController` — CSV opening balance import.
- `TallyImportController` — Tally XML import.

## Key Services/Facades

- `AccountingCoreEngineCore` — centralized journal creation and payroll posting.
- `AccountingPeriodServiceCore` — period lifecycle management.
- `AccountingFacade` — cross-module facade for financial side effects.
- `ReconciliationServiceCore` — reconciliation and discrepancy resolution.
- `OpeningBalanceImportService` — opening balance import processing.
- `TallyImportService` — Tally XML import processing.

## DTO Families

- `JournalCreationRequest` — debit/credit lines, source module, reference, period date.
- `JournalEntry` / `JournalLine` — canonical journal representation.
- `AccountDto` — chart of accounts representation.
- `AccountingPeriodDto` — period lifecycle representation.

## Cross-Module Boundaries

- **Sales → Accounting:** dispatch-linked journals, invoice-linked journals, AR settlements.
- **Purchasing → Accounting:** GRN-linked journals, purchase return journals, AP settlements.
- **Factory → Accounting:** WIP/consumption journals, packaging material journals.
- **HR → Accounting:** payroll posting and payment seams (`PayrollPostingService` → `AccountingFacade`).
- **Inventory -.events.→ Accounting:** `InventoryMovementEvent` → `InventoryAccountingEventListener`.

## Canonical Documentation

For the full architecture reference, see:
- [docs/ARCHITECTURE.md](../../../../../../../docs/ARCHITECTURE.md)
- [docs/INDEX.md](../../../../../../../docs/INDEX.md)

## Architecture Decision Records

> ADRs for accounting decisions will be added in the ADR milestone.

## Posted-Truth Rules

Accounting enforces strict immutability for posted journal entries:

1. **Posted entries are never edited** — only reversed via a new counter-entry.
2. **Reversal types** — `JournalCorrectionType.REVERSAL` (full or partial) and `JournalCorrectionType.VOID` (void-only).
3. **OPEN period** — entries can be reversed freely.
4. **LOCKED period** — entries **cannot** be reversed; the period must be unlocked first.
5. **CLOSED period** — reversal requires admin override authority with audit approval (`overrideAuthorized = true`).
6. **Period-lock enforcement** — controlled by `systemSettingsService.isPeriodLockEnforced()`. When enforced, the reversal must land in a postable period validated by `accountingPeriodService.requirePostablePeriod()`.
7. **Compliance audit** — all reversals are recorded via `AccountingComplianceAuditService` including admin-override metadata.

## Known Limitations

- Period close uses a maker-checker workflow, but reopen is super-admin-only.
- Some import paths (Tally XML) have limited error recovery.
- Partner ledgers (`DealerLedgerEntry`, `SupplierLedgerEntry`) track per-partner financial position but are derived from journal entries rather than independently posted.
