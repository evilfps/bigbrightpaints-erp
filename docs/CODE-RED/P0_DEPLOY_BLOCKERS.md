# CODE-RED P0 Deploy Blockers (Must Fix or Prod-Gate)

Last updated: 2026-02-04

Purpose: a single, concrete list of **P0** items that block a safe enterprise deploy. For details, see:
- `docs/CODE-RED/plan-v2.md`
- `docs/CODE-RED/MODULE_AUDIT_SUMMARY.md`
- `docs/CODE-RED/DEDUPLICATION_BACKLOG.md`

## P0 - Tenant Isolation / Security
- Orchestrator must not accept a caller-controlled company override (`X-Company-Id`) that can diverge from the authenticated company context.
  - Fix: derive companyId from JWT/company context, or enforce header == JWT companyId and fail-closed (403) on mismatch.
  - Add tests for header spoofing attempts on orchestrator endpoints.
  - Status (2026-02-03): ✅ company header/claim mismatch fails closed; tests: `OrchestratorControllerIT`.
- Orchestrator health endpoints must require ops/admin authorization in non-dev environments.
  - Status (2026-02-03): ✅ admin-only in prod; tests: `CR_HealthEndpointProdHardeningIT`.
- Company context must not be header-only for unauthenticated requests:
  - Fix: do not set `CompanyContextHolder` from `X-Company-Id` unless the request is authenticated (JWT `cid`).
  - Status (2026-02-03): ✅ unauthenticated headers ignored; authenticated requests require company claim (fail closed).
- Public health surfaces must be intentional:
  - `/api/integration/health` should be secured (prefer actuator health) or proven safe as an unauthenticated surface.
  - Status (2026-02-03): ✅ endpoint secured (ROLE_ADMIN); tests: `CR_HealthEndpointProdHardeningIT`.
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
  - Status (2026-02-03): ✅ service-layer membership checks for switch/update/delete; tests: `CompanyServiceTest`.
- Identity vocabulary must be unambiguous (prevent future tenant isolation bugs):
  - `companyCode` is the tenant context string; reserve `companyId` for numeric DB ids.
  - Deprecate misleading header/claim/DTO names where `companyId` actually means `companyCode` (backward compatible parsing window).
  - Status (2026-02-03): ✅ `X-Company-Code` + `companyCode` claim introduced (legacy aliases accepted); `/auth/me` now returns `companyCode` + legacy `companyId`.

## P0 - Workflow “Truth” (Status Must Not Bypass Financial/Inventory Truth)
- Orchestrator must not be able to mark orders `SHIPPED/DISPATCHED` without the canonical dispatch chain:
  slip → invoice → journals → ledger updates (`SalesService.confirmDispatch`).
- Status-only fulfillment requests for `SHIPPED/DISPATCHED` are fail-closed unless dispatch truth exists (then read-only).
  - Status (2026-02-04): ✅ dispatch-truth gate + read-only terminal responses; tests:
    `OrchestratorControllerIT.fulfillment_rejects_shipped_status_updates_without_dispatch_confirmation`,
    `IntegrationCoordinatorTest.updateFulfillmentDispatchAcknowledgesWhenDispatchConfirmed`.
- Orchestrator “lightweight dispatch” endpoints (`/api/v1/orchestrator/dispatch*`) are hard deprecated (always 410 + canonicalPath).
- Sales COGS posting must be single-truth:
  - COGS/Inventory relief must only be posted by dispatch-confirm (per-slip reference), not by any alternate “order fulfillment” helper.
  - Status (2026-02-03): ✅ slip-scoped COGS enforced + order-level posting disabled; tests: `OrderFulfillmentE2ETest.dispatchConfirm_idempotent_andRestoresArtifacts`, `OrderFulfillmentE2ETest.partialDispatch_invoicesShippedQty_andCreatesBackorderSlip`, `SalesFulfillmentServiceTest.forcesOrderLevelCogsPostingDisabled`.
- Sales AR/Revenue posting must be single-truth:
  - Sales journals must dedupe across the canonical dispatch reference (`INV-<orderNumber>` for single-slip, `INV-<orderNumber>-<slipNumber>` for multi-slip) and any custom invoice-number reference.
  - Dispatch confirm must not create a second AR/Revenue journal if the canonical dispatch reference already exists (even if not linked on the order).
  - Status (2026-02-03): ✅ canonical sales journal reference enforced with invoice-number alias mapping + mismatch-safe idempotency; tests: `CriticalAccountingAxesIT.salesJournalIdempotentAcrossReferenceVariants`, `CriticalAccountingAxesIT.salesJournalIdempotencyRejectsMismatchedAliasPayload`, `CriticalAccountingAxesIT.salesJournalConcurrentDedupesAcrossReferences`.
