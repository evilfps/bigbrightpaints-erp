# Procure-to-Pay State Machines and Invariants

This document captures the current P2P (purchasing + AP) flows and invariants
for traceability and ERP-grade correctness. It reflects existing behavior only;
no new flows are introduced.

## Raw Material Purchase Statuses
Source: `RawMaterialPurchase`, `PurchasingService`.

States (string values stored on `RawMaterialPurchase.status`):
- `POSTED`: default status on creation (fully outstanding).
- `PARTIAL`: outstanding reduced but not zero.
- `PAID`: outstanding cleared to zero.

Transitions (current behavior):
- Create purchase -> `POSTED`, `outstandingAmount = totalAmount`.
- Supplier settlement reduces `outstandingAmount` by applied + discount + write-off + FX adjustment.
  - outstanding == 0 -> `PAID`
  - 0 < outstanding < total -> `PARTIAL`
  - outstanding == total -> `POSTED`

## Raw Material Movement Types
Source: `RawMaterialService`, `PurchasingService`.

Movements tied to purchases:
- `RECEIPT` with `referenceType = RAW_MATERIAL_PURCHASE` for intake.
- `RETURN` with `referenceType = PURCHASE_RETURN` for supplier returns.

## Supplier Settlements
Source: `AccountingService#settleSupplierInvoices`.

Flow:
- Settlements allocate amounts to purchases via `PartnerSettlementAllocation` rows.
- Journal entry lines:
  - Dr AP (supplier payable) for applied amount.
  - Cr cash/bank for actual cash paid.
  - Optional discount/write-off/FX lines as provided.
- Purchases linked in allocations have `outstandingAmount` reduced by the cleared amount.

## Cross-Module Invariants
These invariants must hold for a canonical P2P flow:
- Purchase → raw material batch created and linked to each line.
- Purchase → raw material movement created per intake line (reference type `RAW_MATERIAL_PURCHASE`).
- Purchase journal:
  - linked to the purchase and to raw material movements (`journalEntryId`).
  - balanced (inventory debits + input tax = AP credit).
- Supplier settlement:
  - creates a balanced journal entry with AP + cash/discount/write-off/FX lines.
  - allocations link back to the supplier and purchase(s).
- Purchase returns:
  - create a `RETURN` movement and a purchase return journal (Dr AP / Cr inventory).
  - stock decreases by return quantity.

## Idempotency and Retry Safety
- Purchase invoices are unique per company (`invoice_number` constraint).
- Purchase journals use reference numbers for idempotent reuse.
- Supplier settlements use `idempotencyKey` to prevent duplicate allocations.

## GST / Tax Handling
- Purchase journals accept optional tax lines.
- Inventory + tax lines must equal the AP total; mismatches reject the post.
