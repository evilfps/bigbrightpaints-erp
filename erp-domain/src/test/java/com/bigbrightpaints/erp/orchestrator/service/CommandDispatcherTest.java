package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
    void approveOrderMalformedOrderIdFailsBeforeOutboxAndTrace() {
        OrchestratorCommand command = new OrchestratorCommand(1L, "ORCH.ORDER.APPROVE", "idem-invalid-order", "hash", "trace-invalid-order");
        ApproveOrderRequest request = new ApproveOrderRequest("abc", "approver@bbp.com", new BigDecimal("5000"));
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.APPROVE"),
                ArgumentMatchers.eq("idem-invalid-order"),
                ArgumentMatchers.eq(request),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-invalid-order", command, true));
        when(integrationCoordinator.reserveInventory("abc", "COMP", "trace-invalid-order", "idem-invalid-order"))
                .thenThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid orderId format"));

        assertThatThrownBy(() -> commandDispatcher.approveOrder(
                request,
                "idem-invalid-order",
                "req-invalid-order",
                "COMP",
                "user-1"))
                .isInstanceOf(ApplicationException.class);

        verify(eventPublisherService, never()).enqueue(ArgumentMatchers.any());
        verify(traceService, never()).record(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any());
        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat(ex ->
                        ex instanceof ApplicationException
                                && ((ApplicationException) ex).getErrorCode() == ErrorCode.VALIDATION_INVALID_INPUT));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
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
    void updateOrderFulfillmentMalformedOrderIdFailsBeforeOutboxAndTrace() {
        OrchestratorCommand command =
                new OrchestratorCommand(1L, "ORCH.ORDER.FULFILLMENT.UPDATE", "idem-invalid-fulfillment", "hash", "trace-invalid-fulfillment");
        OrderFulfillmentRequest request = new OrderFulfillmentRequest("PROCESSING", "start");
        when(idempotencyService.start(
                ArgumentMatchers.eq("ORCH.ORDER.FULFILLMENT.UPDATE"),
                ArgumentMatchers.eq("idem-invalid-fulfillment"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenReturn(new OrchestratorIdempotencyService.CommandLease("trace-invalid-fulfillment", command, true));
        when(integrationCoordinator.updateFulfillment(
                "abc",
                "PROCESSING",
                "COMP",
                "trace-invalid-fulfillment",
                "idem-invalid-fulfillment"))
                .thenThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid orderId format"));

        assertThatThrownBy(() -> commandDispatcher.updateOrderFulfillment(
                "abc",
                request,
                "idem-invalid-fulfillment",
                "req-invalid-fulfillment",
                "COMP",
                "user-1"))
                .isInstanceOf(ApplicationException.class);

        verify(eventPublisherService, never()).enqueue(ArgumentMatchers.any());
        verify(traceService, never()).record(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyMap(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any());
        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat(ex ->
                        ex instanceof ApplicationException
                                && ((ApplicationException) ex).getErrorCode() == ErrorCode.VALIDATION_INVALID_INPUT));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
    }

    @Test
    void dispatchBatchFailsClosedToCanonicalDispatchPath() {
        DispatchRequest request = new DispatchRequest("77", "orch@bbp.com", new BigDecimal("100"));

        assertThatThrownBy(() -> commandDispatcher.dispatchBatch(request, "idem-2", "req-2", "COMP", "user-1"))
                .isInstanceOf(OrchestratorFeatureDisabledException.class)
                .hasMessageContaining("/api/v1/dispatch/confirm");

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
        verify(policyEnforcer).checkDispatchPermissions("user-1", "COMP");
        verifyNoInteractions(eventPublisherService, traceService, idempotencyService);
    }

    @Test
    void dispatchBatchNullRequestStillFailsClosedWithoutSideEffects() {
        assertThatThrownBy(() -> commandDispatcher.dispatchBatch(null, "idem-null-dispatch", "req-null-dispatch", "COMP", "user-1"))
                .isInstanceOf(OrchestratorFeatureDisabledException.class)
                .hasMessageContaining("/api/v1/dispatch/confirm");

        verify(policyEnforcer).checkDispatchPermissions("user-1", "COMP");
        verifyNoInteractions(integrationCoordinator, eventPublisherService, traceService, idempotencyService);
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
                .isInstanceOf(ApplicationException.class)
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
                        ex instanceof ApplicationException
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
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("greater than zero for payroll");

        verify(idempotencyService).markFailed(
                ArgumentMatchers.eq(command),
                ArgumentMatchers.argThat((RuntimeException ex) ->
                        ex instanceof ApplicationException
                                && ex.getMessage() != null
                                && ex.getMessage().contains("greater than zero for payroll")));
        verify(idempotencyService, never()).markSuccess(ArgumentMatchers.any());
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
