package com.bigbrightpaints.erp.orchestrator.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;

@Service
class PayrollIntegrationCoordinator {

  private static final Logger log = LoggerFactory.getLogger(PayrollIntegrationCoordinator.class);

  private final HrService hrService;
  private final AccountingFacade accountingFacade;
  private final OrchestratorFeatureFlags featureFlags;
  private final IntegrationCoordinatorSupportService supportService;

  PayrollIntegrationCoordinator(
      HrService hrService,
      AccountingFacade accountingFacade,
      OrchestratorFeatureFlags featureFlags,
      IntegrationCoordinatorSupportService supportService) {
    this.hrService = hrService;
    this.accountingFacade = accountingFacade;
    this.featureFlags = featureFlags;
    this.supportService = supportService;
  }

  @Transactional(readOnly = true)
  void syncEmployees(String companyId, String traceId, String idempotencyKey) {
    String correlation = supportService.correlationSuffix(traceId, idempotencyKey);
    requirePayrollEnabled();
    supportService.runWithCompanyContext(
        companyId,
        () -> {
          hrService.listEmployees();
          log.info("Synced employees view for company {}{}", companyId, correlation);
        });
  }

  @Transactional
  PayrollRunDto generatePayroll(
      LocalDate payrollDate,
      BigDecimal totalAmount,
      String companyId,
      String traceId,
      String idempotencyKey) {
    requirePayrollEnabled();
    ApplicationException ex =
        new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Orchestrator payroll run is deprecated; use /api/v1/payroll/runs")
            .withDetail("canonicalPath", "/api/v1/payroll/runs");
    String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
    String sanitizedIdempotencyKey =
        CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
    if (StringUtils.hasText(sanitizedTraceId)) {
      ex.withDetail("traceId", sanitizedTraceId);
    }
    if (StringUtils.hasText(sanitizedIdempotencyKey)) {
      ex.withDetail("idempotencyKey", sanitizedIdempotencyKey);
    }
    return failDeprecatedPayroll(ex);
  }

  @Transactional
  JournalEntryDto recordPayrollPayment(
      Long payrollRunId,
      BigDecimal amount,
      Long expenseAccountId,
      Long cashAccountId,
      String companyId,
      String traceId,
      String idempotencyKey) {
    requirePayrollEnabled();
    String memo =
        supportService.correlationMemo(
            "Orchestrator payroll payment for run " + payrollRunId, traceId, idempotencyKey);
    return supportService.withCompanyContext(
        companyId,
        () ->
            accountingFacade.recordPayrollPayment(
                new PayrollPaymentRequest(
                    payrollRunId, cashAccountId, expenseAccountId, amount, null, memo)));
  }

  private void requirePayrollEnabled() {
    if (featureFlags != null && !featureFlags.isPayrollEnabled()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE, "Orchestrator payroll run is disabled (CODE-RED).")
          .withDetail("canonicalPath", "/api/v1/payroll/runs");
    }
  }

  private PayrollRunDto failDeprecatedPayroll(ApplicationException ex) {
    throw ex;
  }
}
