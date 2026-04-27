package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;

final class AccountingPeriodMakerCheckerLifecycleOwner {

  private final AccountingPeriodLifecycleService lifecycleService;
  private final AccountingPeriodCloseWorkflow closeWorkflow;
  private final Supplier<ClosedPeriodPostingExceptionService>
      closedPeriodPostingExceptionServiceSupplier;

  AccountingPeriodMakerCheckerLifecycleOwner(
      AccountingPeriodLifecycleService lifecycleService,
      AccountingPeriodCloseWorkflow closeWorkflow,
      Supplier<ClosedPeriodPostingExceptionService> closedPeriodPostingExceptionServiceSupplier) {
    this.lifecycleService = lifecycleService;
    this.closeWorkflow = closeWorkflow;
    this.closedPeriodPostingExceptionServiceSupplier =
        closedPeriodPostingExceptionServiceSupplier != null
            ? closedPeriodPostingExceptionServiceSupplier
            : () -> null;
  }

  List<AccountingPeriodDto> listPeriods() {
    return lifecycleService.listPeriods();
  }

  AccountingPeriodDto getPeriod(Long periodId) {
    return lifecycleService.getPeriod(periodId);
  }

  AccountingPeriodDto createOrUpdatePeriod(
      AccountingPeriodRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return lifecycleService.createOrUpdatePeriod(request, accountingComplianceAuditService);
  }

  AccountingPeriodDto updatePeriod(
      Long periodId,
      AccountingPeriodRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return lifecycleService.updatePeriod(periodId, request, accountingComplianceAuditService);
  }

  PeriodCloseRequestDto requestPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return closeWorkflow.requestPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto approvePeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return closeWorkflow.approvePeriodClose(periodId, request, accountingComplianceAuditService);
  }

  PeriodCloseRequestDto rejectPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return closeWorkflow.rejectPeriodClose(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto closePeriod(Long periodId, PeriodStatusChangeRequest request) {
    throw ValidationUtils.invalidState(
        "Direct close is disabled; submit /request-close and approve via maker-checker workflow");
  }

  AccountingPeriodDto lockPeriod(
      Long periodId,
      PeriodStatusChangeRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return closeWorkflow.lockPeriod(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriodDto reopenPeriod(
      Long periodId,
      AccountingPeriodReopenRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    return closeWorkflow.reopenPeriod(periodId, request, accountingComplianceAuditService);
  }

  AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
    return lifecycleService.requireOpenPeriod(company, referenceDate);
  }

  AccountingPeriod requirePostablePeriod(
      Company company,
      LocalDate referenceDate,
      String documentType,
      String documentReference,
      String reason,
      boolean overrideRequested) {
    return lifecycleService.requirePostablePeriod(
        company,
        referenceDate,
        documentType,
        documentReference,
        reason,
        overrideRequested,
        closedPeriodPostingExceptionServiceSupplier.get());
  }

  AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
    return lifecycleService.ensurePeriod(company, referenceDate);
  }

  AccountingPeriodDto toDto(AccountingPeriod period) {
    return lifecycleService.toDto(period);
  }
}
