package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

final class AccountingPeriodStatusWorkflow {

  private final AccountingPeriodRepository accountingPeriodRepository;
  private final CompanyContextService companyContextService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final AccountRepository accountRepository;
  private final CompanyClock companyClock;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
  private final PeriodCloseHook periodCloseHook;
  private final AccountingPeriodSnapshotService snapshotService;
  private final AccountingPeriodLifecycleService lifecycleService;
  private final AccountingPeriodChecklistService checklistService;

  AccountingPeriodStatusWorkflow(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      AccountRepository accountRepository,
      CompanyClock companyClock,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      PeriodCloseHook periodCloseHook,
      AccountingPeriodSnapshotService snapshotService,
      AccountingPeriodLifecycleService lifecycleService,
      AccountingPeriodChecklistService checklistService) {
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.companyContextService = companyContextService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.accountRepository = accountRepository;
    this.companyClock = companyClock;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.periodCloseHook = periodCloseHook;
    this.snapshotService = snapshotService;
    this.lifecycleService = lifecycleService;
    this.checklistService = checklistService;
  }

  AccountingPeriodDto closePeriod(
      Long periodId,
      PeriodStatusChangeRequest request,
      boolean fromApprovedRequest,
      PeriodCloseRequest approvedRequest,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
    if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
      return lifecycleService.toDto(period);
    }
    if (request != null
        && request.action() != null
        && request.action() != PeriodStatusChangeRequest.PeriodStatusAction.CLOSE) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Period status action must be CLOSE");
    }
    String note =
        request != null && StringUtils.hasText(request.reason()) ? request.reason().trim() : null;
    if (!StringUtils.hasText(note)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Close reason is required");
    }
    if (fromApprovedRequest
        && (approvedRequest == null
            || approvedRequest.getStatus() != PeriodCloseRequestStatus.APPROVED
            || approvedRequest.getAccountingPeriod() == null
            || !Objects.equals(approvedRequest.getAccountingPeriod().getId(), periodId))) {
      throw ValidationUtils.invalidState(
          "Approved period close request is required before closing period");
    }
    periodCloseHook.onPeriodCloseLocked(company, period);
    boolean force = request != null && Boolean.TRUE.equals(request.force());
    assertNoUninvoicedReceipts(company, period);
    if (!force) {
      checklistService.assertChecklistComplete(company, period);
    }
    period.setChecklistNotes(note);
    BigDecimal netIncome = computeNetIncome(company, period);
    Long closingJournalId = null;
    if (netIncome.compareTo(BigDecimal.ZERO) != 0) {
      JournalEntry closingJe = postClosingJournal(company, period, netIncome, note);
      closingJournalId = closingJe.getId();
    }
    String user = resolveCurrentUsername();
    snapshotService.captureSnapshot(company, period, user);
    Instant now = CompanyTime.now(company);
    period.setStatus(AccountingPeriodStatus.CLOSED);
    period.setClosedAt(now);
    period.setClosedBy(user);
    period.setLockedAt(now);
    period.setLockedBy(user);
    period.setLockReason(note);
    period.setClosingJournalEntryId(closingJournalId);
    AccountingPeriod saved = accountingPeriodRepository.save(period);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodTransition(
          company,
          saved,
          "PERIOD_CLOSED",
          beforeStatus,
          saved.getStatus() != null ? saved.getStatus().name() : null,
          note,
          false);
    }
    lifecycleService.ensurePeriod(
        company, period.getEndDate().plusDays(1), accountingComplianceAuditService);
    return lifecycleService.toDto(saved);
  }

  AccountingPeriodDto lockPeriod(
      Long periodId,
      PeriodStatusChangeRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    if (period.getStatus() == AccountingPeriodStatus.LOCKED
        || period.getStatus() == AccountingPeriodStatus.CLOSED) {
      return lifecycleService.toDto(period);
    }
    if (request == null || request.action() != PeriodStatusChangeRequest.PeriodStatusAction.LOCK) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Period status action must be LOCK");
    }
    if (!StringUtils.hasText(request.reason())) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Lock reason is required");
    }
    String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
    period.setStatus(AccountingPeriodStatus.LOCKED);
    period.setLockedAt(CompanyTime.now(company));
    period.setLockedBy(resolveCurrentUsername());
    period.setLockReason(request.reason().trim());
    AccountingPeriod saved = accountingPeriodRepository.save(period);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodTransition(
          company,
          saved,
          "PERIOD_LOCKED",
          beforeStatus,
          saved.getStatus() != null ? saved.getStatus().name() : null,
          saved.getLockReason(),
          false);
    }
    return lifecycleService.toDto(saved);
  }

  AccountingPeriodDto reopenPeriod(
      Long periodId,
      AccountingPeriodReopenRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    requireSuperAdminRole();
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
    if (period.getStatus() == AccountingPeriodStatus.OPEN) {
      return lifecycleService.toDto(period);
    }
    if (request == null || !StringUtils.hasText(request.reason())) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Reopen reason is required");
    }
    String reason = request.reason().trim();
    Instant now = CompanyTime.now(company);
    period.setStatus(AccountingPeriodStatus.OPEN);
    period.setReopenedAt(now);
    period.setReopenedBy(resolveCurrentUsername());
    period.setReopenReason(reason);
    if (period.getClosingJournalEntryId() != null) {
      journalEntryRepository
          .findByCompanyAndId(company, period.getClosingJournalEntryId())
          .ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, reason));
      period.setClosingJournalEntryId(null);
    }
    snapshotService.deleteSnapshotForPeriod(company, period);
    AccountingPeriod saved = accountingPeriodRepository.save(period);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodTransition(
          company,
          saved,
          "PERIOD_REOPENED",
          beforeStatus,
          saved.getStatus() != null ? saved.getStatus().name() : null,
          reason,
          true);
    }
    return lifecycleService.toDto(saved);
  }

  JournalEntry createSystemJournal(
      Company company,
      AccountingPeriod period,
      String reference,
      String note,
      BigDecimal netIncome) {
    Account retained = ensureEquityAccount(company, "RETAINED_EARNINGS", "Retained Earnings");
    Account periodResult = ensureEquityAccount(company, "PERIOD_RESULT", "Period Result");
    BigDecimal amount = netIncome.abs();
    String memo = note != null ? note : "Period close " + period.getLabel();
    boolean profit = netIncome.compareTo(BigDecimal.ZERO) > 0;
    Long debitAccountId = profit ? periodResult.getId() : retained.getId();
    Long creditAccountId = profit ? retained.getId() : periodResult.getId();
    java.time.LocalDate entryDate = period.getEndDate();
    java.time.LocalDate today = companyClock.today(company);
    if (entryDate.isAfter(today)) {
      entryDate = today;
    }
    AccountingFacade accountingFacade = accountingFacadeProvider.getObject();
    JournalEntryDto posted =
        accountingFacade.createStandardJournal(
            new JournalCreationRequest(
                amount,
                debitAccountId,
                creditAccountId,
                memo,
                "ACCOUNTING_PERIOD",
                reference,
                null,
                null,
                entryDate,
                null,
                null,
                Boolean.TRUE));
    return journalEntryRepository
        .findByCompanyAndId(company, posted.id())
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.SYSTEM_INTERNAL_ERROR,
                    "Closing journal entry not found after posting"));
  }

  void reverseClosingJournalIfNeeded(JournalEntry closing, AccountingPeriod period, String reason) {
    if (closing == null) {
      return;
    }
    JournalEntryStatus status = closing.getStatus();
    if (status == JournalEntryStatus.REVERSED
        || status == JournalEntryStatus.VOIDED
        || closing.getReversalEntry() != null) {
      return;
    }
    accountingFacadeProvider
        .getObject()
        .reverseClosingEntryForPeriodReopen(closing, period, reason);
  }

  private BigDecimal computeNetIncome(Company company, AccountingPeriod period) {
    List<Object[]> aggregates =
        journalLineRepository.summarizeByAccountType(
            company, period.getStartDate(), period.getEndDate());
    BigDecimal grossRevenue = BigDecimal.ZERO;
    BigDecimal otherIncome = BigDecimal.ZERO;
    BigDecimal totalCogs = BigDecimal.ZERO;
    BigDecimal totalExpenses = BigDecimal.ZERO;
    BigDecimal otherExpenses = BigDecimal.ZERO;
    for (Object[] row : aggregates) {
      if (row == null || row.length < 3) {
        continue;
      }
      AccountType type = (AccountType) row[0];
      BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
      switch (type) {
        case REVENUE -> grossRevenue = grossRevenue.add(credit.subtract(debit));
        case OTHER_INCOME -> otherIncome = otherIncome.add(credit.subtract(debit));
        case COGS -> totalCogs = totalCogs.add(debit.subtract(credit));
        case EXPENSE -> totalExpenses = totalExpenses.add(debit.subtract(credit));
        case OTHER_EXPENSE -> otherExpenses = otherExpenses.add(debit.subtract(credit));
        default -> {}
      }
    }
    BigDecimal totalIncome = grossRevenue.add(otherIncome);
    BigDecimal totalCosts = totalCogs.add(totalExpenses).add(otherExpenses);
    return totalIncome.subtract(totalCosts).setScale(2, RoundingMode.HALF_UP);
  }

  private JournalEntry postClosingJournal(
      Company company, AccountingPeriod period, BigDecimal netIncome, String note) {
    String reference =
        "PERIOD-CLOSE-" + period.getYear() + String.format("%02d", period.getMonth());
    return journalEntryRepository
        .findByCompanyAndReferenceNumber(company, reference)
        .orElseGet(() -> createSystemJournal(company, period, reference, note, netIncome));
  }

  private Account ensureEquityAccount(Company company, String code, String name) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account acct = new Account();
              acct.setCompany(company);
              acct.setCode(code);
              acct.setName(name);
              acct.setType(AccountType.EQUITY);
              return accountRepository.save(acct);
            });
  }

  private void assertNoUninvoicedReceipts(Company company, AccountingPeriod period) {
    long uninvoicedReceipts = checklistService.countUninvoicedReceipts(company, period);
    if (uninvoicedReceipts > 0) {
      throw ValidationUtils.invalidState(
          "Un-invoiced goods receipts exist in this period (" + uninvoicedReceipts + ")");
    }
  }

  private void requireSuperAdminRole() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "SUPER_ADMIN authority required to reopen accounting periods");
    }
    boolean hasSuperAdmin =
        authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(authority.getAuthority()));
    if (!hasSuperAdmin) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "SUPER_ADMIN authority required to reopen accounting periods");
    }
  }

  private String resolveCurrentUsername() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }
}
