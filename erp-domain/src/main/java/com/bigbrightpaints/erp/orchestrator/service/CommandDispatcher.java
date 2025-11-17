package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.orchestrator.dto.ApproveOrderRequest;
import com.bigbrightpaints.erp.orchestrator.dto.DispatchRequest;
import com.bigbrightpaints.erp.orchestrator.dto.OrderFulfillmentRequest;
import com.bigbrightpaints.erp.orchestrator.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.orchestrator.event.DomainEvent;
import com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer;
import com.bigbrightpaints.erp.orchestrator.workflow.WorkflowService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandDispatcher {

    private final WorkflowService workflowService;
    private final IntegrationCoordinator integrationCoordinator;
    private final EventPublisherService eventPublisherService;
    private final TraceService traceService;
    private final PolicyEnforcer policyEnforcer;

    public CommandDispatcher(WorkflowService workflowService, IntegrationCoordinator integrationCoordinator,
                             EventPublisherService eventPublisherService, TraceService traceService,
                             PolicyEnforcer policyEnforcer) {
        this.workflowService = workflowService;
        this.integrationCoordinator = integrationCoordinator;
        this.eventPublisherService = eventPublisherService;
        this.traceService = traceService;
        this.policyEnforcer = policyEnforcer;
    }

    @Transactional
    public String approveOrder(ApproveOrderRequest request, String companyId, String userId) {
        policyEnforcer.checkOrderApprovalPermissions(userId, companyId);
        String traceId = workflowService.startWorkflow("order-approval");
        InventoryReservationResult reservation = integrationCoordinator.reserveInventory(request.orderId(), companyId);
        boolean awaitingProduction = reservation != null && !reservation.shortages().isEmpty();
        if (awaitingProduction) {
            integrationCoordinator.queueProduction(request.orderId(), companyId);
        }
        String orderStatus = awaitingProduction ? "PENDING_PRODUCTION" : "READY_TO_SHIP";
        DomainEvent event = DomainEvent.of("OrderApprovedEvent", companyId, userId, "Order", request.orderId(),
            Map.of("orderStatus", orderStatus,
                    "awaitingProduction", awaitingProduction,
                    "approvedBy", request.approvedBy(),
                    "totalAmount", request.totalAmount()));
        eventPublisherService.enqueue(event);
        traceService.record(traceId, "ORDER_APPROVED", Map.of("orderId", request.orderId()));
        return traceId;
    }

    @Transactional
    public String autoApproveOrder(String orderId, BigDecimal totalAmount, String companyId) {
        String traceId = workflowService.startWorkflow("order-auto-approval");
        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.autoApproveOrder(orderId, totalAmount, companyId);
        DomainEvent event = DomainEvent.of("OrderAutoApprovedEvent", companyId, "system", "Order", orderId,
                Map.of("orderStatus", result.orderStatus(),
                        "awaitingProduction", result.awaitingProduction(),
                        "totalAmount", totalAmount));
        eventPublisherService.enqueue(event);
        traceService.record(traceId, "ORDER_AUTO_APPROVED", Map.of("orderId", orderId));
        return traceId;
    }

    @Transactional
    public String updateOrderFulfillment(String orderId, OrderFulfillmentRequest request, String companyId, String userId) {
        policyEnforcer.checkOrderApprovalPermissions(userId, companyId);
        String traceId = workflowService.startWorkflow("order-fulfillment");
        IntegrationCoordinator.AutoApprovalResult result =
                integrationCoordinator.updateFulfillment(orderId, request.status(), companyId);
        DomainEvent event = DomainEvent.of("OrderFulfillmentUpdated", companyId, userId, "Order", orderId,
                Map.of("status", request.status(),
                        "awaitingProduction", result.awaitingProduction(),
                        "notes", request.notes()));
        eventPublisherService.enqueue(event);
        traceService.record(traceId, "ORDER_FULFILLMENT_UPDATED", Map.of("orderId", orderId, "status", request.status()));
        return traceId;
    }

    @Transactional
    public String dispatchBatch(DispatchRequest request, String companyId, String userId) {
        policyEnforcer.checkDispatchPermissions(userId, companyId);
        String traceId = workflowService.startWorkflow("dispatch");
        integrationCoordinator.updateProductionStatus(request.batchId(), companyId);
        integrationCoordinator.releaseInventory(request.batchId(), companyId);
        integrationCoordinator.postDispatchJournal(
                request.batchId(),
                companyId,
                request.debitAccountId(),
                request.creditAccountId(),
                request.postingAmount());
        DomainEvent event = DomainEvent.of("ProductionBatchDispatchedEvent", companyId, userId, "Batch",
            request.batchId(), Map.of("dispatchedBy", request.requestedBy()));
        eventPublisherService.enqueue(event);
        traceService.record(traceId, "BATCH_DISPATCHED", Map.of("batchId", request.batchId()));
        return traceId;
    }

    @Transactional
    public String runPayroll(PayrollRunRequest request, String companyId, String userId) {
        policyEnforcer.checkPayrollPermissions(userId, companyId);
        if (request.postingAmount() == null || request.postingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Posting amount must be greater than zero for payroll");
        }
        String traceId = workflowService.startWorkflow("payroll");
        integrationCoordinator.syncEmployees(companyId);
        var payrollRun = integrationCoordinator.generatePayroll(request.payrollDate(), request.postingAmount(), companyId);
        integrationCoordinator.recordPayrollPayment(
                payrollRun.id(),
                request.postingAmount(),
                request.debitAccountId(),
                request.creditAccountId(),
                companyId);
        DomainEvent event = DomainEvent.of("PayrollCompletedEvent", companyId, userId, "Payroll",
            request.payrollDate().toString(), Map.of("initiatedBy", request.initiatedBy()));
        eventPublisherService.enqueue(event);
        traceService.record(traceId, "PAYROLL_COMPLETED", Map.of("payrollDate", request.payrollDate()));
        return traceId;
    }

    public Map<String, Object> integrationHealth() {
        return integrationCoordinator.health();
    }

    public Map<String, Object> eventHealth() {
        return eventPublisherService.healthSnapshot();
    }

    public Map<String, Object> traceSummary(String traceId) {
        return Map.of(
            "traceId", traceId,
            "events", traceService.getTrace(traceId)
        );
    }

    public String generateTraceId() {
        return UUID.randomUUID().toString();
    }
}
