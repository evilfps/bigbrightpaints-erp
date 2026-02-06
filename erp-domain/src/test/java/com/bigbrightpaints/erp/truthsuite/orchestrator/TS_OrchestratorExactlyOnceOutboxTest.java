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
            "src/main/resources/db/migration/V118__orchestrator_command_idempotency.sql";
    private static final String ORCH_AUDIT_MIGRATION =
            "src/main/resources/db/migration/V121__orchestrator_audit_outbox_identifiers.sql";

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
                "if (!featureFlags.isFactoryDispatchEnabled()) {",
                "recordDeniedCommand(lease, \"ORCH.FACTORY.BATCH.DISPATCH\"",
                "if (!featureFlags.isPayrollEnabled()) {",
                "recordDeniedCommand(lease, \"ORCH.PAYROLL.RUN\"",
                "\"/api/v1/payroll/runs\", \"Orchestrator payroll run is disabled (CODE-RED).\"");
    }

    @Test
    void schemaContainsOrchestratorIdempotencyAndAuditIdentifiers() {
        TruthSuiteFileAssert.assertContains(
                ORCH_IDEMPOTENCY_MIGRATION,
                "CREATE TABLE IF NOT EXISTS orchestrator_commands",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_orchestrator_commands_scope");
        TruthSuiteFileAssert.assertContains(
                ORCH_AUDIT_MIGRATION,
                "ALTER TABLE orchestrator_outbox",
                "ADD COLUMN IF NOT EXISTS trace_id",
                "ADD COLUMN IF NOT EXISTS request_id",
                "ADD COLUMN IF NOT EXISTS idempotency_key");
    }
}
