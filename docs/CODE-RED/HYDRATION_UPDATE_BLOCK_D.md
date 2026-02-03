# Hydration Update (Block D)

## 2026-02-04
- Scope: Block D (P0) Dealer AR safety — dealer receipts idempotency (commit 1).
- Changes:
  - Dealer receipt + hybrid receipt now reserve idempotency key before posting; mismatch-safe idempotency enforced.
  - Receipt allocations keyed by idempotency key (not journal reference) with deterministic canonical references.
  - Idempotency key accepted via request body or `Idempotency-Key` header on receipt endpoints.
- Tests:
  - `mvn -B -ntp -Dtest=CR_DealerReceiptSettlementAuditTrailTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed (schema/flyway/time scans + mvn verify).

- Scope: Block D (P0) Dealer AR safety — dealer settlements idempotency + allocation uniqueness (commit 2).
- Changes:
  - Dealer settlement now reserves idempotency key + canonical reference before posting; mismatch-safe validation enforced.
  - Settlement journal lines are built centrally; invoice settlement reference uses canonical reference.
  - Allocation validation fails early for missing invoice/purchase mismatches.
- Tests:
  - `mvn -B -ntp -Dtest=CR_DealerReceiptSettlementAuditTrailTest,AccountingServiceTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed (schema/flyway/time scans + mvn verify).

- Scope: Block D (P0) Dealer AR safety — sales return + credit note idempotency (commit 3).
- Changes:
  - Sales returns now lock invoices to prevent duplicate inventory/journal side effects under retry.
  - Credit notes require idempotency key or reference and enforce mismatch-safe reserve-first idempotency.
  - Credit note replays use stored journal lines; mismatched payloads fail closed.
- Tests:
  - `mvn -B -ntp -Dtest=CR_SalesReturnCreditNoteIdempotencyTest,SalesReturnServiceTest,CreditDebitNoteIT test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed (schema/flyway/time scans + mvn verify).
