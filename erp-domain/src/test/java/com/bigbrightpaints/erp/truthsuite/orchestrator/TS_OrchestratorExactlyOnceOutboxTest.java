package com.bigbrightpaints.erp.truthsuite.orchestrator;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_OrchestratorExactlyOnceOutboxTest {

    private static final String COMMAND_DISPATCHER =
            "src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java";
    private static final String ORCH_IDEMPOTENCY_MIGRATION =
            "src/main/resources/db/migration_v2/V6__orchestrator.sql";
    private static final String ORCH_AUDIT_MIGRATION =
            "src/main/resources/db/migration_v2/V6__orchestrator.sql";

    @Test
    void commandDispatcherUsesLeaseBasedExactlyOncePattern() {
        TruthSuiteFileAssert.assertContains(
                COMMAND_DISPATCHER,
                "OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(",
                "if (!lease.shouldExecute()) {",
                "eventPublisherService.enqueue(event);",
                "traceService.record(traceId,",
                "idempotencyService.markSuccess(lease.command());",
                "idempotencyService.markFailed(lease.command(), ex);");
    }

    @Test
    void legacyBypassPathsAreFailClosedBehindFeatureFlags() {
        TruthSuiteFileAssert.assertContains(
                COMMAND_DISPATCHER,
                "return executeFeatureGuardedCommand(",
                "\"ORCH.FACTORY.BATCH.DISPATCH\",",
                "featureFlags::isFactoryDispatchEnabled,",
                "\"ORCH.PAYROLL.RUN\",",
                "featureFlags::isPayrollEnabled,",
                "recordDeniedCommand(",
                "throw new OrchestratorFeatureDisabledException(disabledMessage, canonicalPath);");
    }

    @Test
    void correlationIdentifiersPropagateAcrossSalesInventoryAndProductionLegs() {
        TruthSuiteFileAssert.assertContains(
                COMMAND_DISPATCHER,
                "integrationCoordinator.reserveInventory(",
                "integrationCoordinator.updateFulfillment(",
                "integrationCoordinator.updateProductionStatus(",
                "integrationCoordinator.releaseInventory(",
                "integrationCoordinator.syncEmployees(",
                "integrationCoordinator.generatePayroll(");
    }

    @Test
    void schemaContainsOrchestratorIdempotencyAndAuditIdentifiers() {
        TruthSuiteFileAssert.assertContains(
                ORCH_IDEMPOTENCY_MIGRATION,
                "CREATE TABLE public.orchestrator_commands",
                "CREATE UNIQUE INDEX ux_orchestrator_commands_scope");
        TruthSuiteFileAssert.assertContains(
                ORCH_AUDIT_MIGRATION,
                "trace_id character varying(128),",
                "request_id character varying(128),",
                "idempotency_key character varying(255)");
    }
}
