package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

final class AccountingPeriodLifecycleService {

  private final AccountingPeriodRepository accountingPeriodRepository;
  private final CompanyContextService companyContextService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyClock companyClock;
  private final Supplier<AccountingComplianceAuditService> auditServiceSupplier;

  AccountingPeriodLifecycleService(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyClock companyClock,
      Supplier<AccountingComplianceAuditService> auditServiceSupplier) {
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.companyContextService = companyContextService;
    this.accountingLookupService = accountingLookupService;
    this.companyClock = companyClock;
    this.auditServiceSupplier = auditServiceSupplier != null ? auditServiceSupplier : () -> null;
  }

  List<AccountingPeriodDto> listPeriods() {
    Company company = companyContextService.requireCurrentCompany();
    ensureSurroundingPeriods(company);
    return accountingPeriodRepository.findByCompanyOrderByYearDescMonthDesc(company).stream()
        .map(this::toDto)
        .toList();
  }

  AccountingPeriodDto getPeriod(Long periodId) {
    Company company = companyContextService.requireCurrentCompany();
    return toDto(accountingLookupService.requireAccountingPeriod(company, periodId));
  }

  AccountingPeriodDto createOrUpdatePeriod(
      AccountingPeriodRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    AccountingComplianceAuditService auditService =
        resolveAuditService(accountingComplianceAuditService);
    requirePeriodRequest(request);
    int year = requirePeriodYear(request.year());
    int month = requirePeriodMonth(request.month());
    LocalDate startDate = requirePeriodDate(request.startDate(), "startDate");
    LocalDate endDate = requirePeriodDate(request.endDate(), "endDate");
    ValidationUtils.validateDateRange(startDate, endDate, "startDate", "endDate");
    Company company = companyContextService.requireCurrentCompany();
    accountingPeriodRepository
        .lockByCompanyAndYearAndMonth(company, year, month)
        .ifPresent(
            existing -> {
              throw duplicatePeriodException(company, year, month)
                  .withDetail("existingPeriodId", existing.getId());
            });
    AccountingPeriod period = new AccountingPeriod();
    period.setCompany(company);
    period.setYear(year);
    period.setMonth(month);
    period.setStartDate(startDate);
    period.setEndDate(endDate);
    period.setStatus(AccountingPeriodStatus.OPEN);
    CostingMethod beforeCostingMethod = period.getCostingMethod();
    period.setCostingMethod(resolveCostingMethodOrDefault(request.costingMethod()));
    AccountingPeriod saved = saveCreatedPeriodWithDuplicateGuard(company, period, year, month);
    if (auditService != null) {
      auditService.recordPeriodTransition(
          company,
          saved,
          "PERIOD_OPENED",
          null,
          saved.getStatus() != null ? saved.getStatus().name() : null,
          "Period opened");
    }
    if (auditService != null && beforeCostingMethod != saved.getCostingMethod()) {
      auditService.recordCostingMethodChange(
          company, saved, beforeCostingMethod, saved.getCostingMethod());
    }
    return toDto(saved);
  }

