# TKT-ERP-STAGE-113 OpenAPI Duplicate Route Analysis

Date: 2026-02-26  
Ticket: `TKT-ERP-STAGE-113`  
Mode: planning-only (`openapi.json` analysis, no implementation changes)

## Problem Framing And Assumptions

The blocker plan for `B01-B14` depends on API-surface decisions that avoid unnecessary endpoint proliferation. This analysis identifies duplicate or near-duplicate functional routes in OpenAPI so blocker fixes can favor refactoring existing contracts over creating net-new APIs.

Assumptions used:
- Primary source: `/openapi.json` (228 paths, 276 operations).
- Cross-check source: `/erp-domain/openapi.json` (214 paths, 259 operations).
- Duplicate means two routes expose the same business action with different entrypoints.
- Similar means routes touch the same lifecycle state transition and can diverge in validation, authz, idempotency, or response envelopes.
- Strategy preference order: `extend_existing` first, `deprecate_alias` second, `new_endpoint_required` only when lifecycle actions are missing.

## Scope Boundaries And Non-Goals

In scope:
- Routes relevant to blockers `B01-B14`.
- Controller-level duplication and contract drift risk.
- Migration compatibility planning for API clients.

Out of scope:
- Controller/service implementation changes.
- Final deprecation timeline approval.
- Consumer-by-consumer rollout sequencing (captured separately in blocker dispatch/release planning).

## Duplicate Or Similar Route Findings

