#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v2}"
STRICT_RUNTIME_DB_PORT="5433"
STRICT_RUNTIME_DATASOURCE_URL="jdbc:postgresql://db:5432/erp_domain"
STRICT_RUNTIME_DATASOURCE_USERNAME="erp"
STRICT_RUNTIME_DATASOURCE_PASSWORD="erp"
STRICT_RUNTIME_JWT_SECRET="validation-jwt-secret-validation-jwt-secret"
STRICT_RUNTIME_ENCRYPTION_KEY="validation-encryption-key-32-bytes!!"
STRICT_RUNTIME_AUDIT_PRIVATE_KEY="validation-audit-private-key-not-for-release"
STRICT_RUNTIME_ALLOWED_ORIGINS="https://app.bigbrightpaints.com"
STRICT_RUNTIME_MAIL_HOST="mailhog"
STRICT_RUNTIME_MAIL_PORT="1025"
STRICT_RUNTIME_MAIL_USERNAME="mailhog-user"
STRICT_RUNTIME_MAIL_PASSWORD="mailhog-password"
STRICT_HEALTH_STATUS=""
STRICT_READINESS_STATUS=""
STRICT_AUTH_STATUS=""

strict_compose() {
  (
    cd "$ROOT_DIR"
    SPRING_DATASOURCE_URL="$STRICT_RUNTIME_DATASOURCE_URL" \
      SPRING_DATASOURCE_USERNAME="$STRICT_RUNTIME_DATASOURCE_USERNAME" \
      SPRING_DATASOURCE_PASSWORD="$STRICT_RUNTIME_DATASOURCE_PASSWORD" \
      JWT_SECRET="$STRICT_RUNTIME_JWT_SECRET" \
      ERP_SECURITY_ENCRYPTION_KEY="$STRICT_RUNTIME_ENCRYPTION_KEY" \
      ERP_SECURITY_AUDIT_PRIVATE_KEY="$STRICT_RUNTIME_AUDIT_PRIVATE_KEY" \
      SPRING_PROFILES_ACTIVE='prod,flyway-v2' \
      ERP_CORS_ALLOWED_ORIGINS="$STRICT_RUNTIME_ALLOWED_ORIGINS" \
      ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' \
      DB_PORT="$STRICT_RUNTIME_DB_PORT" \
      SPRING_MAIL_HOST="$STRICT_RUNTIME_MAIL_HOST" \
      SPRING_MAIL_PORT="$STRICT_RUNTIME_MAIL_PORT" \
      SPRING_MAIL_USERNAME="$STRICT_RUNTIME_MAIL_USERNAME" \
      SPRING_MAIL_PASSWORD="$STRICT_RUNTIME_MAIL_PASSWORD" \
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH='false' \
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE='false' \
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED='false' \
      docker compose "$@"
  )
}

probe_strict_runtime() {
  STRICT_HEALTH_STATUS="$(
    curl -s -o /tmp/release-proof-health.out -w '%{http_code}' \
      http://localhost:9090/actuator/health || true
  )"
  STRICT_READINESS_STATUS="$(
    curl -s -o /tmp/release-proof-readiness.out -w '%{http_code}' \
      http://localhost:9090/actuator/health/readiness || true
  )"
  STRICT_AUTH_STATUS="$(
    curl -s -o /tmp/release-proof-auth.out -w '%{http_code}' \
      http://localhost:8081/api/v1/auth/me || true
  )"
  [[ "$STRICT_HEALTH_STATUS" == "200" && "$STRICT_READINESS_STATUS" == "200" ]] &&
    [[ "$STRICT_AUTH_STATUS" == "200" || "$STRICT_AUTH_STATUS" == "401" || "$STRICT_AUTH_STATUS" == "403" ]]
}

wait_for_strict_runtime() {
  local attempts="${1:-90}"
  local delay_seconds="${2:-2}"
  local attempt
  for attempt in $(seq 1 "$attempts"); do
    if probe_strict_runtime; then
      return 0
    fi
    sleep "$delay_seconds"
  done
  probe_strict_runtime || true
  return 1
}

echo "[release-proof] strict compose smoke"
strict_compose up -d db rabbitmq mailhog
strict_compose up -d --build app
if wait_for_strict_runtime; then
  echo "[release-proof] strict smoke OK: health=$STRICT_HEALTH_STATUS readiness=$STRICT_READINESS_STATUS auth=$STRICT_AUTH_STATUS"
else
  echo "[release-proof] FAIL: strict compose smoke did not become healthy (health=$STRICT_HEALTH_STATUS readiness=$STRICT_READINESS_STATUS auth=$STRICT_AUTH_STATUS)" >&2
  strict_compose ps >&2 || true
  strict_compose logs --tail=200 app >&2 || true
  exit 1
fi

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