- Orchestrator “non-final” status writes must still be guarded:
  - `PROCESSING/CANCELLED/READY_TO_SHIP` updates must respect the sales state machine and must not be free-form status setters.
  - Status (2026-02-04): ✅ routes through SalesService guards; tests:
    `IntegrationCoordinatorTest.updateFulfillmentProcessingAllowedWhenNoDispatchTruth`.
- Mutating/nondeterministic “finder” endpoints must not exist (read-only GET must have no side effects and must fail-closed on ambiguity).
  - Status (2026-02-03): ✅ `/api/v1/dispatch/order/{orderId}` is now read-only + fails closed on ambiguity; tests: `CR_DispatchOrderLookupReadOnlyIT` (other finder endpoints still under review).
- Packaging slip status updates must enforce a real state machine (no free-form statuses that skip reservation/inventory unwind).

## P0 - Idempotency / Deterministic References
- Orchestrator write commands must be idempotent at the boundary.
  - `Idempotency-Key` is required and reserved in `orchestrator_commands` (scope: company + command + key).
- Orchestrator feature flags must be enforced in the service layer too (defense-in-depth):
  - If `orchestrator.factory-dispatch.enabled=false` or `orchestrator.payroll.enabled=false`, service invocation must fail closed even if the controller is bypassed.
  - Status (2026-02-04): ✅ service-layer gating added; tests: `CommandDispatcherTest.dispatchBatchFailsClosedWhenFactoryDispatchDisabled`, `CommandDispatcherTest.runPayrollFailsClosedWhenPayrollDisabled`, `IntegrationCoordinatorTest.updateProductionStatusFailsClosedWhenFactoryDispatchDisabled`, `IntegrationCoordinatorTest.generatePayrollFailsClosedWhenPayrollDisabled`, `IntegrationCoordinatorTest.recordPayrollPaymentFailsClosedWhenPayrollDisabled`.
- Manufacturing/packing endpoints must be retry-safe at the API boundary (double-click/network/orchestrator retries must not double-consume or double-post).
  - Bulk pack reference must be deterministic (no `System.currentTimeMillis()`); retries must not double-consume packaging.
  - Packing record retries must not double-consume packaging or double-post journals.
  - Opening stock import must have an import idempotency key; retry must not create new batches/movements/journals.
    - Status (2026-02-04): ✅ opening stock import idempotent + prod gated; tests: `CR_OpeningStockImportIdempotencyIT`, `CR_OpeningStockImportProdGatingIT`.
- Manual raw material intake must be disabled by default and require idempotency key when enabled (fail closed with canonical path).
  - Status (2026-02-04): ✅ feature-flag gating + idempotency record; tests: `CR_RawMaterialIntakeIdempotencyIT`, `ProcureToPayE2ETest.rawMaterialIntakeDisabledByDefault`.
- Inventory adjustments must be retry-safe and use the adjustment date for posting:
  - `Idempotency-Key` required; mismatch-safe on replay; journal entry date must equal `adjustmentDate`.
  - Status (2026-02-04): ✅ idempotency + date enforcement; tests: `CR_INV_AdjustmentIdempotencyTest`.
- Goods receipts (GRN) must be idempotent and period-locked by receiptDate (no GRN in CLOSED/LOCKED periods).
  - Status (2026-02-04): ✅ `Idempotency-Key` required + mismatch-safe; closed/locked period rejected; tests:
    `CR_PurchasingToApAccountingTest.grnIdempotency_replayReturnsSameReceipt_andMovementsNotDuplicated`,
    `CR_PurchasingToApAccountingTest.grnIdempotency_mismatchReturnsConflict`,
    `CR_PurchasingToApAccountingTest.grnClosedPeriodRejected`.
- Inventory→GL automation must be OFF in prod unless it is outbox-backed (no silent drift).
  - Status (2026-02-04): ✅ disabled in prod config; test: `CR_InventoryGlAutomationProdOffIT`.
- Dealer receipts/settlements must be idempotent (caller idempotency key enforced; allocations deterministic).
  - Status (2026-02-04): ✅ dealer receipt idempotency reserve-first + mismatch-safe; tests: `CR_DealerReceiptSettlementAuditTrailTest`.
  - Status (2026-02-04): ✅ dealer settlement idempotency reserve-first + allocation uniqueness; tests: `CR_DealerReceiptSettlementAuditTrailTest`, `AccountingServiceTest`.
