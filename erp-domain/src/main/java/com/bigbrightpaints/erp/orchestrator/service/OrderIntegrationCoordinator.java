package com.bigbrightpaints.erp.orchestrator.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;

@Service
class OrderIntegrationCoordinator {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

  private final SalesService salesService;
  private final FactoryService factoryService;
  private final FinishedGoodsService finishedGoodsService;
  private final OrderSupportCoordinator orderSupportCoordinator;
  private final IntegrationCoordinatorSupportService supportService;

  OrderIntegrationCoordinator(
      SalesService salesService,
      FactoryService factoryService,
      FinishedGoodsService finishedGoodsService,
      OrderSupportCoordinator orderSupportCoordinator,
      IntegrationCoordinatorSupportService supportService) {
    this.salesService = salesService;
    this.factoryService = factoryService;
    this.finishedGoodsService = finishedGoodsService;
    this.orderSupportCoordinator = orderSupportCoordinator;
    this.supportService = supportService;
  }

  @Transactional
  FinishedGoodsService.InventoryReservationResult reserveInventory(
      String orderId, String companyId) {
    return reserveInventory(orderId, companyId, null, null);
  }

  @Transactional
  FinishedGoodsService.InventoryReservationResult reserveInventory(
      String orderId, String companyId, String traceId, String idempotencyKey) {
    String correlation = supportService.correlationSuffix(traceId, idempotencyKey);
    return supportService.withCompanyContext(
        companyId,
        () -> {
          Long id = orderSupportCoordinator.requireNumericOrderId(orderId, "reserveInventory");
          orderSupportCoordinator.attachOrderTrace(id, traceId);
          SalesOrder order = salesService.getOrderWithItems(id);
          InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
          if (!reservation.shortages().isEmpty()) {
            orderSupportCoordinator.scheduleUrgentProduction(
                order, reservation.shortages(), traceId, idempotencyKey);
            log.warn(
                "Order {} has {} pending shortage line(s); queued urgent production{}",
                id,
                reservation.shortages().size(),
                correlation);
          } else {
            log.info("Reserved inventory for order {}{}", id, correlation);
          }
          salesService.updateOrchestratorWorkflowStatus(id, "RESERVED");
          return reservation;
        });
  }

