package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer;
import com.bigbrightpaints.erp.orchestrator.workflow.WorkflowService;
import java.math.BigDecimal;
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

    private CommandDispatcher commandDispatcher;

    @BeforeEach
    void setUp() {
        commandDispatcher = new CommandDispatcher(
                workflowService,
                integrationCoordinator,
                eventPublisherService,
                traceService,
                policyEnforcer);
    }

    @Test
    void approveOrderQueuesProductionAndPublishesAwaitingProductionEvent() {
        when(workflowService.startWorkflow("order-approval")).thenReturn("trace-123");
        ApproveOrderRequest request = new ApproveOrderRequest("101", "approver@bbp.com", new BigDecimal("5000"));
        InventoryShortage shortage = new InventoryShortage("SKU-1", BigDecimal.ONE, "Red Paint");
        InventoryReservationResult reservation = new InventoryReservationResult(null, List.of(shortage));
        when(integrationCoordinator.reserveInventory("101", "COMP")).thenReturn(reservation);

        String traceId = commandDispatcher.approveOrder(request, "COMP", "user-1");

        assertThat(traceId).isEqualTo("trace-123");
        verify(policyEnforcer).checkOrderApprovalPermissions("user-1", "COMP");
        verify(integrationCoordinator).reserveInventory("101", "COMP");

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisherService).enqueue(eventCaptor.capture());
        DomainEvent published = eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo("OrderApprovedEvent");
        assertThat(published.companyId()).isEqualTo("COMP");
        assertThat(published.userId()).isEqualTo("user-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) published.payload();
        assertThat(payload)
                .containsEntry("awaitingProduction", true)
                .containsEntry("orderStatus", "PENDING_PRODUCTION")
                .containsEntry("approvedBy", "approver@bbp.com")
                .containsEntry("totalAmount", new BigDecimal("5000"));

        verify(traceService).record(
                ArgumentMatchers.eq("trace-123"),
                ArgumentMatchers.eq("ORDER_APPROVED"),
                ArgumentMatchers.eq("COMP"),
                ArgumentMatchers.<Map<String, Object>>argThat(map -> "101".equals(map.get("orderId"))));
    }
}
