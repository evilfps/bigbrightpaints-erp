package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.exception.OrchestratorFeatureDisabledException;
import com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandDispatcherTest {

    @Mock
    private WorkflowService workflowService;
    @Mock
    private IntegrationCoordinator integrationCoordinator;
    @Mock
    private EventPublisherService eventPublisherService;
    @Mock
    private TraceService traceService;
    @Mock
    private PolicyEnforcer policyEnforcer;
    @Mock
    private OrchestratorIdempotencyService idempotencyService;

    private CommandDispatcher commandDispatcher;
    private OrchestratorFeatureFlags featureFlags;

    @BeforeEach
    void setUp() {
        featureFlags = new OrchestratorFeatureFlags(true, true);
        commandDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer,
                idempotencyService,
                featureFlags);
    }

    @Test
    void approveOrderQueuesProductionAndPublishesAwaitingProductionEvent() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-1", "hash", "trace-123");
        ApproveOrderRequest request = new ApproveOrderRequest("101", "approver@bbp.com", new BigDecimal("5000"));
        InventoryShortage shortage = new InventoryShortage("SKU-1", BigDecimal.ONE, "Red Paint");
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of(shortage));
        when(integrationCoordinator.reserveInventory("101", "COMP")).thenReturn(reservation);
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("idem-1"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-123", command, true));

        String traceId = commandDispatcher.approveOrder(request, "idem-1", "req-1", "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-123");
        verify(policyEnforcer).checkOrderApprovalPermissions("user-1", "COMP");
        verify(integrationCoordinator).reserveInventory("101", "COMP");

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent published = eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo("OrderApprovedEvent");
        assertThat(published.companyId()).isEqualTo("COMP");
        assertThat(published.userId()).isEqualTo("user-1");
        assertThat(published.traceId()).isEqualTo("trace-123");
        assertThat(published.requestId()).isEqualTo("req-1");
        assertThat(published.idempotencyKey()).isEqualTo("idem-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) published.payload();
        assertThat(payload)
                .containsEntry("awaitingProduction", true)
                .containsEntry("orderStatus", "PENDING_PRODUCTION")
                .containsEntry("approvedBy", "approver@bbp.com")
                .containsEntry("totalAmount", new BigDecimal("5000"))
                .containsEntry("traceId", "trace-123");

        verify(traceService).record(
                ArgumentMatchers.eq("trace-123"),
                ArgumentMatchers.eq("ORDER_APPROVED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "101".equals(map.get("orderId")) && "idem-1".equals(map.get("idempotencyKey"))),
                ArgumentMatchers.eq("req-1"),
                ArgumentMatchers.eq("idem-1"));

        verify(idempotencyService).markSuccess(command);
    }

    @Test
    void dispatchBatchFailsClosedWhenFactoryDispatchDisabled() {
        CommandDispatcher disabledDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer,
                idempotencyService,
                new OrchestratorFeatureFlags(true, false));

        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-2", "hash", "trace-456");
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", new BigDecimal("100"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("idem-2"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-456", command, true));

        assertThatThrownBy(() -> disabledDispatcher.dispatchBatch(request, "idem-2", "req-2", "COMP", "user-1"))
                .isInstanceOf(OrchestratorFeatureDisabledException.class);

        verify(integrationCoordinator, never()).updateProductionStatus(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).releaseInventory(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).postDispatchJournal(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
        verify(eventPublisherService).enqueue(ArgumentMatchers.argThat(event ->
                "OrchestratorCommandDenied".equals(event.eventType())));
        verify(traceService).record(
                ArgumentMatchers.eq("trace-456"),
                ArgumentMatchers.eq("ORCH_COMMAND_DENIED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "ORCH.FACTORY.BATCH.DISPATCH".equals(map.get("commandName"))),
                ArgumentMatchers.eq("req-2"),
                ArgumentMatchers.eq("idem-2"));
        verify(idempotencyService).markFailed(ArgumentMatchers.eq(command), ArgumentMatchers.any(RuntimeException.class));
    }

    @Test
    void runPayrollFailsClosedWhenPayrollDisabled() {
        CommandDispatcher disabledDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer,
                idempotencyService,
                new OrchestratorFeatureFlags(false, true));

        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.PAYROLL.RUN", "idem-3", "hash", "trace-789");
        PayrollRunRequest request = new PayrollRunRequest(LocalDate.now(), "orch", 11L, 22L, new BigDecimal("1000"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.PAYROLL.RUN"),
                ArgumentMatchers.eq("idem-3"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-789", command, true));

        assertThatThrownBy(() -> disabledDispatcher.runPayroll(request, "idem-3", "req-3", "COMP", "user-1"))
                .isInstanceOf(OrchestratorFeatureDisabledException.class);

        verify(integrationCoordinator, never()).syncEmployees(ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).generatePayroll(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).recordPayrollPayment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(eventPublisherService).enqueue(ArgumentMatchers.argThat(event ->
                "OrchestratorCommandDenied".equals(event.eventType())));
        verify(traceService).record(
                ArgumentMatchers.eq("trace-789"),
                ArgumentMatchers.eq("ORCH_COMMAND_DENIED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "ORCH.PAYROLL.RUN".equals(map.get("commandName"))),
                ArgumentMatchers.eq("req-3"),
                ArgumentMatchers.eq("idem-3"));
        verify(idempotencyService).markFailed(ArgumentMatchers.eq(command), ArgumentMatchers.any(RuntimeException.class));
    }

    @Test
    void autoApproveOrderUsesIdempotencyAndRecordsIdentifiers() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.ORDER.AUTO_APPROVE", "auto-1", "hash", "trace-999");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.AUTO_APPROVE"),
                ArgumentMatchers.eq("auto-1"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-999", command, true));
        IntegrationCoordinator.AutoApprovalResult result = new IntegrationCoordinator.AutoApprovalResult("READY_TO_SHIP", false);
        when(integrationCoordinator.autoApproveOrder("101", new BigDecimal("5000"), "COMP")).thenReturn(result);

        String traceId = commandDispatcher.autoApproveOrder("101", new BigDecimal("5000"), "COMP", "auto-1", "req-9");

        assertThat(traceId).isEqualTo("trace-999");
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent published = eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo("OrderAutoApprovedEvent");
        assertThat(published.traceId()).isEqualTo("trace-999");
        assertThat(published.requestId()).isEqualTo("req-9");
        assertThat(published.idempotencyKey()).isEqualTo("auto-1");
        verify(traceService).record(
                ArgumentMatchers.eq("trace-999"),
                ArgumentMatchers.eq("ORDER_AUTO_APPROVED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "101".equals(map.get("orderId")) && "auto-1".equals(map.get("idempotencyKey"))),
                ArgumentMatchers.eq("req-9"),
                ArgumentMatchers.eq("auto-1"));
        verify(idempotencyService).markSuccess(command);
    }
}
