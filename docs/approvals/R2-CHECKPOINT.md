# R2 Checkpoint

Last reviewed: 2026-04-26

## Scope
- Feature: `settlement-rbac-prevalidation-followup-hardcut`
- Branch: refactor/accounting-centralization-20260420 (base: origin/main)
- PR: pending
- Review candidate:
  - require ROLE_ADMIN, ROLE_ACCOUNTING, or ROLE_SUPER_ADMIN at the HTTP authorization layer for dealer hybrid receipt writes before MVC bean validation can inspect the request body
  - require ROLE_ADMIN, ROLE_ACCOUNTING, or ROLE_SUPER_ADMIN at the HTTP authorization layer for dealer and supplier auto-settle writes before MVC bean validation can inspect the request body
  - keep authorized accounting users reaching normal request validation/business handling on the same public settlement routes
  - leave report/export/payment semantics unchanged outside the settlement receipt/auto-settle RBAC ordering corridor
- Why this is R2: this packet changes high-risk auth/RBAC enforcement in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java` for accounting settlement and receipt mutation routes.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/controller/ReportControllerRouteContractIT.java`
- Contract surfaces affected:
  - POST /api/v1/accounting/receipts/dealer/hybrid
  - POST /api/v1/accounting/dealers/{dealerId}/auto-settle
  - POST /api/v1/accounting/suppliers/{supplierId}/auto-settle
  - existing POST /api/v1/accounting/receipts/dealer, POST /api/v1/accounting/settlements/dealers, and POST /api/v1/accounting/settlements/suppliers pre-validation RBAC guarantees
- Failure mode if wrong:
  - ROLE_SALES or another insufficient role could trigger bean validation on accounting mutation bodies before authorization denies the request
  - authorized accounting/admin callers could be denied before reaching normal validation/business handling
  - settlement/receipt public-route behavior could drift from the VAL-PARTNER-027 contract

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: branch-local integration candidate pending PR review
- Basis: this is a compatibility-preserving accounting RBAC hardening that narrows unauthorized write access ordering without widening tenant, accounting, report, export, or payment semantics.

## Escalation Decision
- Human escalation required: no
- Reason: this packet only strengthens fail-closed authorization ordering for existing accounting write routes and does not widen privileges, change tenant boundaries, or introduce destructive migration behavior.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the packet if the accounting settlement/receipt RBAC ordering contract regresses
  - after merge: revert packet and rerun focused security/accounting tests plus enterprise policy gates
- Rollback trigger:
  - ROLE_SALES receives 400 bean-validation responses instead of 403 authorization denial on any protected settlement/receipt mutation route
  - ROLE_ACCOUNTING or ROLE_ADMIN can no longer reach normal 400 validation/business handling with an invalid request body
  - any report/export/payment semantics change outside the scoped RBAC ordering hard-cut
  - policy gate fails after integrating this packet

## Expiry
- Valid until: 2026-05-03
- Re-evaluate if: scope expands beyond settlement/receipt authorization ordering into broader auth, tenant isolation, payment semantics, or public contract changes.

## Verification Evidence
- Scope-to-evidence mapping:
  - HTTP authorization pre-validation guard: `SecurityConfig` now covers dealer receipt, dealer hybrid receipt, dealer settlement, dealer auto-settle, supplier settlement, and supplier auto-settle POST routes before MVC validation.
  - Regression proof: `ReportControllerRouteContractIT` asserts ROLE_SALES receives 403 on all protected settlement/receipt mutation routes with empty/invalid bodies, while ROLE_ACCOUNTING reaches 400 validation handling.
  - Runtime proof: compose-backed curl probes against the approved local runtime showed ROLE_SALES 403 and ROLE_ACCOUNTING 400 on the three follow-up routes.
- Commands run:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest=ReportControllerRouteContractIT test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -DspotlessFiles=src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java,src/test/java/com/bigbrightpaints/erp/modules/reports/controller/ReportControllerRouteContractIT.java spotless:check`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='AdminApprovalRbacIT,ReportControllerSecurityIT,ReportControllerRouteContractIT,PortalFinanceControllerIT,StatementAgingIT,DealerLedgerIT,CompanyContextFilterControlPlaneBindingTest,SuperAdminTenantWorkflowIsolationIT,TenantRuntimeEnforcementAuthIT,OpenApiSnapshotIT' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='JournalEntryE2ETest,AccountingEndpointContractTest,SettlementControllerIdempotencyHeaderParityTest,CriticalAccountingAxesIT,TS_RuntimeAccountingReplayConflictExecutableCoverageTest,CR_ManualJournalSafetyTest,CR_DealerReceiptSettlementAuditTrailTest,CR_PurchasingToApAccountingTest,CR_SalesReturnCreditNoteIdempotencyTest,NumberSequenceServiceIntegrationTest,ReferenceNumberServiceTest,TS_RuntimeReferenceNumberServiceExecutableCoverageTest,InvoiceServiceTest,AccountingServiceTest#dealerReceiptService_routesLiveReceiptFlowThroughJournalEntryService+creditDebitNoteService_routesLiveCreditNoteFlowThroughJournalEntryService+inventoryAccountingService_routesLiveLandedCostFlowThroughJournalEntryService' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -DskipTests compile`
  - `bash scripts/reset_final_validation_runtime.sh`
  - `commands.strict-runtime-smoke-check`
  - `curl` runtime probes for ROLE_SALES and ROLE_ACCOUNTING on the dealer hybrid receipt, dealer auto-settle, and supplier auto-settle routes
  - `ENTERPRISE_DIFF_BASE=HEAD bash ci/check-codex-review-guidelines.sh`
  - `git diff --check`
- Result summary:
  - focused route-contract proof passed for the follow-up endpoints and existing protected settlement/receipt routes
  - targeted security proof, targeted accounting proof, compile, and scoped spotless check passed
  - runtime reset verified validation seed fixtures and smoke checks passed on approved ports
  - curl runtime probes returned 403 for ROLE_SALES and 400 for ROLE_ACCOUNTING with invalid bodies on the hybrid receipt and dealer/supplier auto-settle routes
  - scoped policy gate passed against the current worktree packet; unscoped branch-wide policy mode still includes older branch migration changes outside this packet
- Artifact note:
  - inline evidence in this checkpoint records the route-contract, Maven proof, and runtime curl observations for the scoped RBAC ordering packet.
