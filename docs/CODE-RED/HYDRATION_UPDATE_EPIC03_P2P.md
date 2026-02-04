# Hydration Update (EPIC 03 - P2P Safety)

## 2026-02-04
- Scope: EPIC 03 Purchasing/AP safety (GRN idempotency + period lock, supplier payment/settlement idempotency, period close posted-ish handling + predeploy scans).
- Changes:
  - GRN reserve-first idempotency + closed/locked period guard.
  - Supplier payment/settlement idempotency reserve-first + mismatch-safe behavior + allocation uniqueness.
  - Period close checklist treats POSTED|PARTIAL|PAID as posted while still requiring journal linkage; added predeploy scans for posted‑ish missing journals and duplicate supplier allocations.
- Tests:
  - `mvn -B -ntp -Dtest=CR_PurchasingToApAccountingTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed (schema/flyway/time scans + mvn verify).