- Supplier payments/settlements must be idempotent (caller idempotency key enforced; allocations deterministic).
  - Status (2026-02-04): ✅ supplier payment/settlement reserve-first + mismatch-safe; tests:
    `CR_PurchasingToApAccountingTest.supplierPayment_idempotencyConcurrent_singleJournalAndAllocations`,
    `CR_PurchasingToApAccountingTest.supplierSettlement_idempotencyReplay_doesNotDoubleReduceOutstanding`,
    `CR_PurchasingToApAccountingTest.supplierSettlement_idempotencyMismatch_conflicts`.
- Sales returns/credit notes must be retry-safe and mismatch-safe (no duplicate credit journals or return movements).
  - Status (2026-02-04): ✅ sales return invoice lock + credit note reserve-first idempotency; tests: `CR_SalesReturnCreditNoteIdempotencyTest`, `SalesReturnServiceTest`, `CreditDebitNoteIT`.
- Payroll run creation must be single-truth per period:
  - One run per (company, runType, periodStart, periodEnd); deterministic idempotency + mismatch-safe replay.
  - Legacy HR payroll run endpoint is deprecated (410 + canonicalPath) to prevent missing period fields.
  - Status (2026-02-04): ✅ unique identity enforced + legacy endpoint gated; tests: `CR_PayrollIdempotencyConcurrencyTest`, `CR_PayrollLegacyEndpointGatedIT`.
- Add DB uniqueness where needed to prevent duplicate reservations/batches under concurrency.
  - Packaging slips must not duplicate per order under concurrency (guard at DB and service layer).
- Idempotency must be mismatch-safe:
  - If a replay hits an existing reference but payload differs materially (amount/accounts), fail closed with a conflict (no silent reuse).
- Status (2026-02-03): ✅ orchestrator boundary enforces mismatch-safe idempotency (409 on replay); tests:
  `OrchestratorControllerIT.approve_order_rejects_idempotency_mismatch`.
- Observability must be enterprise-grade for every privileged write:
  - Standardize `requestId/traceId/correlationId/idempotencyKey/referenceNumber` so we can audit “who did what” end-to-end across modules.
  - Outbox/audit must be queryable by these identifiers without parsing payload JSON.
- Status (2026-02-03): ✅ orchestrator audit/outbox store `requestId/traceId/idempotencyKey` columns; tests:
  `OrchestratorControllerIT.approve_order_is_idempotent_and_audited`.

## P0 - Accounting Correctness (Enterprise Grade)
- Payroll posting must be canonical and idempotent (expense + payable only; mismatch-safe reference).
  - Status (2026-02-04): ✅ AccountingFacade posting enforced; tests:
    `CR_PayrollIdempotencyConcurrencyTest.payrollPosting_isIdempotent_andCreatesExpensePayableLines`,
    `CR_PayrollIdempotencyConcurrencyTest.payrollPosting_mismatchConflictOnReplay`.
- Payroll payments must clear Salary Payable (no double-expensing).
  - `POST /api/v1/accounting/payroll/payments` now requires POSTED runs, posts **Dr SALARY-PAYABLE / Cr CASH**, and stores `payroll_runs.payment_journal_entry_id`.
  - HR `POST /api/v1/payroll/runs/{id}/mark-paid` is blocked unless a payment journal exists (prevents “PAID with no payment evidence”).
- Period close must treat posted-ish purchases as posted (POSTED|PARTIAL|PAID) while still requiring journal linkage.
  - Status (2026-02-04): ✅ checklist counts fixed for purchases; tests:
    `CR_PurchasingToApAccountingTest.periodChecklist_treatsPartialAndPaidPurchasesAsPosted`,
    `CR_PurchasingToApAccountingTest.periodChecklist_flagsPostedishPurchaseMissingJournalLink`.
- Closed-period reporting must use **period-end snapshots** as the source of truth.
  - Fix: persist period-end snapshots at close, and make report paths read snapshots for CLOSED periods.
- Fix FIFO valuation to use remaining/available quantities (not total quantities) so depleted batches don’t inflate valuation.
- Period-close postings must not bypass accounting posting boundaries (no direct account balance mutation outside `AccountingFacade`/`AccountingService` invariants).

## P0 - Deploy Gates / Flyway Discipline
- Flyway must be forward-only: do not edit applied migrations; repair only when it is known-safe.
- Add a staging deploy gate for Flyway history drift:
  - DB `flyway_schema_history` count and max version must match repo expectations.
- Ensure prod mail config is correct (prod uses `SMTP_*` placeholders; `SMTP_PASSWORD` must not be left as `changeme`).
