# Repo Overview

Last reviewed: 2026-02-15

Primary runtime areas:
- `erp-domain/`: Spring Boot backend and all module/domain code.
- `scripts/`: verification, guards, migrations, and CI wiring.
- `docs/`: architecture, policy, and execution contracts.
- `testing/`, `artifacts/`: harness and result history.

Top-level module list:
- `modules/accounting`, `admin`, `auth`, `company`, `factory`, `hr`, `inventory`, `invoice`, `production`, `purchasing`, `rbac`, `reports`, `sales`, plus shared cross-module runtime `orchestrator`.
- `docs/system-map/modules/*/FILES.md` contains module file-level maps.

Canonical path and migration policy:
- All future schema work must target `erp-domain/src/main/resources/db/migration_v2`.
- Legacy `db/migration` is historical and not modified for new work.

Duplicate/overlap evidence map (current):
Endpoint duplication:
- Sales dispatch can be initiated through canonical sales flow and legacy orchestrator endpoints (`OrchestratorController.fulfillOrder/dispatchOrder`), creating duplicated orchestration control.
- Partner onboarding (dealer role) is exposed through multiple onboarding-related paths and handlers, with legacy `createDealer` touchpoints that need canonical ownership cleanup.

File-level overlap candidates:
- `PayrollRunRequest` duplicates exist in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/dto/PayrollRunRequest.java` and `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/dto/PayrollRunRequest.java`.
- `ResetPasswordRequest` and `ForgotPasswordRequest` appear in both `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/dto/` and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/web/`.
- Governance guidance is split across multiple AGENTS files (`accounting`, `sales`, `inventory`, `hr`) with different update cadences and overlap risk.

Method-level overlap candidates:
- `applyIdempotencyKey` is repeated in controllers in `modules/accounting`, `modules/factory`, `modules/inventory`, `modules/purchasing`.
- `resolveIdempotencyKey` and `dispatchOrder` are implemented both in `SalesService` and `OrchestratorController`.
- `postSalesJournal` and `postCogsJournal` cross module boundaries (`SalesJournalService`, `SalesService`, `AccountingFacade`), with duplicate posting pathways.
- `normalizeIdempotencyKey` is implemented in `modules/purchasing`, `modules/inventory`, `modules/production` helpers with semantically similar behavior.

Prioritized cleanup queue from map evidence:
1. **Flow overlap cleanups (high)**: reconcile sales dispatch/dealer onboarding; retire deprecated orchestrator-only execution routes.
2. **Journal overlap control (high)**: consolidate `postSalesJournal`/`postCogsJournal` ownership in canonical `SalesFulfillmentService` + `AccountingFacade` edge.
3. **Schema overlap risk (high)**: finish V2 convergence migrations and keep v1 legacy untouched except compatibility.
4. **Idempotency helper debt (medium)**: centralize helper behavior so request-key canonicalization and partner settlement key handling are single-source and auditable.
5. **DTO/API overlap (medium)**: deduplicate `PayrollRunRequest` and auth reset/forgot request models behind module boundary contracts.

Residual risks:
- Any duplicate paths listed as deprecated remain temporarily usable for compatibility and should be guarded by strict contract tests until fully retired.
- Some test-only legacy overlaps are intentional for backward-compatibility verification.
