#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v2}"

echo "[release-proof] gate-release lane"
bash "$ROOT_DIR/scripts/gate_release.sh"

echo "[release-proof] targeted deploy hardening"
(
  cd "$ROOT_DIR/erp-domain"
  MIGRATION_SET="$MIGRATION_SET" mvn -Djacoco.skip=true -Dtest='CR_ProductionMonitoringContractTest,CR_HealthEndpointProdHardeningIT,CR_ActuatorProdHardeningIT' test
)

echo "[release-proof] targeted dispatch, accounting, and runtime boundary proof"
(
  cd "$ROOT_DIR/erp-domain"
  MIGRATION_SET="$MIGRATION_SET" mvn -Djacoco.skip=true -Dtest='DispatchControllerTest,DispatchOperationalBoundaryIT,DispatchConfirmationIT,TS_O2CDispatchCanonicalPostingTest,TS_O2COrchestratorDispatchRemovalRegressionTest,OrderFulfillmentE2ETest,JournalEntryE2ETest,AccountingControllerJournalEndpointsTest,AccountingControllerIdempotencyHeaderParityTest,CriticalAccountingAxesIT,TS_RuntimeAccountingReplayConflictExecutableCoverageTest,TS_RuntimeAccountingPayrollPostingExecutableCoverageTest,CR_ManualJournalSafetyTest,CR_DealerReceiptSettlementAuditTrailTest,CR_PayrollIdempotencyConcurrencyTest,CR_PurchasingToApAccountingTest,CR_SalesReturnCreditNoteIdempotencyTest,NumberSequenceServiceIntegrationTest,ReferenceNumberServiceTest,TS_RuntimeReferenceNumberServiceExecutableCoverageTest,OrderNumberServiceTest,InvoiceServiceTest,CompanyContextFilterControlPlaneBindingTest,AuthTenantAuthorityIT,TenantRuntimeEnforcementAuthIT,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantControlPlaneEnforcementTest,CompanyControllerIT,OpenApiSnapshotIT' test
)

echo "[release-proof] contract guards"
bash "$ROOT_DIR/scripts/guard_openapi_contract_drift.sh"
bash "$ROOT_DIR/scripts/guard_workflow_canonical_paths.sh"
bash "$ROOT_DIR/scripts/guard_dispatch_frontend_handoff_contract.sh"

echo "[release-proof] OK"
