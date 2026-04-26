# R2 Checkpoint

Last reviewed: 2026-04-26

## Scope
- Feature: `default-account-clear-semantics-followup-hardcut`
- Branch: refactor/accounting-centralization-20260420 (base: origin/main)
- PR: pending
- Review candidate:
  - add explicit `clearAccountFields` semantics to the existing accounting default-account update route so validators can intentionally clear a configured default without treating omitted/null account IDs as accidental mutation intent
  - keep default-account updates company-scoped through the existing `CompanyContextService` + company-scoped account lookup owner
  - audit default-account update/clear outcomes through accounting business audit events
  - seed and verify deterministic MOCK/RIVAL inventory, COGS, revenue, discount, and tax default-account baselines for validation runtime dispatch/invoice proofs
  - preserve downstream fail-closed configuration readiness when a required default is intentionally cleared
- Why this is R2: this packet changes high-risk accounting configuration behavior in `CompanyDefaultAccountsService`, validation seeding, and the validation runtime reset script.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyDefaultAccountsService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingComplianceAuditService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/ValidationSeedDataInitializer.java`
  - `scripts/reset_final_validation_runtime.sh`
- Contract surfaces affected:
  - PUT /api/v1/accounting/default-accounts
  - GET /api/v1/accounting/default-accounts
  - GET /api/v1/accounting/configuration/health
  - validation runtime reset fixture verification for seeded default-account readiness
- Failure mode if wrong:
  - omitted/null fields could still be mistaken for explicit clears, or explicit clears could mutate unrelated default-account slots
  - runtime validation could start without required dispatch/invoice defaults, forcing manual setup drift
  - downstream dispatch/invoice accounting readiness could stay healthy after a required default is cleared instead of failing closed
  - default-account changes could lose audit visibility or cross company boundaries

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: branch-local integration candidate pending PR review
- Basis: this is a compatibility-preserving accounting configuration hardening that adds an explicit clear path and deterministic validation defaults without widening tenant, auth, accounting posting, report, export, or payment semantics.

## Escalation Decision
- Human escalation required: no
- Reason: this packet only makes existing accounting default-account mutation intent explicit, adds runtime fixture verification, and does not widen privileges, change tenant boundaries, or introduce destructive migration behavior.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the packet if default-account update/clear semantics or validation runtime readiness regress
  - after merge: revert packet and rerun focused default-account, accounting proof, runtime reset, OpenAPI, compile, and enterprise policy gates
- Rollback trigger:
  - `clearAccountFields` cannot intentionally clear a requested default-account slot, or it clears unrelated slots
  - omitted/null account ID fields start clearing defaults without explicit clear intent
  - reset validation runtime no longer starts with MOCK/RIVAL ready default-account baselines
  - configuration health stays healthy after a required default is cleared
  - default-account public contract or audit behavior drifts from the scoped packet intent
  - policy gate fails after integrating this packet

## Expiry
- Valid until: 2026-05-03
- Re-evaluate if: scope expands beyond default-account clear semantics and validation runtime seeding into broader auth, tenant isolation, payment semantics, destructive migrations, or accounting posting redesign.

## Verification Evidence
- Scope-to-evidence mapping:
  - Explicit clear semantics: `CompanyDefaultAccountsRequest.clearAccountFields` and `CompanyDefaultAccountsService` clear only requested slots, reject set+clear conflicts, and keep null/omitted IDs as no-op partial update semantics.
  - Runtime baseline: `ValidationSeedDataInitializer` creates company-scoped MOCK/RIVAL inventory, COGS, revenue, discount, and tax defaults; `scripts/reset_final_validation_runtime.sh` now verifies those slots and account types.
  - Auditability: `AccountingComplianceAuditService` records `DEFAULT_ACCOUNTS_CLEARED` / `DEFAULT_ACCOUNTS_UPDATED` business audit events with before/after default-account state.
  - Fail-closed proof: compose-backed curl cleared MOCK `taxAccountId`, observed configuration health fail closed, restored the same tax account, and observed health recover.
- Commands run:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='CompanyDefaultAccountsServiceTest,AccountControllerTest,ValidationSeedDataInitializerTest' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - `bash scripts/reset_final_validation_runtime.sh`
  - `commands.strict-runtime-smoke-check`
  - `curl` runtime probes for GET defaults, PUT clear `taxAccountId`, configuration health fail-closed, PUT restore, and audit row inspection
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='JournalEntryE2ETest,AccountingEndpointContractTest,SettlementControllerIdempotencyHeaderParityTest,CriticalAccountingAxesIT,TS_RuntimeAccountingReplayConflictExecutableCoverageTest,CR_ManualJournalSafetyTest,CR_DealerReceiptSettlementAuditTrailTest,CR_PurchasingToApAccountingTest,CR_SalesReturnCreditNoteIdempotencyTest,NumberSequenceServiceIntegrationTest,ReferenceNumberServiceTest,TS_RuntimeReferenceNumberServiceExecutableCoverageTest,InvoiceServiceTest,AccountingServiceTest#dealerReceiptService_routesLiveReceiptFlowThroughJournalEntryService+creditDebitNoteService_routesLiveCreditNoteFlowThroughJournalEntryService+inventoryAccountingService_routesLiveLandedCostFlowThroughJournalEntryService' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='CriticalAccountingAxesIT,AccountingEndpointContractTest,SettlementControllerIdempotencyHeaderParityTest,ReconciliationControlsIT' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -DskipTests compile`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q spotless:check -DspotlessFiles='src/main/java/com/bigbrightpaints/erp/modules/accounting/dto/CompanyDefaultAccountsRequest.java,src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountController.java,src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountResolutionOwnerService.java,src/main/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyDefaultAccountsService.java,src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingComplianceAuditService.java,src/main/java/com/bigbrightpaints/erp/core/config/ValidationSeedDataInitializer.java,src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountControllerTest.java,src/test/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyDefaultAccountsServiceTest.java'`
  - `ENTERPRISE_DIFF_BASE=HEAD bash ci/check-codex-review-guidelines.sh`
  - `git diff --check`
- Result summary:
  - focused default-account/controller/validation-seed tests passed
  - OpenAPI snapshot refreshed and exposes optional `clearAccountFields`
  - runtime reset verified actors, tenant fixtures, dealers, finance/UAT fixtures, and the new default-account baseline checks
  - runtime clear proof showed baseline health `healthy=true`, clear response `taxAccountId=null`, health after clear `healthy=false`, restore response `taxAccountId=7`, restored health `healthy=true`
  - audit DB inspection showed one `DEFAULT_ACCOUNTS_CLEARED` and one `DEFAULT_ACCOUNTS_UPDATED` event for `COMPANY_DEFAULT_ACCOUNTS`
  - targeted accounting proof, baseline test pack, compile, scoped spotless check, and diff whitespace check passed
- Artifact note:
  - inline evidence in this checkpoint records the Maven proof, runtime reset, curl clear/restore, audit inspection, and OpenAPI observations for the scoped default-account clear semantics packet.
