package com.bigbrightpaints.erp.truthsuite.orchestrator;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_OrchestratorIdempotencyCoverageTest {

    private static final String COMMAND_DISPATCHER =
            "src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java";
    private static final String INTEGRATION_COORDINATOR =
            "src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java";
    private static final String FINISHED_GOODS_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java";

    @Test
    void commandDispatcher_uses_lease_lifecycle_for_idempotent_execution() {
        TruthSuiteFileAssert.assertContainsInOrder(
                COMMAND_DISPATCHER,
                "OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(",
                "return new LeaseEnvelope(lease, normalizedRequestId, canonicalIdempotencyKey);",
                "if (!lease.shouldExecute()) {",
                "idempotencyService.markSuccess(lease.command());",
                "idempotencyService.markFailed(lease.command(), ex);");
    }

    @Test
    void integrationCoordinator_propagates_trace_and_idempotency_into_inventory_leg() {
        TruthSuiteFileAssert.assertContains(
                INTEGRATION_COORDINATOR,
                "attachOrderTrace(id, traceId);",
                "InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);",
                "scheduleUrgentProduction(order, reservation.shortages(), traceId, idempotencyKey);",
                "salesService.updateOrchestratorWorkflowStatus(id, \"RESERVED\");");
    }

    @Test
    void finishedGoods_reservation_replay_short_circuits_duplicate_allocation_paths() {
        TruthSuiteFileAssert.assertContains(
                FINISHED_GOODS_SERVICE,
                "if (!slip.getLines().isEmpty()) {",
                "if (slipLinesMatchOrder(slip, managedOrder)) {",
                "return new InventoryReservationResult(toSlipDto(slip), List.of());",
                "releaseReservationsForOrder(order.getId());");
    }
}
