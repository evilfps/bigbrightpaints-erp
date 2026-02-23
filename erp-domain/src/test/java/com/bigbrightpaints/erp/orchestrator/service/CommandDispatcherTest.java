package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.OrderFulfillmentRequest;
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
import static org.mockito.Mockito.doThrow;
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
        when(integrationCoordinator.reserveInventory("101", "COMP", "trace-123", "idem-1")).thenReturn(reservation);
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("idem-1"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-123", command, true));

        String traceId = commandDispatcher.approveOrder(request, "idem-1", "req-1", "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-123");
        verify(policyEnforcer).checkOrderApprovalPermissions("user-1", "COMP");
        verify(integrationCoordinator).reserveInventory("101", "COMP", "trace-123", "idem-1");

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
    void updateOrderFulfillmentPropagatesTraceAndIdempotencyToCoordinator() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.ORDER.FULFILLMENT.UPDATE", "idem-fulfillment", "hash", "trace-fulfillment");
        OrderFulfillmentRequest request = new OrderFulfillmentRequest("PROCESSING", "start");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.FULFILLMENT.UPDATE"),
                ArgumentMatchers.eq("idem-fulfillment"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-fulfillment", command, true));
        when(integrationCoordinator.updateFulfillment(
                "101",
                "PROCESSING",
                "COMP",
                "trace-fulfillment",
                "idem-fulfillment"))
                .thenReturn(new IntegrationCoordinator.AutoApprovalResult("PROCESSING", false));

        String traceId = commandDispatcher.updateOrderFulfillment(
                "101",
                request,
                "idem-fulfillment",
                "req-fulfillment",
                "COMP",
                "user-1");

        assertThat(traceId).isEqualTo("trace-fulfillment");
        verify(integrationCoordinator).updateFulfillment(
                "101",
                "PROCESSING",
                "COMP",
                "trace-fulfillment",
                "idem-fulfillment");
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
        verify(integrationCoordinator, never()).updateProductionStatus(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).releaseInventory(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).releaseInventory(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).postDispatchJournal(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
        verify(integrationCoordinator, never()).postDispatchJournal(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
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
    void dispatchBatchDisabledUsesCanonicalLeaseIdempotencyKeyInDeniedAudit() {
        CommandDispatcher disabledDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer,
                idempotencyService,
                new OrchestratorFeatureFlags(true, false));

        OrchestratorCommand command = new OrchestratorCommand(
                1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-denied", "hash", "trace-denied");
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", new BigDecimal("100"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("  idem-denied  "),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-denied", command, true));

        assertThatThrownBy(() -> disabledDispatcher.dispatchBatch(
                request,
                "  idem-denied  ",
                "req-denied",
                "COMP",
                "user-1"))
                .isInstanceOf(OrchestratorFeatureDisabledException.class);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent denied = eventCaptor.getValue();
        assertThat(denied.idempotencyKey()).isEqualTo("idem-denied");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) denied.payload();
        assertThat(payload).containsEntry("idempotencyKey", "idem-denied");

        verify(traceService).record(
                ArgumentMatchers.eq("trace-denied"),
                ArgumentMatchers.eq("ORCH_COMMAND_DENIED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "idem-denied".equals(map.get("idempotencyKey"))
                                && "ORCH.FACTORY.BATCH.DISPATCH".equals(map.get("commandName"))),
                ArgumentMatchers.eq("req-denied"),
                ArgumentMatchers.eq("idem-denied"));
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
        verify(integrationCoordinator, never()).syncEmployees(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).generatePayroll(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).generatePayroll(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).recordPayrollPayment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).recordPayrollPayment(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
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
    void dispatchBatchInvalidPostingAmountMarksFailedAndThrows() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-invalid-dispatch", "hash", "trace-invalid-dispatch");
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", BigDecimal.ZERO);
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("idem-invalid-dispatch"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-invalid-dispatch", command, true));

        assertThatThrownBy(() -> commandDispatcher.dispatchBatch(request, "idem-invalid-dispatch", "req-invalid-dispatch", "COMP", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero for dispatch");

        verify(integrationCoordinator, never()).updateProductionStatus(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).updateProductionStatus(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).releaseInventory(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).releaseInventory(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).postDispatchJournal(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat((RuntimeException ex) ->
                        ex instanceof IllegalArgumentException
                                && ex.getMessage() != null
                                && ex.getMessage().contains("greater than zero for dispatch")));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void dispatchBatchNullRequestMarksFailedAndThrows() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-null-dispatch", "hash", "trace-null-dispatch");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("idem-null-dispatch"),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-null-dispatch", command, true));

        assertThatThrownBy(() -> commandDispatcher.dispatchBatch(null, "idem-null-dispatch", "req-null-dispatch", "COMP", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero for dispatch");

        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat((RuntimeException ex) ->
                        ex instanceof IllegalArgumentException
                                && ex.getMessage() != null
                                && ex.getMessage().contains("greater than zero for dispatch")));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void runPayrollInvalidPostingAmountMarksFailedAndThrows() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.PAYROLL.RUN", "idem-invalid-payroll", "hash", "trace-invalid-payroll");
        PayrollRunRequest request = new PayrollRunRequest(LocalDate.now(), "orch", 11L, 22L, BigDecimal.ZERO);
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.PAYROLL.RUN"),
                ArgumentMatchers.eq("idem-invalid-payroll"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-invalid-payroll", command, true));

        assertThatThrownBy(() -> commandDispatcher.runPayroll(request, "idem-invalid-payroll", "req-invalid-payroll", "COMP", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero for payroll");

        verify(integrationCoordinator, never()).syncEmployees(ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).syncEmployees(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).generatePayroll(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).generatePayroll(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).recordPayrollPayment(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString());
        verify(integrationCoordinator, never()).recordPayrollPayment(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat((RuntimeException ex) ->
                        ex instanceof IllegalArgumentException
                                && ex.getMessage() != null
                                && ex.getMessage().contains("greater than zero for payroll")));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void runPayrollNullRequestMarksFailedAndThrows() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.PAYROLL.RUN", "idem-null-payroll", "hash", "trace-null-payroll");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.PAYROLL.RUN"),
                ArgumentMatchers.eq("idem-null-payroll"),
                ArgumentMatchers.isNull(),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-null-payroll", command, true));

        assertThatThrownBy(() -> commandDispatcher.runPayroll(null, "idem-null-payroll", "req-null-payroll", "COMP", "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero for payroll");

        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat((RuntimeException ex) ->
                        ex instanceof IllegalArgumentException
                                && ex.getMessage() != null
                                && ex.getMessage().contains("greater than zero for payroll")));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void dispatchBatchDisabledAuditFailureStillMarksFailed() {
        CommandDispatcher disabledDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer,
                idempotencyService,
                new OrchestratorFeatureFlags(true, false));

        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-dispatch-audit-fail", "hash", "trace-dispatch-audit-fail");
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", new BigDecimal("100"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("idem-dispatch-audit-fail"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-dispatch-audit-fail", command, true));
        doThrow(new RuntimeException("outbox down"))
                .when(eventPublisherService)
                .enqueue(ArgumentMatchers.any(DomainEvent.class));

        assertThatThrownBy(() -> disabledDispatcher.dispatchBatch(request, "idem-dispatch-audit-fail", "req-dispatch-audit-fail", "COMP", "user-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox down");

        verify(idempotencyService).markFailed(ArgumentMatchers.eq(command), ArgumentMatchers.any(RuntimeException.class));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void dispatchBatchPropagatesTraceAndIdempotencyToDispatchJournal() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-dispatch", "hash", "trace-dispatch");
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", new BigDecimal("100"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.FACTORY.BATCH.DISPATCH"),
                ArgumentMatchers.eq("idem-dispatch"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-dispatch", command, true));

        String traceId = commandDispatcher.dispatchBatch(request, "idem-dispatch", "req-dispatch", "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-dispatch");
        verify(policyEnforcer).checkDispatchPermissions("user-1", "COMP");
        verify(integrationCoordinator).updateProductionStatus("77", "COMP", "trace-dispatch", "idem-dispatch");
        verify(integrationCoordinator).releaseInventory("77", "COMP", "trace-dispatch", "idem-dispatch");
        verify(integrationCoordinator).postDispatchJournal(
                "77",
                "COMP",
                new BigDecimal("100"),
                "trace-dispatch",
                "idem-dispatch");
        verify(idempotencyService).markSuccess(command);
    }

    @Test
    void runPayrollPropagatesTraceAndIdempotencyToAccountingPosting() {
        LocalDate payrollDate = LocalDate.of(2026, 1, 31);
        BigDecimal postingAmount = new BigDecimal("1000");
        PayrollRunRequest request = new PayrollRunRequest(payrollDate, "orch", 11L, 22L, postingAmount);
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.PAYROLL.RUN", "idem-payroll", "hash", "trace-payroll");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.PAYROLL.RUN"),
                ArgumentMatchers.eq("idem-payroll"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-payroll", command, true));
        when(integrationCoordinator.generatePayroll(
                payrollDate,
                postingAmount,
                "COMP",
                "trace-payroll",
                "idem-payroll"))
                .thenReturn(new PayrollRunDto(55L, null, payrollDate, "COMPLETED", "orch", null, postingAmount, null, "idem-payroll"));

        String traceId = commandDispatcher.runPayroll(request, "idem-payroll", "req-payroll", "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-payroll");
        verify(policyEnforcer).checkPayrollPermissions("user-1", "COMP");
        verify(integrationCoordinator).syncEmployees("COMP", "trace-payroll", "idem-payroll");
        verify(integrationCoordinator).recordPayrollPayment(
                55L,
                postingAmount,
                11L,
                22L,
                "COMP",
                "trace-payroll",
                "idem-payroll");
        verify(idempotencyService).markSuccess(command);
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
        when(integrationCoordinator.autoApproveOrder("101", new BigDecimal("5000"), "COMP", "trace-999", "auto-1"))
                .thenReturn(result);

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

    @Test
    void approveOrderReplayLeaseSkipsSideEffectsAndReturnsTrace() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-replay", "hash", "trace-replay");
        ApproveOrderRequest request = new ApproveOrderRequest("301", "approver@bbp.com", new BigDecimal("1500"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("idem-replay"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-replay", command, false));

        String traceId = commandDispatcher.approveOrder(request, "idem-replay", "req-replay", "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-replay");
        verify(policyEnforcer).checkOrderApprovalPermissions("user-1", "COMP");
        verify(integrationCoordinator, never()).reserveInventory(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(eventPublisherService, never()).enqueue(ArgumentMatchers.any());
        verify(traceService, never()).record(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
        verify(idempotencyService, never()).markFailed(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void approveOrderLongRequestIdUsesDeterministicCorrelationHash() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-long", "hash", "trace-long");
        ApproveOrderRequest request = new ApproveOrderRequest("401", "approver@bbp.com", new BigDecimal("1800"));
        when(integrationCoordinator.reserveInventory("401", "COMP", "trace-long", "idem-long"))
                .thenReturn(new InventoryReservationResult(null, List.of()));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("idem-long"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-long", command, true));
        String longRequestId = "request-".repeat(40);

        String traceId = commandDispatcher.approveOrder(request, "idem-long", longRequestId, "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-long");
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent published = eventCaptor.getValue();
        assertThat(published.requestId()).startsWith("RIDH|");
        assertThat(published.requestId()).hasSize(69);
        verify(traceService).record(
                ArgumentMatchers.eq("trace-long"),
                ArgumentMatchers.eq("ORDER_APPROVED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.eq(published.requestId()),
                ArgumentMatchers.eq("idem-long"));
    }

    @Test
    void approveOrderUsesCanonicalLeaseIdempotencyKeyForEventAndTrace() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-canonical", "hash", "trace-canonical");
        ApproveOrderRequest request = new ApproveOrderRequest("201", "approver@bbp.com", new BigDecimal("1200"));
        when(integrationCoordinator.reserveInventory("201", "COMP", "trace-canonical", "idem-canonical"))
                .thenReturn(new InventoryReservationResult(null, List.of()));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("  idem-canonical  "),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-canonical", command, true));

        String traceId = commandDispatcher.approveOrder(
                request,
                "  idem-canonical  ",
                "req-canonical",
                "COMP",
                "user-1");

        assertThat(traceId).isEqualTo("trace-canonical");

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent published = eventCaptor.getValue();
        assertThat(published.idempotencyKey()).isEqualTo("idem-canonical");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) published.payload();
        assertThat(payload).containsEntry("idempotencyKey", "idem-canonical");

        verify(traceService).record(
                ArgumentMatchers.eq("trace-canonical"),
                ArgumentMatchers.eq("ORDER_APPROVED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map ->
                        "201".equals(map.get("orderId")) && "idem-canonical".equals(map.get("idempotencyKey"))),
                ArgumentMatchers.eq("req-canonical"),
                ArgumentMatchers.eq("idem-canonical"));
        verify(idempotencyService).markSuccess(command);
    }
}
