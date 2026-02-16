package com.bigbrightpaints.erp.orchestrator.service;

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
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final String REQUEST_ID_HASH_PREFIX = "RIDH|";

    private final WorkflowService workflowService;
    private final IntegrationCoordinator integrationCoordinator;
    private final EventPublisherService eventPublisherService;
    private final TraceService traceService;
    private final PolicyEnforcer policyEnforcer;
    private final OrchestratorIdempotencyService idempotencyService;
    private final OrchestratorFeatureFlags featureFlags;

    public CommandDispatcher(WorkflowService workflowService, IntegrationCoordinator integrationCoordinator,
                             EventPublisherService eventPublisherService, TraceService traceService,
                             PolicyEnforcer policyEnforcer,
                             OrchestratorIdempotencyService idempotencyService,
                             OrchestratorFeatureFlags featureFlags) {
        this.workflowService = workflowService;
        this.integrationCoordinator = integrationCoordinator;
        this.eventPublisherService = eventPublisherService;
        this.traceService = traceService;
        this.policyEnforcer = policyEnforcer;
        this.idempotencyService = idempotencyService;
        this.featureFlags = featureFlags;
    }

    @Transactional
    public String approveOrder(ApproveOrderRequest request,
                               String idempotencyKey,
                               String requestId,
                               String companyId,
                               String userId) {
        policyEnforcer.checkOrderApprovalPermissions(userId, companyId);
        String normalizedRequestId = normalizeRequestId(requestId, idempotencyKey);
        OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(
                "ORCH.ORDER.APPROVE",
                idempotencyKey,
                request,
                () -> workflowService.startWorkflow("order-approval"));
        String canonicalIdempotencyKey = canonicalIdempotencyKey(lease, idempotencyKey);
        return executeWithLease(lease, () -> {
            String traceId = lease.traceId();
            InventoryReservationResult reservation = integrationCoordinator.reserveInventory(request.orderId(), companyId);
            boolean awaitingProduction = reservation != null && !reservation.shortages().isEmpty();
            String orderStatus = awaitingProduction ? "PENDING_PRODUCTION" : "READY_TO_SHIP";
            DomainEvent event = DomainEvent.of("OrderApprovedEvent", companyId, userId, "Order", request.orderId(),
                Map.of("orderStatus", orderStatus,
                        "awaitingProduction", awaitingProduction,
                        "approvedBy", request.approvedBy(),
                        "totalAmount", request.totalAmount(),
                        "traceId", traceId,
                        "idempotencyKey", canonicalIdempotencyKey),
                traceId,
                normalizedRequestId,
                canonicalIdempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "ORDER_APPROVED", companyId,
                    Map.of("orderId", request.orderId(), "idempotencyKey", canonicalIdempotencyKey),
                    normalizedRequestId, canonicalIdempotencyKey);
            return traceId;
        });
    }

    @Transactional
    public String autoApproveOrder(String orderId,
                                   BigDecimal totalAmount,
                                   String companyId,
                                   String idempotencyKey,
                                   String requestId) {
        String normalizedRequestId = normalizeRequestId(requestId, idempotencyKey);
        OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(
                "ORCH.ORDER.AUTO_APPROVE",
                idempotencyKey,
                Map.of("orderId", orderId, "totalAmount", totalAmount),
                () -> workflowService.startWorkflow("order-auto-approval"));
        String canonicalIdempotencyKey = canonicalIdempotencyKey(lease, idempotencyKey);
        return executeWithLease(lease, () -> {
            String traceId = lease.traceId();
            IntegrationCoordinator.AutoApprovalResult result =
                    integrationCoordinator.autoApproveOrder(orderId, totalAmount, companyId);
            DomainEvent event = DomainEvent.of("OrderAutoApprovedEvent", companyId, "system", "Order", orderId,
                    Map.of("orderStatus", result.orderStatus(),
                            "awaitingProduction", result.awaitingProduction(),
                            "totalAmount", totalAmount,
                            "traceId", traceId,
                            "idempotencyKey", canonicalIdempotencyKey),
                    traceId,
                    normalizedRequestId,
                    canonicalIdempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "ORDER_AUTO_APPROVED", companyId,
                    Map.of("orderId", orderId, "idempotencyKey", canonicalIdempotencyKey),
                    normalizedRequestId, canonicalIdempotencyKey);
            return traceId;
        });
    }

    @Transactional
    public String updateOrderFulfillment(String orderId,
                                         OrderFulfillmentRequest request,
                                         String idempotencyKey,
                                         String requestId,
                                         String companyId,
                                         String userId) {
        policyEnforcer.checkOrderApprovalPermissions(userId, companyId);
        String normalizedRequestId = normalizeRequestId(requestId, idempotencyKey);
        OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(
                "ORCH.ORDER.FULFILLMENT.UPDATE",
                idempotencyKey,
                Map.of("orderId", orderId, "request", request),
                () -> workflowService.startWorkflow("order-fulfillment"));
        String canonicalIdempotencyKey = canonicalIdempotencyKey(lease, idempotencyKey);
        return executeWithLease(lease, () -> {
            String traceId = lease.traceId();
            IntegrationCoordinator.AutoApprovalResult result =
                    integrationCoordinator.updateFulfillment(orderId, request.status(), companyId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", request.status());
            payload.put("awaitingProduction", result.awaitingProduction());
            payload.put("notes", request.notes());
            payload.put("traceId", traceId);
            payload.put("idempotencyKey", canonicalIdempotencyKey);
            DomainEvent event = DomainEvent.of("OrderFulfillmentUpdated", companyId, userId, "Order", orderId,
                    payload,
                    traceId,
                    normalizedRequestId,
                    canonicalIdempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "ORDER_FULFILLMENT_UPDATED", companyId, Map.of(
                    "orderId", orderId,
                    "status", request.status(),
                    "idempotencyKey", canonicalIdempotencyKey),
                    normalizedRequestId, canonicalIdempotencyKey);
            return traceId;
        });
    }

    @Transactional(noRollbackFor = OrchestratorFeatureDisabledException.class)
    public String dispatchBatch(DispatchRequest request,
                                String idempotencyKey,
                                String requestId,
                                String companyId,
                                String userId) {
        policyEnforcer.checkDispatchPermissions(userId, companyId);
        String normalizedRequestId = normalizeRequestId(requestId, idempotencyKey);
        OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(
                "ORCH.FACTORY.BATCH.DISPATCH",
                idempotencyKey,
                request,
                () -> workflowService.startWorkflow("dispatch"));
        String canonicalIdempotencyKey = canonicalIdempotencyKey(lease, idempotencyKey);
        if (!lease.shouldExecute()) {
            return lease.traceId();
        }
        if (!featureFlags.isFactoryDispatchEnabled()) {
            recordDeniedCommand(lease, "ORCH.FACTORY.BATCH.DISPATCH", companyId, userId,
                    canonicalIdempotencyKey, normalizedRequestId,
                    "/api/v1/factory", "Orchestrator factory dispatch is disabled (CODE-RED).",
                    "Batch", request != null ? request.batchId() : null);
            throw new OrchestratorFeatureDisabledException(
                    "Orchestrator factory dispatch is disabled (CODE-RED).",
                    "/api/v1/factory");
        }
        ensurePositivePostingAmount(lease.command(), request != null ? request.postingAmount() : null, "dispatch");
        return executeWithLease(lease, () -> {
            String traceId = lease.traceId();
            integrationCoordinator.updateProductionStatus(request.batchId(), companyId);
            integrationCoordinator.releaseInventory(request.batchId(), companyId);
            integrationCoordinator.postDispatchJournal(
                    request.batchId(),
                    companyId,
                    request.postingAmount(),
                    traceId,
                    canonicalIdempotencyKey);
            DomainEvent event = DomainEvent.of("ProductionBatchDispatchedEvent", companyId, userId, "Batch",
                request.batchId(), Map.of("dispatchedBy", request.requestedBy(), "traceId", traceId,
                        "idempotencyKey", canonicalIdempotencyKey),
                traceId,
                normalizedRequestId,
                canonicalIdempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "BATCH_DISPATCHED", companyId,
                    Map.of("batchId", request.batchId(), "idempotencyKey", canonicalIdempotencyKey),
                    normalizedRequestId, canonicalIdempotencyKey);
            return traceId;
        });
    }

    @Transactional(noRollbackFor = OrchestratorFeatureDisabledException.class)
    public String runPayroll(PayrollRunRequest request,
                             String idempotencyKey,
                             String requestId,
                             String companyId,
                             String userId) {
        policyEnforcer.checkPayrollPermissions(userId, companyId);
        String normalizedRequestId = normalizeRequestId(requestId, idempotencyKey);
        OrchestratorIdempotencyService.CommandLease lease = idempotencyService.start(
                "ORCH.PAYROLL.RUN",
                idempotencyKey,
                request,
                () -> workflowService.startWorkflow("payroll"));
        String canonicalIdempotencyKey = canonicalIdempotencyKey(lease, idempotencyKey);
        if (!lease.shouldExecute()) {
            return lease.traceId();
        }
        if (!featureFlags.isPayrollEnabled()) {
            recordDeniedCommand(lease, "ORCH.PAYROLL.RUN", companyId, userId,
                    canonicalIdempotencyKey, normalizedRequestId,
                    "/api/v1/payroll/runs", "Orchestrator payroll run is disabled (CODE-RED).",
                    "Payroll", request != null && request.payrollDate() != null ? request.payrollDate().toString() : null);
            throw new OrchestratorFeatureDisabledException(
                    "Orchestrator payroll run is disabled (CODE-RED).",
                    "/api/v1/payroll/runs");
        }
        ensurePositivePostingAmount(lease.command(), request != null ? request.postingAmount() : null, "payroll");
        return executeWithLease(lease, () -> {
            String traceId = lease.traceId();
            integrationCoordinator.syncEmployees(companyId);
            var payrollRun = integrationCoordinator.generatePayroll(request.payrollDate(), request.postingAmount(), companyId);
            integrationCoordinator.recordPayrollPayment(
                    payrollRun.id(),
                    request.postingAmount(),
                    request.debitAccountId(),
                    request.creditAccountId(),
                    companyId,
                    traceId,
                    canonicalIdempotencyKey);
            DomainEvent event = DomainEvent.of("PayrollCompletedEvent", companyId, userId, "Payroll",
                request.payrollDate().toString(), Map.of("initiatedBy", request.initiatedBy(), "traceId", traceId,
                        "idempotencyKey", canonicalIdempotencyKey),
                traceId,
                normalizedRequestId,
                canonicalIdempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "PAYROLL_COMPLETED", companyId,
                    Map.of("payrollDate", request.payrollDate(), "idempotencyKey", canonicalIdempotencyKey),
                    normalizedRequestId, canonicalIdempotencyKey);
            return traceId;
        });
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

    private void recordDeniedCommand(OrchestratorIdempotencyService.CommandLease lease,
                                     String commandName,
                                     String companyId,
                                     String userId,
                                     String idempotencyKey,
                                     String requestId,
                                     String canonicalPath,
                                     String message,
                                     String entity,
                                     String entityId) {
        RuntimeException denied = new RuntimeException(message);
        try {
            String traceId = lease.traceId();
            Map<String, Object> payload = new HashMap<>();
            payload.put("commandName", commandName);
            payload.put("reason", message);
            payload.put("canonicalPath", canonicalPath);
            payload.put("idempotencyKey", idempotencyKey);
            payload.put("traceId", traceId);
            DomainEvent event = DomainEvent.of("OrchestratorCommandDenied", companyId, userId,
                    entity, entityId != null ? entityId : commandName, payload,
                    traceId, requestId, idempotencyKey);
            eventPublisherService.enqueue(event);
            traceService.record(traceId, "ORCH_COMMAND_DENIED", companyId, Map.of(
                    "commandName", commandName,
                    "reason", message,
                    "canonicalPath", canonicalPath,
                    "idempotencyKey", idempotencyKey,
                    "entity", entity,
                    "entityId", entityId
            ), requestId, idempotencyKey);
        } finally {
            idempotencyService.markFailed(lease.command(), denied);
        }
    }

    private String normalizeRequestId(String requestId, String idempotencyKey) {
        String normalized = StringUtils.hasText(requestId) ? requestId.trim() : null;
        if (!StringUtils.hasText(normalized)) {
            normalized = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        }
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (normalized.length() <= MAX_REQUEST_ID_LENGTH) {
            return normalized;
        }
        return REQUEST_ID_HASH_PREFIX + DigestUtils.sha256Hex(normalized);
    }

    private String canonicalIdempotencyKey(OrchestratorIdempotencyService.CommandLease lease,
                                           String fallbackIdempotencyKey) {
        if (lease != null && lease.command() != null && StringUtils.hasText(lease.command().getIdempotencyKey())) {
            return lease.command().getIdempotencyKey();
        }
        if (!StringUtils.hasText(fallbackIdempotencyKey)) {
            return fallbackIdempotencyKey;
        }
        return fallbackIdempotencyKey.trim();
    }

    private String executeWithLease(OrchestratorIdempotencyService.CommandLease lease,
                                    CommandExecution execution) {
        if (!lease.shouldExecute()) {
            return lease.traceId();
        }
        try {
            String traceId = execution.execute();
            idempotencyService.markSuccess(lease.command());
            return traceId;
        } catch (RuntimeException ex) {
            idempotencyService.markFailed(lease.command(), ex);
            throw ex;
        }
    }

    private void ensurePositivePostingAmount(OrchestratorCommand command,
                                             BigDecimal postingAmount,
                                             String operation) {
        if (postingAmount != null && postingAmount.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        IllegalArgumentException ex =
                new IllegalArgumentException("Posting amount must be greater than zero for " + operation);
        idempotencyService.markFailed(command, ex);
        throw ex;
    }

    @FunctionalInterface
    private interface CommandExecution {
        String execute();
    }
}
