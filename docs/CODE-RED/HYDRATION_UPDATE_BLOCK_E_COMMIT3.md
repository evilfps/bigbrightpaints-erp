# Hydration Update (Block E - Commit 3)

## 2026-02-04
- Scope: Inventory→GL automation hard-disabled in prod until outbox-backed (Block E, commit 3).
- Changes:
  - Set `erp.inventory.accounting.events.enabled=false` in `application-prod.yml`.
  - Added prod hardening test proving the inventory GL auto-posting listener is absent in prod profile.
- Tests:
  - `mvn -B -ntp -Dtest=CR_InventoryGlAutomationProdOffIT test`
  - `bash scripts/verify_local.sh`
- Notes:
  - Full `verify_local` gate passed (schema/flyway/time scans + mvn verify).
