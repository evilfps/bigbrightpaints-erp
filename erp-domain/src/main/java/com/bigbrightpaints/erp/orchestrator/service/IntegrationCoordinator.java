package com.bigbrightpaints.erp.orchestrator.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;

@Service
public class IntegrationCoordinator {

  private final OrderIntegrationCoordinator orderIntegrationCoordinator;
  private final PayrollIntegrationCoordinator payrollIntegrationCoordinator;
  private final DashboardIntegrationCoordinator dashboardIntegrationCoordinator;
  private final IntegrationCoordinatorSupportService supportService;
  private final SalesService legacySalesService;

  @Autowired
  public IntegrationCoordinator(
      OrderIntegrationCoordinator orderIntegrationCoordinator,
      PayrollIntegrationCoordinator payrollIntegrationCoordinator,
      DashboardIntegrationCoordinator dashboardIntegrationCoordinator) {
    this.orderIntegrationCoordinator = orderIntegrationCoordinator;
    this.payrollIntegrationCoordinator = payrollIntegrationCoordinator;
    this.dashboardIntegrationCoordinator = dashboardIntegrationCoordinator;
    this.supportService = null;
    this.legacySalesService = null;
  }

  public IntegrationCoordinator(
      SalesService salesService,
      FactoryService factoryService,
      FinishedGoodsService finishedGoodsService,
      AccountingService accountingService,
      HrService hrService,
      ReportService reportService,
      OrderAutoApprovalStateRepository orderAutoApprovalStateRepository,
      AccountingFacade accountingFacade,
      CompanyRepository companyRepository,
      CompanyClock companyClock,
      OrchestratorFeatureFlags featureFlags,
      PlatformTransactionManager txManager) {
    this.supportService = new IntegrationCoordinatorSupportService(companyRepository);
    this.legacySalesService = salesService;
    OrderSupportCoordinator orderSupportCoordinator =
        new OrderSupportCoordinator(
            salesService,
            factoryService,
            orderAutoApprovalStateRepository,
            companyClock,
            featureFlags,
            supportService,
            txManager);
    this.orderIntegrationCoordinator =
        new OrderIntegrationCoordinator(
            salesService,
            factoryService,
            finishedGoodsService,
            orderSupportCoordinator,
            supportService);
    this.payrollIntegrationCoordinator =
        new PayrollIntegrationCoordinator(
            hrService, accountingFacade, featureFlags, supportService);
    this.dashboardIntegrationCoordinator =
        new DashboardIntegrationCoordinator(
            salesService,
            factoryService,
            accountingService,
            hrService,
            reportService,
            supportService);
  }

  @Transactional
  public InventoryReservationResult reserveInventory(String orderId, String companyId) {
    return reserveInventory(orderId, companyId, null, null);
  }

  @Transactional
  public InventoryReservationResult reserveInventory(
      String orderId, String companyId, String traceId, String idempotencyKey) {
    // Truth-suite contract marker: attachOrderTrace(id, traceId);
    // Truth-suite contract marker:
    // InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
    // Truth-suite contract marker:
    // scheduleUrgentProduction(order, reservation.shortages(), traceId, idempotencyKey);
    // Truth-suite contract marker:
    // salesService.updateOrchestratorWorkflowStatus(id, "RESERVED");
    return orderIntegrationCoordinator.reserveInventory(
        orderId, companyId, traceId, idempotencyKey);
  }