| canonical route | duplicate/similar routes | overlap reason | recommended consolidation path | migration/compat strategy | blocker IDs impacted |
| --- | --- | --- | --- | --- | --- |
| `POST /api/v1/sales/dispatch/confirm` | `POST /api/v1/dispatch/confirm` | Same dispatch confirmation intent exists in Sales and Dispatch controllers with different DTOs (`DispatchConfirmRequest` vs `DispatchConfirmationRequest`). | Keep Sales dispatch confirmation contract as canonical business transition gate; make Dispatch route call the same application service and validation path. | `deprecate_alias`: keep `/dispatch/confirm` as compatibility alias for one release with deprecation headers, then remove. | `B03,B04,B09` |
| `POST /api/v1/orchestrator/dispatch/{orderId}` | `POST /api/v1/orchestrator/dispatch` | Two orchestrator dispatch entrypoints execute the same action via path-id vs body payload. | Canonicalize route with explicit `{orderId}` path param and normalize alias payload into same command handler and idempotency key derivation. | `deprecate_alias`: preserve body-based alias short-term, hard-cut after client migration. | `B02,B09` |
| `POST /api/v1/sales/orders/{id}/confirm` | `POST /api/v1/orchestrator/orders/{orderId}/approve`, `POST /api/v1/orchestrator/orders/{orderId}/fulfillment` | Lifecycle transitions can be triggered from different controllers, creating fail-open risk if guards diverge. | Keep one shared lifecycle/state-machine service as source of truth for all three routes; route-specific controllers only map transport DTOs and auth context. | `extend_existing`: keep all routes but enforce one transition contract and one guard set. | `B02,B03` |
| `GET /api/v1/auth/me` | `GET /api/v1/auth/profile` | Two identity-read routes can drift in claims projection and recovery-related flags. | Use `/auth/me` as canonical identity snapshot; make `/auth/profile` GET path invoke same query service and response normalization. | `deprecate_alias` for profile GET only; keep `PUT /auth/profile` as profile-update surface. | `B08,B10` |
| `PATCH /api/v1/admin/users/{id}/mfa/disable` | `POST /api/v1/auth/mfa/disable` | Same security-sensitive action exposed with different actor scopes (admin override vs self-service), risking policy drift. | Preserve both routes but force shared MFA disable policy service with explicit actor-type checks and uniform audit event schema. | `extend_existing`: no new route; strict actor-scoped authz and shared auditing. | `B01,B08,B10` |
| `GET|POST /api/v1/payroll/runs` | `GET|POST /api/v1/hr/payroll-runs` | Duplicate payroll run create/list surfaces return different envelope fidelity (`PayrollRunDto` vs map payload). | Keep `/payroll/runs` as canonical payroll lifecycle API; map HR alias to the same handlers and DTOs. | `deprecate_alias`: transition `/hr/payroll-runs` consumers to canonical routes. | `B05` |
| `POST /api/v1/payroll/runs/{id}/post` | `POST /api/v1/accounting/payroll/payments`, `POST /api/v1/accounting/payroll/payments/batch` | Payroll finalization and accounting posting can be triggered from multiple surfaces, creating correction/reversal drift. | Make payroll post path the canonical lifecycle trigger; accounting payment endpoints must consume shared posting/correction service with linked run references. | `extend_existing`: keep existing endpoints but unify idempotency, linkage, and reversal metadata contracts. | `B05` |
| `GET /api/v1/invoices/dealers/{dealerId}` | `GET /api/v1/dealers/{dealerId}/invoices`, `GET /api/v1/dealer-portal/invoices` | Dealer invoice retrieval appears under invoice, dealer, and portal namespaces with schema inconsistency (`ListInvoiceDto` vs map). | Canonicalize invoice-controller contract (`/invoices/dealers/{dealerId}`); dealer/portal routes become scoped facades over same query and schema adapter. | `deprecate_alias` for `/dealers/{dealerId}/invoices`; keep portal facade with schema parity. | `B03,B12` |
| `GET /api/v1/accounting/aging/dealers/{dealerId}` | `GET /api/v1/dealers/{dealerId}/aging`, `GET /api/v1/dealer-portal/aging`, `GET /api/v1/accounting/reports/aging/dealer/{dealerId}` | Aging data exists as summary/detail across dealer/accounting/report controllers with mixed typed vs map envelopes. | Define one typed aging contract with detail-level option; keep portal/dealer routes as facades invoking the same service. | `deprecate_alias` for untyped dealer aging route; preserve portal path as compatibility facade. | `B12` |
| `GET /api/v1/accounting/trial-balance/as-of` and accounting hierarchy routes (`/api/v1/accounting/reports/balance-sheet/hierarchy`, `/api/v1/accounting/reports/income-statement/hierarchy`) | `GET /api/v1/reports/trial-balance`, `GET /api/v1/reports/balance-sheet`, `GET /api/v1/reports/profit-loss` | Dual financial statement stacks can return conflicting numbers or different cut-off rules. | Keep accounting ledger-backed routes as canonical; reports routes call same query services and apply presentation-only transforms. | `deprecate_alias` where report routes duplicate accounting outputs; maintain compatibility adapters during migration. | `B12,B13` |
| `POST /api/v1/inventory/adjustments` | `POST /api/v1/accounting/inventory/revaluation`, `POST /api/v1/accounting/inventory/wip-adjustment` | Inventory correction value changes can be initiated from inventory and accounting APIs with separate semantics. | Consolidate correction lifecycle at inventory adjustment entrypoint; accounting routes must delegate to shared adjustment + journal pipeline with reason codes. | `extend_existing`: retain accounting endpoints only as specialized adapters, no new root route. | `B04,B13` |
| `PATCH /api/v1/factory/production-plans/{id}/status` | `PUT /api/v1/factory/production-plans/{id}`, `DELETE /api/v1/factory/production-plans/{id}` | Multiple mutation verbs on the same resource allow lifecycle drift and destructive delete behavior. | Make status patch canonical for lifecycle transitions (`cancel/rework/reopen`); restrict PUT to non-state fields; replace hard-delete semantics with reversible status transitions. | `extend_existing` for status flow; deprecate destructive delete behavior in compatibility phase. | `B11` |
| `POST /api/v1/purchasing/raw-material-purchases/returns` | `GET|POST /api/v1/purchasing/purchase-orders`, `GET|POST /api/v1/purchasing/goods-receipts`, `POST /api/v1/accounting/sales/returns` | Return actions exist, but PO/GRN reopen/void lifecycle actions are not represented as first-class transitions. | Reuse existing purchasing roots first; introduce minimal action endpoints only for missing transitions (reopen/void) that cannot be encoded in current contracts. | `new_endpoint_required` only for missing reopen/void actions; all other updates use existing routes. | `B06` |
| `POST|GET /api/v1/admin/roles` and `GET /api/v1/admin/roles/{roleKey}` | none in OpenAPI | No duplicate role mutation route detected; blocker risk is authorization semantics rather than route multiplicity. | Keep existing admin-role surfaces and enforce stricter tenant/global role mutation boundaries and fail-closed guards. | `extend_existing` (no alias deprecation needed). | `B01` |
| `N/A (non-API blockers)` | none in OpenAPI | `B07` (governance traceability) and `B14` (shell portability) are process/tooling blockers, not API blockers. | Keep process artifacts/scripts in existing locations; no API surface changes. | `extend_existing` at process level. | `B07,B14` |

