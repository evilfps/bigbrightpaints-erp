package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;

@Service
class PayrollAccountingService {

  private final AccountingCoreSupport accountingCoreSupport;
  private final PayrollRunRepository payrollRunRepository;
  private final CompanyContextService companyContextService;
  private final com.bigbrightpaints.erp.core.util.CompanyClock companyClock;
  private final com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService
      hrLookupService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository
      accountRepository;
  private final JournalEntryService journalEntryService;

  PayrollAccountingService(
      AccountingCoreSupport accountingCoreSupport,
      PayrollRunRepository payrollRunRepository,
      CompanyContextService companyContextService,
      com.bigbrightpaints.erp.core.util.CompanyClock companyClock,
      com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService hrLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      JournalEntryService journalEntryService) {
    this.accountingCoreSupport = accountingCoreSupport;
    this.payrollRunRepository = payrollRunRepository;
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
    this.hrLookupService = hrLookupService;
    this.accountingLookupService = accountingLookupService;
    this.accountRepository = accountRepository;
    this.journalEntryService = journalEntryService;
  }

  @Transactional
  JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    String runToken = accountingCoreSupport.resolvePayrollRunToken(runNumber, runId);
    if (!StringUtils.hasText(runToken)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Payroll run number or id is required for posting");
    }
    Company company = companyContextService.requireCurrentCompany();
    LocalDate entryDate = postingDate != null ? postingDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "Payroll - " + runToken;
    List<JournalCreationRequest.LineRequest> standardizedLines =
        lines == null
            ? List.of()
            : lines.stream()
                .map(
                    line ->
                        new JournalCreationRequest.LineRequest(
                            line.accountId(), line.debit(), line.credit(), line.description()))
                .toList();
    return journalEntryService.createStandardJournal(
        new JournalCreationRequest(
            totalPayrollLinesAmount(lines),
            null,
            null,
            resolvedMemo,
            "PAYROLL",
            "PAYROLL-" + runToken,
            null,
            standardizedLines,
            entryDate,
            null,
            null,
            false));
  }

  @Transactional
  JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run = hrLookupService.lockPayrollRun(company, request.payrollRunId());

    if (run.getStatus() == PayrollRun.PayrollStatus.PAID
        && run.getPaymentJournalEntryId() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll run already marked PAID but payment journal reference is missing")
          .withDetail("payrollRunId", run.getId());
    }
    if (run.getStatus() != PayrollRun.PayrollStatus.POSTED
        && run.getStatus() != PayrollRun.PayrollStatus.PAID) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll must be posted to accounting before recording payment")
          .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
    }
    if (run.getJournalEntryId() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll must be posted to accounting before recording payment")
          .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
    }

    Account cashAccount =
        accountingCoreSupport.requireCashAccountForSettlement(
            company, request.cashAccountId(), "payroll payment");
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    Account salaryPayableAccount =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE")
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                        "Salary payable account (SALARY-PAYABLE) is required to record payroll"
                            + " payments"));

    JournalEntry postingJournal =
        accountingLookupService.requireJournalEntry(company, run.getJournalEntryId());
    BigDecimal payableAmount = BigDecimal.ZERO;
    if (postingJournal.getLines() != null) {
      for (var line : postingJournal.getLines()) {
        if (line.getAccount() == null || line.getAccount().getId() == null) {
          continue;
        }
        if (!salaryPayableAccount.getId().equals(line.getAccount().getId())) {
          continue;
        }
        payableAmount =
            payableAmount.add(
                MoneyUtils.zeroIfNull(line.getCredit())
                    .subtract(MoneyUtils.zeroIfNull(line.getDebit())));
      }
    }
    if (payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
              ErrorCode.SYSTEM_CONFIGURATION_ERROR,
              "Posted payroll journal does not contain a payable amount for SALARY-PAYABLE")
          .withDetail("postingJournalId", postingJournal.getId());
    }
    if (payableAmount.subtract(amount).abs().compareTo(AccountingConstants.ALLOCATION_TOLERANCE)
        > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Payroll payment amount does not match salary payable from the posted payroll"
                  + " journal")
          .withDetail("expectedAmount", payableAmount)
          .withDetail("requestAmount", amount);
    }

    if (run.getPaymentJournalEntryId() != null) {
      JournalEntry paid =
          accountingLookupService.requireJournalEntry(company, run.getPaymentJournalEntryId());
      accountingCoreSupport.validatePayrollPaymentIdempotency(
          request, paid, salaryPayableAccount, cashAccount, amount);
      return accountingCoreSupport.toDto(paid);
    }

    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payroll payment for " + run.getRunDate();
    String reference = accountingCoreSupport.resolvePayrollPaymentReference(run, request, company);
    List<JournalCreationRequest.LineRequest> lines =
        List.of(
            new JournalCreationRequest.LineRequest(
                salaryPayableAccount.getId(), payableAmount, BigDecimal.ZERO, memo),
            new JournalCreationRequest.LineRequest(
                cashAccount.getId(), BigDecimal.ZERO, payableAmount, memo));
    JournalEntryDto entry =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                payableAmount,
                salaryPayableAccount.getId(),
                cashAccount.getId(),
                memo,
                "PAYROLL",
                reference,
                null,
                lines,
                accountingCoreSupport.currentDate(company),
                null,
                null,
                Boolean.FALSE));
    JournalEntry paymentJournal = accountingLookupService.requireJournalEntry(company, entry.id());
    run.setPaymentJournalEntryId(paymentJournal.getId());
    payrollRunRepository.save(run);
    return entry;
  }

  private BigDecimal totalPayrollLinesAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal totalDebit = BigDecimal.ZERO;
    for (JournalEntryRequest.JournalLineRequest line : lines) {
      if (line == null || line.debit() == null) {
        continue;
      }
      totalDebit = totalDebit.add(line.debit());
    }
    return totalDebit;
  }
}