  @Transactional
  void queueProduction(String orderId, String companyId) {
    orderSupportCoordinator.queueProduction(orderId, companyId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  IntegrationCoordinator.AutoApprovalResult autoApproveOrder(String orderId, String companyId) {
    return autoApproveOrder(orderId, companyId, null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  IntegrationCoordinator.AutoApprovalResult autoApproveOrder(
      String orderId, String companyId, String traceId, String idempotencyKey) {
    String normalizedCompanyId = supportService.normalizeCompanyId(companyId);
    if (normalizedCompanyId == null) {
      log.warn("Cannot auto-approve order without a company context");
      return new IntegrationCoordinator.AutoApprovalResult("PENDING_PRODUCTION", true);
    }
    Long numericId = orderSupportCoordinator.requireNumericOrderId(orderId, "autoApproveOrder");
    AtomicReference<String> status = new AtomicReference<>("PENDING_PRODUCTION");
    AtomicBoolean awaitingProduction = new AtomicBoolean(false);
    supportService.runWithCompanyContext(
        normalizedCompanyId,
        () -> {
          orderSupportCoordinator.attachOrderTrace(numericId, traceId);
          var state = orderSupportCoordinator.lockAutoApprovalState(normalizedCompanyId, numericId);
          if (state.isCompleted()) {
            log.info("Auto-approval already completed for order {}", numericId);
            status.set(state.isDispatchFinalized() ? "SHIPPED" : "READY_TO_SHIP");
            return;
          }
          state.startAttempt();
          try {
            if (!state.isInventoryReserved()) {
              InventoryReservationResult reservation =
                  reserveInventory(orderId, normalizedCompanyId, traceId, idempotencyKey);
              awaitingProduction.set(reservation != null && !reservation.shortages().isEmpty());
              state.markInventoryReserved();
            }
            if (!state.isOrderStatusUpdated()) {
              salesService.updateOrchestratorWorkflowStatus(
                  numericId, awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
              state.markOrderStatusUpdated();
            }
            log.info(
                "Auto-approved order {}; awaitingProduction={}",
                numericId,
                awaitingProduction.get());
            status.set(awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
            if (!awaitingProduction.get()) {
              state.markCompleted();
            }
          } catch (RuntimeException ex) {
            state.markFailed(ex.getMessage());
            status.set("FAILED");
            log.error("Auto-approval failed for order {}", numericId, ex);
            throw ex;
          }
        });
    return new IntegrationCoordinator.AutoApprovalResult(status.get(), awaitingProduction.get());
  }

  @Transactional
  void updateProductionStatus(String planId, String companyId) {
    updateProductionStatus(planId, companyId, null, null);
  }

  @Transactional
  void updateProductionStatus(
      String planId, String companyId, String traceId, String idempotencyKey) {
    String correlation = supportService.correlationSuffix(traceId, idempotencyKey);
    orderSupportCoordinator.requireFactoryDispatchEnabled();
    supportService.runWithCompanyContext(
        companyId,
        () -> {
          Long id = orderSupportCoordinator.parseNumericId(planId);
          if (id == null) {
            return;
          }
          ProductionPlanDto plan = factoryService.updatePlanStatus(id, "COMPLETED");
          log.info("Marked production plan {} as completed{}", planId, correlation);
          orderSupportCoordinator
              .extractOrderIdFromPlan(plan)
              .ifPresent(
                  orderId -> {
                    IntegrationCoordinator.AutoApprovalResult result =
                        autoApproveOrder(String.valueOf(orderId), companyId, traceId, idempotencyKey);
                    log.info(
                        "Resumed auto-approval for order {} after plan completion; status={},"
                            + " awaitingProduction={}{}",
                        orderId,
                        result.orderStatus(),
                        result.awaitingProduction(),
                        correlation);
                  });
        });
  }

  @Transactional
  IntegrationCoordinator.AutoApprovalResult updateFulfillment(
      String orderId, String requestedStatus, String companyId) {
    return updateFulfillment(orderId, requestedStatus, companyId, null, null);
  }

  @Transactional
  IntegrationCoordinator.AutoApprovalResult updateFulfillment(
      String orderId,
      String requestedStatus,
      String companyId,
      String traceId,
      String idempotencyKey) {
    return supportService.withCompanyContext(
        companyId,
        () -> {
          Long id = orderSupportCoordinator.requireNumericOrderId(orderId, "updateFulfillment");
          orderSupportCoordinator.attachOrderTrace(id, traceId);
          String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
          return switch (status) {
            case "PROCESSING" -> {
              salesService.updateOrchestratorWorkflowStatus(id, "PROCESSING");
              yield new IntegrationCoordinator.AutoApprovalResult("PROCESSING", false);
            }
            case "CANCELLED" -> {
              var state = orderSupportCoordinator.lockAutoApprovalState(companyId, id);
              state.markFailed("Cancelled");
              salesService.cancelOrder(id, "Cancelled");
              yield new IntegrationCoordinator.AutoApprovalResult("CANCELLED", false);
            }
            case "READY_TO_SHIP" ->
                autoApproveOrder(orderId, companyId, traceId, idempotencyKey);
            case "SHIPPED", "DISPATCHED", "FULFILLED", "COMPLETED" ->
                throw new ApplicationException(
                        ErrorCode.BUSINESS_INVALID_STATE,
                        "Orchestrator cannot update dispatch-like statuses. Use"
                            + " /api/v1/dispatch/confirm.")
                    .withDetail("canonicalPath", "/api/v1/dispatch/confirm")
                    .withDetail("requestedStatus", requestedStatus);
            default ->
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Unsupported fulfillment status: " + requestedStatus);
          };
        });
  }
}
