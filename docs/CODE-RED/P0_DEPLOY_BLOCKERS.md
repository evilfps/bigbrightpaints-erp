# CODE-RED P0 Deploy Blockers (Must Fix or Prod-Gate)

Last updated: 2026-02-03

Purpose: a single, concrete list of **P0** items that block a safe enterprise deploy. For details, see:
- `docs/CODE-RED/plan-v2.md`
- `docs/CODE-RED/MODULE_AUDIT_SUMMARY.md`
- `docs/CODE-RED/DEDUPLICATION_BACKLOG.md`

## P0 - Tenant Isolation / Security
- Orchestrator must not accept a caller-controlled company override (`X-Company-Id`) that can diverge from the authenticated company context.
  - Fix: derive companyId from JWT/company context, or enforce header == JWT companyId and fail-closed (403) on mismatch.
  - Add tests for header spoofing attempts on orchestrator endpoints.
- Orchestrator health endpoints must require ops/admin authorization in non-dev environments.
- Company context must not be header-only for unauthenticated requests:
  - Fix: do not set `CompanyContextHolder` from `X-Company-Id` unless the request is authenticated (JWT `cid`).
- Public health surfaces must be intentional:
  - `/api/integration/health` should be secured (prefer actuator health) or proven safe as an unauthenticated surface.
- Actuator + docs must be prod-hardened (public attack surface):
  - Prod stance: do not expose Swagger/OpenAPI (`/swagger-ui/**`, `/v3/**`) unless explicitly secured on a management port.
  - Actuator exposure must be minimal (prefer `health` + `info` only) and must not leak details.
  - Status (2026-02-03): ✅ Swagger/OpenAPI disabled in prod, actuator info env disabled; tests: `CR_SwaggerProdHardeningIT`, `CR_ActuatorProdHardeningIT`.
- CORS must be safe by default:
  - Reject wildcard origins (`*`) in settings when credentials are enabled; prefer explicit HTTPS origins.
  - Add tests for invalid origin updates + regression tests for preflight behavior.
  - Status (2026-02-03): ✅ Wildcard rejected + https enforced (localhost http allowed); tests: `SystemSettingsServiceCorsTest`.
- Company membership enforcement must not depend only on controllers:
  - Any multi-company “switch” and any company update/delete path must enforce membership in the service layer too (defense-in-depth).
- Identity vocabulary must be unambiguous (prevent future tenant isolation bugs):
  - `companyCode` is the tenant context string; reserve `companyId` for numeric DB ids.
  - Deprecate misleading header/claim/DTO names where `companyId` actually means `companyCode` (backward compatible parsing window).

## P0 - Workflow “Truth” (Status Must Not Bypass Financial/Inventory Truth)
- Orchestrator must not be able to mark orders `SHIPPED/DISPATCHED` without the canonical dispatch chain:
  slip → invoice → journals → ledger updates (`SalesService.confirmDispatch`).
- Status-only fulfillment updates to `SHIPPED/DISPATCHED` are now fail-closed in orchestrator code (no bypass path).
- Orchestrator “lightweight dispatch” endpoints (`/api/v1/orchestrator/dispatch*`) are hard deprecated (always 410 + canonicalPath).
- Sales COGS posting must be single-truth:
  - COGS/Inventory relief must only be posted by dispatch-confirm (per-slip reference), not by any alternate “order fulfillment” helper.
- Sales AR/Revenue posting must be single-truth:
  - Sales journals must dedupe across the canonical `INV-<orderNumber>` reference and any custom invoice-number reference.
  - Dispatch confirm must not create a second AR/Revenue journal if an `INV-<orderNumber>` journal already exists (even if not linked on the order).
- Orchestrator “non-final” status writes must still be guarded:
  - `PROCESSING/CANCELLED/READY_TO_SHIP` updates must respect the sales state machine and must not be free-form status setters.
- Mutating/nondeterministic “finder” endpoints must not exist (read-only GET must have no side effects and must fail-closed on ambiguity).
- Packaging slip status updates must enforce a real state machine (no free-form statuses that skip reservation/inventory unwind).

## P0 - Idempotency / Deterministic References
- Orchestrator write commands must be idempotent at the boundary.
  - `Idempotency-Key` is required and reserved in `orchestrator_commands` (scope: company + command + key).
- Orchestrator feature flags must be enforced in the service layer too (defense-in-depth):
  - If `orchestrator.factory-dispatch.enabled=false` or `orchestrator.payroll.enabled=false`, service invocation must fail closed even if the controller is bypassed.
- Manufacturing/packing endpoints must be retry-safe at the API boundary (double-click/network/orchestrator retries must not double-consume or double-post).
  - Bulk pack reference must be deterministic (no `System.currentTimeMillis()`); retries must not double-consume packaging.
  - Packing record retries must not double-consume packaging or double-post journals.
  - Opening stock import must have an import idempotency key; retry must not create new batches/movements/journals.
- Dealer receipts/settlements must be idempotent (caller idempotency key enforced; allocations deterministic).
- Add DB uniqueness where needed to prevent duplicate reservations/batches under concurrency.
  - Packaging slips must not duplicate per order under concurrency (guard at DB and service layer).
- Idempotency must be mismatch-safe:
  - If a replay hits an existing reference but payload differs materially (amount/accounts), fail closed with a conflict (no silent reuse).
- Observability must be enterprise-grade for every privileged write:
  - Standardize `requestId/traceId/correlationId/idempotencyKey/referenceNumber` so we can audit “who did what” end-to-end across modules.
  - Outbox/audit must be queryable by these identifiers without parsing payload JSON.

## P0 - Accounting Correctness (Enterprise Grade)
- Payroll payments must clear Salary Payable (no double-expensing).
  - `POST /api/v1/accounting/payroll/payments` now requires POSTED runs, posts **Dr SALARY-PAYABLE / Cr CASH**, and stores `payroll_runs.payment_journal_entry_id`.
  - HR `POST /api/v1/payroll/runs/{id}/mark-paid` is blocked unless a payment journal exists (prevents “PAID with no payment evidence”).
- Closed-period reporting must use **period-end snapshots** as the source of truth.
  - Fix: persist period-end snapshots at close, and make report paths read snapshots for CLOSED periods.
- Fix FIFO valuation to use remaining/available quantities (not total quantities) so depleted batches don’t inflate valuation.
- Period-close postings must not bypass accounting posting boundaries (no direct account balance mutation outside `AccountingFacade`/`AccountingService` invariants).

## P0 - Deploy Gates / Flyway Discipline
- Flyway must be forward-only: do not edit applied migrations; repair only when it is known-safe.
- Add a staging deploy gate for Flyway history drift:
  - DB `flyway_schema_history` count and max version must match repo expectations.
- Ensure prod mail config is correct (prod uses `SMTP_*` placeholders; `SMTP_PASSWORD` must not be left as `changeme`).
