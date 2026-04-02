# Architecture

High-level system map for the `orchestrator-erp` cleanup mission.

This file is internal worker guidance for the mission infrastructure, not a public canonical docs entrypoint.

**What belongs here:** system shape, module relationships, canonical write/read boundaries, deployment-proof surfaces, and the architectural seams workers must preserve while simplifying the repo.

---

## System Shape

Current code package root remains `com.bigbrightpaints.erp`, but the current product identity for touched canonical docs is `orchestrator-erp`.

The backend is a modular monolith:

- `core/` — security, config, exception handling, audit, idempotency, health, utilities
- `modules/` — business domains (`accounting`, `admin`, `auth`, `company`, `factory`, `inventory`, `invoice`, `portal`, `production`, `purchasing`, `rbac`, `reports`, `sales`, plus currently paused `hr`)
- `orchestrator/` — background coordination, outbox/event publishing, command dispatch, schedulers, health surfaces
- `shared/dto/` — shared API envelopes and cross-cutting DTOs

The codebase already has strong infrastructure patterns (idempotency, outbox/event delivery, RBAC, multi-tenancy, audits), but important business flows still cross module boundaries through large services, listeners, helpers, and duplicated contract surfaces.

## Mission Cleanup Posture

This mission is not preserving dead compatibility.

- No legacy data compatibility is required.
- No active frontend consumers need protecting.
- No new fallbacks, aliases, dual-write paths, or duplicate helper seams should be introduced.
- Prefer deletion, extraction, and reuse of the surviving canonical path.
- Accounting refactors must preserve dependent-module correctness and remove duplicates rather than relocate them.
- HR/payroll feature work is out of scope unless a shared guard/test/doc surface must stay consistent.

## Runtime Boundary

The approved runtime boundary for this mission is fixed:

- Postgres: `5433`
- RabbitMQ: `5672`
- MailHog UI: `8025`
- App HTTP: `8081`
- Actuator/management: `9090`

Workers must not use host Postgres `5432` or introduce new services outside this boundary without returning to the orchestrator.

## Canonical Public Contract Surfaces

These are the highest-value public surfaces that must stay singular and aligned across code, OpenAPI, docs, tests, and worker guidance:

- Auth bootstrap: `GET /api/v1/auth/me`
- Public password reset corridor: `POST /api/v1/auth/password/forgot|reset`
- Dispatch write boundary: `POST /api/v1/dispatch/confirm`
- Accounting manual journal boundary: `POST /api/v1/accounting/journal-entries`
- Canonical superadmin tenant-runtime mutation paths:
  - `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
  - `PUT /api/v1/superadmin/tenants/{id}/limits`

Retired routes should be absent or explicitly fail closed. Do not leave a second public writer behind.

## Core Architectural Invariants

- **Tenant scoping is mandatory.** Company-scoped data and request admission depend on company context and must fail closed.
- **`ApplicationException` + `ErrorCode` remain the business error contract.**
- **Accounting is the financial truth boundary.** Other modules may initiate business flows, but accounting owns journals, settlements, period control, and reconciliation truth.
- **Idempotency is mandatory on write surfaces.** Reject stale headers and parallel fallback replay schemes.
- **Role/host boundaries are part of the contract.** Admin/control-plane, operational/factory, sales/commercial, and dealer/self-service surfaces must stay explicit.
- **Docs/OpenAPI/tests/CI are part of the architecture.** A cleanup is incomplete if these still teach contradictory truths.

## Highest-Risk Cleanup Seams

### 1. Accounting core

Primary hotspot:

- `modules/accounting/internal/AccountingCoreEngineCore`

Supporting sprawl:

- `modules/accounting/service/AccountingCoreEngine`
- `modules/accounting/service/AccountingCoreLogic`
- `modules/accounting/service/AccountingCoreService`
- `modules/accounting/internal/AccountingFacadeCore`
- `modules/accounting/controller/AccountingController`

Cleanup direction:

- Split by business flow, not by more wrapper layers
- Keep one canonical write path per operation
- Remove duplicate helper logic while preserving downstream module behavior

### 2. Dispatch truth

Dispatch is a cross-module seam. The public write host, downstream financial ownership, docs, OpenAPI, tests, and validator guidance must all converge on the same truth.

Workers must answer both questions whenever dispatch is touched:

1. Which public controller/host owns dispatch today?
2. Which service path owns the authoritative downstream business and financial effects?

### 3. Security/runtime admission

Primary hotspots:

- `core/security/CompanyContextFilter`
- `modules/company/service/TenantRuntimeEnforcementService`
- `modules/company/service/TenantRuntimeRequestAdmissionService`
- `core/security/TenantRuntimeAccessService`
- `core/util/CompanyEntityLookup`

Cleanup direction:

- Keep tenant binding fail-closed
- Keep canonical control-plane paths singular
- Remove shadow runtime owners
- Replace giant generic lookup gravity wells with narrower module-scoped resolution

### 4. Deployment-proof and CI truth

The deployability story is split across:

- strict compose runtime
- gate scripts
- workflow files
- runbooks/docs
- generated artifacts and old mission-specific guidance

Cleanup direction:

- one real strict smoke story
- one real release-proof story
- docs-only governance stays narrow
- stale generated artifacts do not masquerade as current proof

## Canonical Dependency Map

These edges are the main blast-radius map for refactors:

- `sales -> inventory` for dispatch, stock execution, and reservation-facing behavior
- `sales -> accounting` for AR/revenue/COGS and settlement truth
- `factory -> inventory` for production, packing, and finished-goods registration
- `factory -> accounting` for manufacturing/packing side effects
- `purchasing -> inventory` for GRN/opening stock/returns/stock intake
- `purchasing -> accounting` for AP, supplier settlement, and purchase-return truth
- `invoice -> accounting` for posting, settlement, and reference behavior
- `reports -> accounting/inventory/sales` for downstream truth consumption
- `auth/company/admin -> every tenant business surface` for company binding, control-plane, and runtime enforcement
- `orchestrator -> sales/factory/accounting/inventory` for background coordination and fail-closed retirement checks

Accounting cleanup must explicitly re-verify dependent module flows across sales, inventory, purchasing, invoice, and reporting.

## Canonical Flow Spine

For the current mission, the highest-value operator flow is:

`tenant onboarding -> company defaults -> brand/item setup -> readiness review -> opening stock -> production log -> packing record -> dispatch confirm`

This flow spans control-plane, production/catalog, inventory, factory, dispatch, and accounting/reporting consumers. Cleanup work must not reopen aliases or second owners inside this spine.

## Validation Model

This mission’s validation model is:

- **strict `prod,flyway-v2` compose smoke** for deployment/runtime proof
- **targeted Maven suites** for business-flow proof

Treat these as complementary:

- compose smoke proves the runtime boots and exposes the expected health/app boundary
- targeted suites prove business-critical invariants for dispatch, accounting, runtime admission, and dependent-module flows

## Canonical Evidence Sources

When workers need truth, prefer this order:

1. controller annotations + `openapi.json` for route/payload truth
2. service/facade/engine code for lifecycle and side-effect truth
3. focused tests for executed behavior and retirement proof
4. canonical docs for operator/developer-facing explanation
5. library/validator guidance only after it has been aligned to the canonical contract

## Worker Design Implications

- Do not split a god class into several duplicate god helpers.
- Do not leave a deprecated route or helper alive “just in case.”
- If a path is no longer canonical, remove it or retire it explicitly.
- If a refactor touches accounting, verify dependent modules in the same packet or return a tracked issue immediately.
- If a feature changes a canonical contract surface, update OpenAPI/docs/tests/guidance in the same packet.
