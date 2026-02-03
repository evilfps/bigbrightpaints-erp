# Hydration Update (Block A Remaining)

## 2026-02-03
- Scope: Block A (P0) Tenant Isolation + Public Attack Surface Hardening — remaining items.
- Changes:
  - Identity naming alignment (`companyCode` canonical, legacy aliases retained) and mismatch fail-closed.
  - Health endpoints locked down in prod; `/api/integration/health` and orchestrator health require ROLE_ADMIN.
  - Service-layer membership checks for company switch/update/delete (defense-in-depth).
- Tests:
  - `mvn -B -ntp -Dtest=CR_HealthEndpointProdHardeningIT test`
  - `mvn -B -ntp -Dtest=CompanyServiceTest test`
  - `bash scripts/verify_local.sh`
- Notes:
  - `verify_local` still failing due to existing suite failures:
    - `OrchestratorControllerIT.approve_order_creates_outbox_event` (409 CONFLICT).
    - `ErpInvariantsSuiteIT.hireToPay_goldenPath` (expected PAID, got POSTED).
