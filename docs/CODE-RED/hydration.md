# Hydration / Verification Rules (V1)

## Non-Negotiable Gates
1) Idempotency
   - Sales order creation idempotent by (company, idempotencyKey).
   - Dispatch confirmation idempotent per packaging slip.
   - Payroll run idempotent by (company, runType, periodStart, periodEnd).

2) Ledger invariants
   - All posted journal entries are balanced.
   - Period lock enforced for all postings (system + manual).
   - No manual journal reference collides with system reference namespace.

3) Inventory invariants
   - No batch quantity goes negative.
   - Dispatch cannot ship more than reserved/available.
   - COGS is computed from dispatch unit cost (FIFO/WAC) deterministically.

4) Timezone invariants
   - Business dates use CompanyClock + company timezone.
   - No ZoneId.systemDefault() in business logic.

## Required Evidence for Every PR
- Code references (file + method) for every business rule touched.
- DB migration impact explained (scan -> clean -> constrain).
- Tests updated to reflect code (not the other way around).

## Required Test Matrix
- Sales: order idempotency, reservation, partial dispatch, invoice amounts, journal uniqueness.
- Purchasing: GRN partials, invoice posting, purchase return.
- Payroll: idempotency, attendance windows, advances/deductions.
- Manufacturing: WIP journals, packing journals, cost allocation month boundaries.

