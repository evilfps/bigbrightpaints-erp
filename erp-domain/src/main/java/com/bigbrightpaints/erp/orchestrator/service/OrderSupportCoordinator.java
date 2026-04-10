package com.bigbrightpaints.erp.orchestrator.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;

@Service
class OrderSupportCoordinator {

  private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

  private final SalesService salesService;
  private final FactoryService factoryService;
  private final OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
  private final CompanyClock companyClock;
  private final OrchestratorFeatureFlags featureFlags;
  private final IntegrationCoordinatorSupportService supportService;
  private final TransactionTemplate txTemplate;

  OrderSupportCoordinator(
      SalesService salesService,
      FactoryService factoryService,
      OrderAutoApprovalStateRepository orderAutoApprovalStateRepository,
      CompanyClock companyClock,
      OrchestratorFeatureFlags featureFlags,
      IntegrationCoordinatorSupportService supportService,
      PlatformTransactionManager txManager) {
    this.salesService = salesService;
    this.factoryService = factoryService;
    this.orderAutoApprovalStateRepository = orderAutoApprovalStateRepository;
    this.companyClock = companyClock;
    this.featureFlags = featureFlags;
    this.supportService = supportService;
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.txTemplate = template;
  }

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
          log.info("Queued production plan for requested order");
        });
  }

  void scheduleUrgentProduction(
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

  OrderAutoApprovalState lockAutoApprovalState(String companyId, Long orderId) {
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
                        log.warn("Auto-approval state already exists; retrying fetch");
                      }
                      return orderAutoApprovalStateRepository
                          .findByCompanyCodeAndOrderId(companyId, orderId)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Unable to initialize auto-approval state"));
                    }));
  }

  Optional<Long> extractOrderIdFromPlan(ProductionPlanDto plan) {
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

  void attachOrderTrace(Long orderId, String traceId) {
    if (orderId == null) {
      return;
    }
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    if (!StringUtils.hasText(sanitizedTraceId)) {
      return;
    }
    salesService.attachTraceId(orderId, sanitizedTraceId);
  }

  Long requireNumericOrderId(String orderId, String operation) {
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

  Long parseNumericId(String id) {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException | NullPointerException ex) {
      log.warn(
          "Rejected non-numeric identifier [{}]",
          CorrelationIdentifierSanitizer.safeIdentifierForLog(id));
      return null;
    }
  }

  void requireFactoryDispatchEnabled() {
    if (featureFlags != null && !featureFlags.isFactoryDispatchEnabled()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Orchestrator factory dispatch is disabled (CODE-RED).")
          .withDetail("canonicalPath", "/api/v1/factory");
    }
  }
}