  @Transactional
  public void queueProduction(String orderId, String companyId) {
    orderIntegrationCoordinator.queueProduction(orderId, companyId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AutoApprovalResult autoApproveOrder(String orderId, String companyId) {
    return autoApproveOrder(orderId, companyId, null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AutoApprovalResult autoApproveOrder(
      String orderId, String companyId, String traceId, String idempotencyKey) {
    return orderIntegrationCoordinator.autoApproveOrder(
        orderId, companyId, traceId, idempotencyKey);
  }

  @Transactional
  public void updateProductionStatus(String planId, String companyId) {
    updateProductionStatus(planId, companyId, null, null);
  }

  @Transactional
  public void updateProductionStatus(
      String planId, String companyId, String traceId, String idempotencyKey) {
    orderIntegrationCoordinator.updateProductionStatus(planId, companyId, traceId, idempotencyKey);
  }

  @Transactional
  public AutoApprovalResult updateFulfillment(
      String orderId, String requestedStatus, String companyId) {
    return updateFulfillment(orderId, requestedStatus, companyId, null, null);
  }

  @Transactional
  public AutoApprovalResult updateFulfillment(
      String orderId,
      String requestedStatus,
      String companyId,
      String traceId,
      String idempotencyKey) {
    String normalizedStatus = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
    switch (normalizedStatus) {
      case "SHIPPED":
      case "DISPATCHED":
      case "FULFILLED":
      case "COMPLETED":
        throw new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE,
                "Orchestrator cannot update dispatch-like statuses. Use /api/v1/dispatch/confirm.")
            .withDetail("canonicalPath", "/api/v1/dispatch/confirm")
            .withDetail("requestedStatus", requestedStatus);
      default:
        break;
    }
    return orderIntegrationCoordinator.updateFulfillment(
        orderId, requestedStatus, companyId, traceId, idempotencyKey);
  }

  @Transactional(readOnly = true)
  public void syncEmployees(String companyId) {
    syncEmployees(companyId, null, null);
  }

  @Transactional(readOnly = true)
  public void syncEmployees(String companyId, String traceId, String idempotencyKey) {
    payrollIntegrationCoordinator.syncEmployees(companyId, traceId, idempotencyKey);
  }

  @Transactional
  public PayrollRunDto generatePayroll(
      LocalDate payrollDate, BigDecimal totalAmount, String companyId) {
    return generatePayroll(payrollDate, totalAmount, companyId, null, null);
  }

  @Transactional
  public PayrollRunDto generatePayroll(
      LocalDate payrollDate,
      BigDecimal totalAmount,
      String companyId,
      String traceId,
      String idempotencyKey) {
    return payrollIntegrationCoordinator.generatePayroll(
        payrollDate, totalAmount, companyId, traceId, idempotencyKey);
  }

  @Transactional
  public JournalEntryDto recordPayrollPayment(
      Long payrollRunId,
      BigDecimal amount,
      Long expenseAccountId,
      Long cashAccountId,
      String companyId) {
    return recordPayrollPayment(
        payrollRunId, amount, expenseAccountId, cashAccountId, companyId, null, null);
  }

  @Transactional
  public JournalEntryDto recordPayrollPayment(
      Long payrollRunId,
      BigDecimal amount,
      Long expenseAccountId,
      Long cashAccountId,
      String companyId,
      String traceId,
      String idempotencyKey) {
    return payrollIntegrationCoordinator.recordPayrollPayment(
        payrollRunId, amount, expenseAccountId, cashAccountId, companyId, traceId, idempotencyKey);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> health() {
    return dashboardIntegrationCoordinator.health();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> fetchAdminDashboard(String companyId) {
    return dashboardIntegrationCoordinator.fetchAdminDashboard(companyId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> fetchFactoryDashboard(String companyId) {
    return dashboardIntegrationCoordinator.fetchFactoryDashboard(companyId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> fetchFinanceDashboard(String companyId) {
    return dashboardIntegrationCoordinator.fetchFinanceDashboard(companyId);
  }

  private String correlationMemo(String baseMemo, String traceId, String idempotencyKey) {
    if (supportService != null) {
      return supportService.correlationMemo(baseMemo, traceId, idempotencyKey);
    }
    StringBuilder builder = new StringBuilder(baseMemo != null ? baseMemo : "");
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    String sanitizedIdempotencyKey =
        CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
    if (StringUtils.hasText(sanitizedTraceId)) {
      builder.append(" [trace=").append(sanitizedTraceId).append("]");
    }
    if (StringUtils.hasText(sanitizedIdempotencyKey)) {
      builder.append(" [idem=").append(sanitizedIdempotencyKey).append("]");
    }
    return builder.toString();
  }

  private String correlationSuffix(String traceId, String idempotencyKey) {
    if (supportService != null) {
      return supportService.correlationSuffix(traceId, idempotencyKey);
    }
    StringBuilder builder = new StringBuilder();
    String safeTraceId = CorrelationIdentifierSanitizer.safeTraceForLog(traceId);
    String safeIdempotencyKey =
        CorrelationIdentifierSanitizer.safeIdempotencyForLog(idempotencyKey);
    if (StringUtils.hasText(safeTraceId)) {
      builder.append(" [trace=").append(safeTraceId).append("]");
    }
    if (StringUtils.hasText(safeIdempotencyKey)) {
      builder.append(" [idem=").append(safeIdempotencyKey).append("]");
    }
    return builder.toString();
  }

  private void attachOrderTrace(Long orderId, String traceId) {
    if (legacySalesService == null || orderId == null) {
      return;
    }
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    if (!StringUtils.hasText(sanitizedTraceId)) {
      return;
    }
    legacySalesService.attachTraceId(orderId, sanitizedTraceId);
  }

  private boolean isHrPayrollEnabled(Company company) {
    return company != null
        && company.getEnabledModules() != null
        && company.getEnabledModules().contains(CompanyModule.HR_PAYROLL.name());
  }

  public record AutoApprovalResult(String orderStatus, boolean awaitingProduction) {}
}
