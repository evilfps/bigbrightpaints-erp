# Accounting Module

Last reviewed: 2026-03-29

## Overview

The accounting module owns financial posting, corrections, period controls, settlement truth, and formal reporting boundaries. It is the financial truth boundary for the entire ERP.

## What This Module Owns

- **Journal entries and lines** — the canonical financial record.
- **Chart of accounts** — account hierarchy and classification.
- **Accounting periods** — period open/close/reopen with maker-checker workflow.
- **Reconciliation** — discrepancy resolution and control account matching.
- **Settlements and corrections** — notes, reversals, and explicit correction paths.
- **Data imports** — opening balances (CSV), Tally XML import.
- **Payroll posting seam** — receives payroll journal posting from the HR module.

## Primary Controllers

- `AccountingController` — journal CRUD, account management.
- `AccountingPeriodController` — period open/close/reopen.
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
- **HR → Accounting:** payroll posting and payment seams (`PayrollPostingService` → `AccountingCoreEngineCore`).
- **Inventory -.events.→ Accounting:** `InventoryMovementEvent` → `InventoryAccountingEventListener`.

## Canonical Documentation

For the full architecture reference, see:
- [docs/ARCHITECTURE.md](../../../../../../../docs/ARCHITECTURE.md)
- [docs/INDEX.md](../../../../../../../docs/INDEX.md)

## Architecture Decision Records

> ADRs for accounting decisions will be added in the ADR milestone.

## Known Limitations

- Period close uses a maker-checker workflow, but reopen is super-admin-only.
- Correction/reversal semantics differ slightly between modules that post to accounting.
- Some import paths (Tally XML) have limited error recovery.
