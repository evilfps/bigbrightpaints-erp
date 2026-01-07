# Reconciliation Contracts (Epic 08)

## Tolerance
- Monetary variances must be within 0.01 to be considered reconciled.

## Inventory vs GL
- Inventory valuation must reconcile to the inventory control account.
- Prefer the company's default inventory account; fall back to inventory-named accounts if unset.

## AR vs Dealer Ledger
- AR control accounts (codes containing AR/RECEIVABLE) must match summed dealer ledger balances.
- Dealer outstanding balances should align with ledger balances within tolerance.

## AP vs Supplier Ledger
- AP control accounts (codes containing AP/PAYABLE) must match summed supplier ledger balances.
- Supplier outstanding balances should align with ledger balances within tolerance.

## Period Close Controls
- No unposted documents in the period window (draft invoices/purchases/payroll runs).
- No unlinked documents in the period window (posted invoices/purchases/payroll runs missing journal links).
- No unbalanced journals in the period window.
