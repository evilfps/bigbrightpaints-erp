package com.bigbrightpaints.erp.orchestrator.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;

@Service
class OrderIntegrationCoordinator {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

  private final SalesService salesService;
  private final FactoryService factoryService;
  private final FinishedGoodsService finishedGoodsService;
  private final OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
  private final com.bigbrightpaints.erp.core.util.CompanyClock companyClock;
  private final OrchestratorFeatureFlags featureFlags;
  private final IntegrationCoordinatorSupportService supportService;
  private final TransactionTemplate txTemplate;

  OrderIntegrationCoordinator(
      SalesService salesService,
      FactoryService factoryService,
      FinishedGoodsService finishedGoodsService,
      OrderAutoApprovalStateRepository orderAutoApprovalStateRepository,
      com.bigbrightpaints.erp.core.util.CompanyClock companyClock,
      OrchestratorFeatureFlags featureFlags,
      IntegrationCoordinatorSupportService supportService,
      PlatformTransactionManager txManager) {
    this.salesService = salesService;
    this.factoryService = factoryService;
    this.finishedGoodsService = finishedGoodsService;
    this.orderAutoApprovalStateRepository = orderAutoApprovalStateRepository;
    this.companyClock = companyClock;
    this.featureFlags = featureFlags;
    this.supportService = supportService;
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.txTemplate = template;
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
          Long id = requireNumericOrderId(orderId, "reserveInventory");
          attachOrderTrace(id, traceId);
          SalesOrder order = salesService.getOrderWithItems(id);
          InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
          if (!reservation.shortages().isEmpty()) {
            scheduleUrgentProduction(order, reservation.shortages(), traceId, idempotencyKey);
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
    Company company = supportService.requireCompany(companyId, "queueProduction");
    supportService.runWithCompanyContext(
        company.getCode(),
        () -> {
          ProductionPlanRequest request =
              new ProductionPlanRequest(
                  "PLAN-" + orderId,
                  "Order " + orderId,
                  1.0,
                  companyClock.today(company).plusDays(1),
                  "Auto-generated from orchestrator");
          factoryService.createPlan(request);
          log.info("Queued production plan for order {}", orderId);
        });
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  IntegrationCoordinator.AutoApprovalResult autoApproveOrder(
      String orderId, BigDecimal amount, String companyId) {
    return autoApproveOrder(orderId, amount, companyId, null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  IntegrationCoordinator.AutoApprovalResult autoApproveOrder(
      String orderId, BigDecimal amount, String companyId, String traceId, String idempotencyKey) {
    String correlation = supportService.correlationSuffix(traceId, idempotencyKey);
    String normalizedCompanyId = supportService.normalizeCompanyId(companyId);
    if (normalizedCompanyId == null) {
      log.warn("Cannot auto-approve order {} without a company context{}", orderId, correlation);
      return new IntegrationCoordinator.AutoApprovalResult("PENDING_PRODUCTION", true);
    }
    Long numericId = requireNumericOrderId(orderId, "autoApproveOrder");
    AtomicReference<String> status = new AtomicReference<>("PENDING_PRODUCTION");
    AtomicBoolean awaitingProduction = new AtomicBoolean(false);
    supportService.runWithCompanyContext(
        normalizedCompanyId,
        () -> {
          attachOrderTrace(numericId, traceId);
          OrderAutoApprovalState state = lockAutoApprovalState(normalizedCompanyId, numericId);
          if (state.isCompleted()) {
            log.info(
                "Auto-approval already completed for order {} (company {}){}",
                orderId,
                normalizedCompanyId,
                correlation);
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
                "Auto-approved order {} for company {}; awaitingProduction={}{}",
                orderId,
                normalizedCompanyId,
                awaitingProduction.get(),
                correlation);
            status.set(awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
            if (!awaitingProduction.get()) {
              state.markCompleted();
            }
          } catch (RuntimeException ex) {
            state.markFailed(ex.getMessage());
            status.set("FAILED");
            log.error(
                "Auto-approval failed for order {} (company {}){}",
                orderId,
                normalizedCompanyId,
                correlation,
                ex);
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
    requireFactoryDispatchEnabled();
    supportService.runWithCompanyContext(
        companyId,
        () -> {
          Long id = parseNumericId(planId);
          if (id == null) {
            return;
          }
          ProductionPlanDto plan = factoryService.updatePlanStatus(id, "COMPLETED");
          log.info("Marked production plan {} as completed{}", planId, correlation);
          extractOrderIdFromPlan(plan)
              .ifPresent(
                  orderId -> {
                    IntegrationCoordinator.AutoApprovalResult result =
                        autoApproveOrder(
                            String.valueOf(orderId), null, companyId, traceId, idempotencyKey);
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
          Long id = requireNumericOrderId(orderId, "updateFulfillment");
          attachOrderTrace(id, traceId);
          String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
          return switch (status) {
            case "PROCESSING" -> {
              salesService.updateOrchestratorWorkflowStatus(id, "PROCESSING");
              yield new IntegrationCoordinator.AutoApprovalResult("PROCESSING", false);
            }
            case "CANCELLED" -> {
              OrderAutoApprovalState state = lockAutoApprovalState(companyId, id);
              state.markFailed("Cancelled");
              salesService.cancelOrder(id, "Cancelled");
              yield new IntegrationCoordinator.AutoApprovalResult("CANCELLED", false);
            }
            case "READY_TO_SHIP" ->
                autoApproveOrder(orderId, null, companyId, traceId, idempotencyKey);
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

  private void scheduleUrgentProduction(
      SalesOrder order, List<InventoryShortage> shortages, String traceId, String idempotencyKey) {
    if (shortages == null || shortages.isEmpty()) {
      return;
    }
    LocalDate today = companyClock.today(order.getCompany());
    for (InventoryShortage shortage : shortages) {
      factoryService.createPlan(
          new ProductionPlanRequest(
              "URG-" + order.getId() + "-" + shortage.productCode(),
              shortage.productName() + " (" + shortage.productCode() + ")",
              shortage.shortageQuantity().doubleValue(),
              today,
              supportService.correlationMemo(
                  "Urgent replenishment for order " + order.getOrderNumber(),
                  traceId,
                  idempotencyKey)));
      factoryService.createTask(
          new FactoryTaskRequest(
              "Urgent build " + shortage.productCode(),
              supportService.correlationMemo(
                  "Short by "
                      + shortage.shortageQuantity()
                      + " units for order "
                      + order.getOrderNumber(),
                  traceId,
                  idempotencyKey),
              "production",
              "URGENT",
              today.plusDays(1),
              order.getId(),
              null));
    }
  }

  private OrderAutoApprovalState lockAutoApprovalState(String companyId, Long orderId) {
    return txTemplate.execute(
        status ->
            orderAutoApprovalStateRepository
                .findByCompanyCodeAndOrderId(companyId, orderId)
                .orElseGet(
                    () -> {
                      try {
                        orderAutoApprovalStateRepository.save(
                            new OrderAutoApprovalState(companyId, orderId));
                      } catch (DataIntegrityViolationException ex) {
                        log.warn(
                            "Auto-approval state already exists for order {} in company {};"
                                + " retrying fetch",
                            orderId,
                            companyId);
                      }
                      return orderAutoApprovalStateRepository
                          .findByCompanyCodeAndOrderId(companyId, orderId)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Unable to initialize auto-approval state"));
                    }));
  }

  private Optional<Long> extractOrderIdFromPlan(ProductionPlanDto plan) {
    if (plan == null) {
      return Optional.empty();
    }
    if (plan.planNumber() != null) {
      String[] parts = plan.planNumber().split("-");
      if (parts.length >= 2) {
        Optional<Long> parsed = supportService.parseLong(parts[1]);
        if (parsed.isPresent()) {
          return parsed;
        }
      }
    }
    if (plan.notes() != null) {
      for (String token : plan.notes().split("\\D+")) {
        Optional<Long> parsed = supportService.parseLong(token);
        if (parsed.isPresent()) {
          return parsed;
        }
      }
    }
    return Optional.empty();
  }

  private void attachOrderTrace(Long orderId, String traceId) {
    if (orderId == null) {
      return;
    }
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    if (!StringUtils.hasText(sanitizedTraceId)) {
      return;
    }
    salesService.attachTraceId(orderId, sanitizedTraceId);
  }

  private Long requireNumericOrderId(String orderId, String operation) {
    Long id = parseNumericId(orderId);
    if (id != null) {
      return id;
    }
    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid orderId format")
        .withDetail("field", "orderId")
        .withDetail("operation", operation)
        .withDetail("expected", "numeric")
        .withDetail("safeIdentifier", CorrelationIdentifierSanitizer.safeIdentifierForLog(orderId));
  }

  private Long parseNumericId(String id) {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException | NullPointerException ex) {
      log.warn(
          "Rejected non-numeric identifier [{}]",
          CorrelationIdentifierSanitizer.safeIdentifierForLog(id));
      return null;
    }
  }

  private void requireFactoryDispatchEnabled() {
    if (featureFlags != null && !featureFlags.isFactoryDispatchEnabled()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Orchestrator factory dispatch is disabled (CODE-RED).")
          .withDetail("canonicalPath", "/api/v1/factory");
    }
  }
}
