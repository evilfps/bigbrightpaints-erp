# Task 07 — Tenancy / RBAC / Portal Entrypoints Hunt

## Scope
- Workflows: authentication + authorization, multi-company boundaries, dealer portal self-scope.
- Portals: Admin, Accounting, Sales, Factory, Dealer.
- Modules (primary): `auth`, `rbac`, `company`, plus controller annotations across modules.

## ERP expectation
- No cross-company reads/writes: company context must scope every query and write.
- Dealer portal must be self-scoped to the dealer user; dealer must not access admin/sales endpoints.
- “Portal intent” in `docs/API_PORTAL_MATRIX.md` matches actual enforcement in controllers and services.

## Where to inspect in code
- Company scoping filter:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
- Portal endpoint surfaces:
  - `docs/API_PORTAL_MATRIX.md`
  - Controllers in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**/controller/*`
- Dealer portal controller:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/DealerPortalController.java`
- Multi-company switching:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/MultiCompanyController.java`

## Evidence to gather

### SQL probes
- Cross-company link mismatches:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/09_tenancy_cross_company_links.sql`

### GET-only API probes
- Dealer portal:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/02_dealer_portal_gets.sh`
- Orchestrator health endpoints (role reachability + headers):
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/03_orchestrator_health_gets.sh`

### Escalation probes (dev only)
- Attempt cross-company access patterns:
  - valid token + wrong `X-Company-Id`
  - valid token + foreign IDs in path parameters
- Attempt dealer user access to non-dealer endpoints listed in OpenAPI (expect 403).

## What counts as a confirmed flaw (LF)
- Any endpoint that allows a user to access another company’s data (directly or via foreign IDs).
- Any dealer endpoint that leaks other dealers’ records, or allows write operations.
- Any portal/role mismatch that makes a critical workflow unreachable for intended operators (and thus forces “backdoor” usage).

## Why tests might still pass
- Tests often validate happy-path permissions but not “wrong header + right token” combinations.
- Cross-company cases require multiple seeded companies and role memberships.

## Deliverable
- Confirmed LF items with evidence.
- Update `tasks/erp_logic_audit/FINDINGS_INDEX.md` (LF vs LEAD).