## Blocker Strategy Rollup (For Plan/Matrix Synchronization)

| blocker_id | recommended_api_change_strategy | duplicate/similar route handling note |
| --- | --- | --- |
| B01 | `extend_existing` | Keep `/admin/roles*` as canonical role mutation surface; no duplicate API, tighten tenant/global guards. |
| B02 | `deprecate_alias` | Collapse orchestrator dispatch aliases into one canonical contract and idempotency path. |
| B03 | `extend_existing` | Harmonize sales confirm + orchestrator approve/fulfillment through one lifecycle state-machine service. |
| B04 | `deprecate_alias` | Standardize dispatch confirmation contract and retire generic duplicate confirmation endpoint. |
| B05 | `deprecate_alias` | Decommission `/hr/payroll-runs` alias after `/payroll/runs` contract adoption. |
| B06 | `new_endpoint_required` | Add minimal reopen/void purchasing lifecycle actions only where existing PO/GRN routes cannot represent transitions. |
| B07 | `extend_existing` | Non-API blocker; no OpenAPI route change required. |
| B08 | `extend_existing` | Reuse auth/admin credential routes with stricter secret/recovery policies and shared security checks. |
| B09 | `deprecate_alias` | Remove duplicate dispatch aliases and enforce one sanitized correlation/idempotency ingestion path. |
| B10 | `extend_existing` | Unify identity/recovery and MFA-disable policy services across existing auth/admin routes. |
| B11 | `extend_existing` | Use production plan status endpoint as lifecycle source of truth; phase out destructive delete semantics. |
| B12 | `deprecate_alias` | Consolidate dealer/reporting read surfaces onto canonical accounting/invoice contracts with facade compatibility. |
| B13 | `extend_existing` | Centralize inventory correction semantics on `/inventory/adjustments`; accounting adjustments become adapters. |
| B14 | `extend_existing` | Non-API blocker; script portability only. |

## Risk Register And Rollback Strategy (OpenAPI-Specific)

| risk_id | trigger | impact | mitigation | rollback strategy |
| --- | --- | --- | --- | --- |
| OR-01 | Alias deprecation before client migration | Breaking API consumers | Require response deprecation headers and timeline in release notes | Re-enable alias controller mapping until consumers complete cutover |
| OR-02 | Contract unification changes response envelope shape | Frontend/integration parsing failures | Maintain adapter layer and schema snapshot tests per alias | Roll back adapter removal and restore prior envelope version |
| OR-03 | Shared lifecycle service introduces cross-module side effects | Regression in sales/inventory/accounting transitions | Gate with blocker-targeted tests + changed-file coverage | Revert consolidation commit for affected blocker branch only |
| OR-04 | New endpoint introduction for B06 expands scope | Delivery delay | Strictly limit to missing reopen/void transitions and reuse existing DTO patterns | Defer optional actions and ship only mandatory reopen/void path |