  AccountingPeriodDto updatePeriod(
      Long periodId,
      AccountingPeriodRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    AccountingComplianceAuditService auditService =
        resolveAuditService(accountingComplianceAuditService);
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period request is required");
    }
    LocalDate startDate = requirePeriodDate(request.startDate(), "startDate");
    LocalDate endDate = requirePeriodDate(request.endDate(), "endDate");
    ValidationUtils.validateDateRange(startDate, endDate, "startDate", "endDate");
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    if (period.getStatus() != AccountingPeriodStatus.OPEN) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_STATE, "Only OPEN periods can be updated")
          .withDetail("periodId", periodId)
          .withDetail("status", period.getStatus() != null ? period.getStatus().name() : null);
    }
    CostingMethod beforeCostingMethod = period.getCostingMethod();
    period.setStartDate(startDate);
    period.setEndDate(endDate);
    period.setCostingMethod(resolveCostingMethodOrDefault(request.costingMethod()));
    AccountingPeriod saved = accountingPeriodRepository.save(period);
    if (auditService != null && beforeCostingMethod != saved.getCostingMethod()) {
      auditService.recordCostingMethodChange(
          company, saved, beforeCostingMethod, saved.getCostingMethod());
    }
    return toDto(saved);
  }

  AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
    AccountingPeriod period = lockOrCreatePeriod(company, referenceDate);
    if (period.getStatus() != AccountingPeriodStatus.OPEN) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Accounting period " + period.getLabel() + " is locked/closed");
    }
    return period;
  }

  AccountingPeriod requirePostablePeriod(
      Company company,
      LocalDate referenceDate,
      String documentType,
      String documentReference,
      String reason,
      boolean overrideRequested,
      ClosedPeriodPostingExceptionService closedPeriodPostingExceptionService) {
    AccountingPeriod period = lockExistingPeriod(company, referenceDate);
    if (period.getStatus() == AccountingPeriodStatus.OPEN) {
      return period;
    }
    if (!overrideRequested) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Accounting period "
              + period.getLabel()
              + " is locked/closed; an admin one-hour posting exception is required for this"
              + " document");
    }
    if (closedPeriodPostingExceptionService == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Closed-period posting exception workflow is not configured");
    }
    closedPeriodPostingExceptionService.authorize(
        company, period, documentType, documentReference, reason);
    return period;
  }

  AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
    return ensurePeriod(company, referenceDate, resolveAuditService(null));
  }

  AccountingPeriod ensurePeriod(
      Company company,
      LocalDate referenceDate,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
    LocalDate safeDate = baseDate.withDayOfMonth(1);
    int year = safeDate.getYear();
    int month = safeDate.getMonthValue();
    return accountingPeriodRepository
        .findByCompanyAndYearAndMonth(company, year, month)
        .orElseGet(
            () -> {
              AccountingPeriod period = new AccountingPeriod();
              period.setCompany(company);
              period.setYear(year);
              period.setMonth(month);
              period.setStartDate(safeDate);
              period.setEndDate(safeDate.plusMonths(1).minusDays(1));
              period.setStatus(AccountingPeriodStatus.OPEN);
              period.setCostingMethod(CostingMethod.FIFO);
              AccountingPeriod saved = accountingPeriodRepository.save(period);
              AccountingComplianceAuditService auditService =
                  resolveAuditService(accountingComplianceAuditService);
              if (auditService != null) {
                auditService.recordPeriodTransition(
                    company,
                    saved,
                    "PERIOD_OPENED",
                    null,
                    saved.getStatus() != null ? saved.getStatus().name() : null,
                    "Period opened");
              }
              return saved;
            });
  }

  AccountingPeriod resolvePeriod(Company company, Long periodId, LocalDate referenceDate) {
    if (periodId != null) {
      return accountingLookupService.requireAccountingPeriod(company, periodId);
    }
    LocalDate effectiveDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
    return ensurePeriod(company, effectiveDate);
  }

  AccountingPeriod resolvePeriod(Company company, Long periodId) {
    if (periodId != null) {
      return accountingLookupService.requireAccountingPeriod(company, periodId);
    }
    return accountingPeriodRepository
        .findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN)
        .orElseGet(() -> ensurePeriod(company, resolveCurrentDate(company)));
  }

  LocalDate resolveCurrentDate(Company company) {
    return companyClock.today(company);
  }

  AccountingPeriodDto toDto(AccountingPeriod period) {
    return new AccountingPeriodDto(
        period.getId(),
        period.getYear(),
        period.getMonth(),
        period.getStartDate(),
        period.getEndDate(),
        period.getLabel(),
        period.getStatus().name(),
        period.isBankReconciled(),
        period.getBankReconciledAt(),
        period.getBankReconciledBy(),
        period.isInventoryCounted(),
        period.getInventoryCountedAt(),
        period.getInventoryCountedBy(),
        period.getClosedAt(),
        period.getClosedBy(),
        period.getChecklistNotes(),
        period.getLockedAt(),
        period.getLockedBy(),
        period.getLockReason(),
        period.getReopenedAt(),
        period.getReopenedBy(),
        period.getReopenReason(),
        period.getClosingJournalEntryId(),
        period.getChecklistNotes(),
        resolveCostingMethodOrDefault(period.getCostingMethod()).name());
  }

  private AccountingPeriod lockOrCreatePeriod(Company company, LocalDate referenceDate) {
    LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
    LocalDate safeDate = baseDate.withDayOfMonth(1);
    int year = safeDate.getYear();
    int month = safeDate.getMonthValue();
    return accountingPeriodRepository
        .lockByCompanyAndYearAndMonth(company, year, month)
        .orElseGet(() -> ensurePeriod(company, safeDate));
  }

  private AccountingPeriod lockExistingPeriod(Company company, LocalDate referenceDate) {
    LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
    LocalDate safeDate = baseDate.withDayOfMonth(1);
    int year = safeDate.getYear();
    int month = safeDate.getMonthValue();
    return accountingPeriodRepository
        .lockByCompanyAndYearAndMonth(company, year, month)
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Accounting period " + year + "-" + month + " not found; create it first"));
  }

  private void ensureSurroundingPeriods(Company company) {
    LocalDate today = resolveCurrentDate(company);
    ensurePeriod(company, today);
    ensurePeriod(company, today.minusMonths(1));
    ensurePeriod(company, today.plusMonths(1));
  }

  private void requirePeriodRequest(AccountingPeriodRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period request is required");
    }
  }

  private int requirePeriodYear(Integer year) {
    if (year == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period year is required");
    }
    return year;
  }

  private int requirePeriodMonth(Integer month) {
    if (month == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period month is required");
    }
    if (month < 1 || month > 12) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period month must be between 1 and 12");
    }
    return month;
  }

  private LocalDate requirePeriodDate(LocalDate date, String fieldName) {
    if (date == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period " + fieldName + " is required");
    }
    return date;
  }

  private AccountingPeriod saveCreatedPeriodWithDuplicateGuard(
      Company company, AccountingPeriod period, int year, int month) {
    try {
      return accountingPeriodRepository.save(period);
    } catch (DataIntegrityViolationException ex) {
      if (isDuplicatePeriodViolation(ex)) {
        throw duplicatePeriodException(company, year, month);
      }
      throw ex;
    }
  }

  private ApplicationException duplicatePeriodException(Company company, int year, int month) {
    String companyCode =
        company != null && company.getCode() != null ? company.getCode() : "UNKNOWN";
    return new ApplicationException(
            ErrorCode.BUSINESS_DUPLICATE_ENTRY,
            "Accounting period "
                + year
                + "-"
                + month
                + " already exists for company "
                + companyCode)
        .withDetail("field", "year,month")
        .withDetail("year", year)
        .withDetail("month", month)
        .withDetail("companyCode", companyCode);
  }

  private boolean isDuplicatePeriodViolation(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("accounting_periods_company_id_year_month_key")
            || (normalized.contains("accounting_periods")
                && normalized.contains("company_id")
                && normalized.contains("year")
                && normalized.contains("month")
                && normalized.contains("unique"))) {
          return true;
        }
      }
      cursor = cursor.getCause();
    }
    return false;
  }

  private CostingMethod resolveCostingMethodOrDefault(CostingMethod costingMethod) {
    return costingMethod != null ? costingMethod : CostingMethod.FIFO;
  }

  private AccountingComplianceAuditService resolveAuditService(
      AccountingComplianceAuditService explicitAuditService) {
    if (explicitAuditService != null) {
      return explicitAuditService;
    }
    return auditServiceSupplier.get();
  }
}
