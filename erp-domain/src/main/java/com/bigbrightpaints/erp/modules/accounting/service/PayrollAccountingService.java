package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

import jakarta.persistence.EntityManager;

@Service
public class PayrollAccountingService extends AccountingCoreEngineCore {

  private final JournalEntryService journalEntryService;

  @Autowired
  public PayrollAccountingService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      JournalEntryRepository journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      AccountingPeriodService accountingPeriodService,
      ReferenceNumberService referenceNumberService,
      ApplicationEventPublisher eventPublisher,
      CompanyClock companyClock,
      CompanyEntityLookup companyEntityLookup,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      InvoiceRepository invoiceRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      InvoiceSettlementPolicy invoiceSettlementPolicy,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository,
      EntityManager entityManager,
      SystemSettingsService systemSettingsService,
      AuditService auditService,
      AccountingEventStore accountingEventStore,
      JournalEntryService journalEntryService) {
    super(
        companyContextService,
        accountRepository,
        journalEntryRepository,
        dealerLedgerService,
        supplierLedgerService,
        payrollRunRepository,
        payrollRunLineRepository,
        accountingPeriodService,
        referenceNumberService,
        eventPublisher,
        companyClock,
        companyEntityLookup,
        settlementAllocationRepository,
        rawMaterialPurchaseRepository,
        invoiceRepository,
        rawMaterialMovementRepository,
        rawMaterialBatchRepository,
        finishedGoodBatchRepository,
        dealerRepository,
        supplierRepository,
        invoiceSettlementPolicy,
        journalReferenceResolver,
        journalReferenceMappingRepository,
        entityManager,
        systemSettingsService,
        auditService,
        accountingEventStore);
    this.journalEntryService = journalEntryService;
  }

  @Transactional
  public JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    String runToken = resolvePayrollRunToken(runNumber, runId);
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
                .map(line -> new JournalCreationRequest.LineRequest(line.accountId(), line.debit(), line.credit(), line.description()))
                .toList();
    JournalCreationRequest standardizedRequest = new JournalCreationRequest(
            totalLinesAmount(lines),
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
            false);
    return createStandardJournal(standardizedRequest);
  }

  @Transactional
  public PayrollBatchPaymentResponse processPayrollBatchPayment(
      PayrollBatchPaymentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    if (request.lines() == null || request.lines().isEmpty()) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one payroll line is required"); }

    Account cash =
        requireCashAccountForSettlement(company, request.cashAccountId(), "payroll batch payment");
    Account expense = requireAccount(company, request.expenseAccountId());

    Account taxPayable =
        request.taxPayableAccountId() != null
            ? requireAccount(company, request.taxPayableAccountId())
            : null;
    Account pfPayable =
        request.pfPayableAccountId() != null
            ? requireAccount(company, request.pfPayableAccountId())
            : null;

    Account employerTaxExpense =
        request.employerTaxExpenseAccountId() != null
            ? requireAccount(company, request.employerTaxExpenseAccountId())
            : null;
    Account employerPfExpense =
        request.employerPfExpenseAccountId() != null
            ? requireAccount(company, request.employerPfExpenseAccountId())
            : null;

    BigDecimal defaultTaxRate =
        request.defaultTaxRate() != null ? request.defaultTaxRate() : BigDecimal.ZERO;
    BigDecimal defaultPfRate =
        request.defaultPfRate() != null ? request.defaultPfRate() : BigDecimal.ZERO;
    BigDecimal employerTaxRate =
        request.employerTaxRate() != null ? request.employerTaxRate() : BigDecimal.ZERO;
    BigDecimal employerPfRate =
        request.employerPfRate() != null ? request.employerPfRate() : BigDecimal.ZERO;

    List<PayrollBatchPaymentRequest.PayrollLine> lines = request.lines();
    List<PayrollBatchPaymentResponse.LineTotal> lineTotals = new ArrayList<>();

    BigDecimal totalGross = BigDecimal.ZERO;
    BigDecimal totalTaxWithholding = BigDecimal.ZERO;
    BigDecimal totalPfWithholding = BigDecimal.ZERO;
    BigDecimal totalAdvances = BigDecimal.ZERO;
    BigDecimal totalNetPay = BigDecimal.ZERO;

    for (PayrollBatchPaymentRequest.PayrollLine line : lines) {
      if (!StringUtils.hasText(line.name())) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Line name is required"); }
      int days = line.days() == null ? 0 : line.days();
      if (days <= 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Days must be greater than zero for " + line.name());
      }
      BigDecimal wage = line.dailyWage() == null ? BigDecimal.ZERO : line.dailyWage();
      if (wage.compareTo(BigDecimal.ZERO) <= 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Daily wage must be greater than zero for " + line.name());
      }
      BigDecimal advances = line.advances() == null ? BigDecimal.ZERO : line.advances();
      if (advances.compareTo(BigDecimal.ZERO) < 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Advances cannot be negative for " + line.name());
      }

      BigDecimal grossPay =
          wage.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
      BigDecimal taxWithholding =
          line.taxWithholding() != null
              ? line.taxWithholding()
              : grossPay.multiply(defaultTaxRate).setScale(2, RoundingMode.HALF_UP);
      BigDecimal pfWithholding =
          line.pfWithholding() != null
              ? line.pfWithholding()
              : grossPay.multiply(defaultPfRate).setScale(2, RoundingMode.HALF_UP);
      BigDecimal netPay =
          grossPay
              .subtract(taxWithholding)
              .subtract(pfWithholding)
              .subtract(advances)
              .setScale(2, RoundingMode.HALF_UP);

      if (netPay.compareTo(BigDecimal.ZERO) < 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Net pay cannot be negative for " + line.name() + ". Deductions exceed gross pay.");
      }

      totalGross = totalGross.add(grossPay);
      totalTaxWithholding = totalTaxWithholding.add(taxWithholding);
      totalPfWithholding = totalPfWithholding.add(pfWithholding);
      totalAdvances = totalAdvances.add(advances);
      totalNetPay = totalNetPay.add(netPay);

      lineTotals.add(new PayrollBatchPaymentResponse.LineTotal(
          line.name(), days, wage.setScale(2, RoundingMode.HALF_UP), grossPay, taxWithholding, pfWithholding, advances, netPay, line.notes()));
    }

    if (totalGross.compareTo(BigDecimal.ZERO) <= 0) { throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Total gross payroll amount must be greater than zero"); }

    BigDecimal employerTaxAmount =
        totalGross.multiply(employerTaxRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal employerPfAmount =
        totalGross.multiply(employerPfRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalEmployerCost = totalGross.add(employerTaxAmount).add(employerPfAmount);

    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payroll batch for " + request.runDate();
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : referenceNumberService.payrollPaymentReference(company);

    PayrollRun run = new PayrollRun();
    run.setCompany(company);
    run.setRunType(PayrollRun.RunType.MONTHLY);
    run.setPeriodStart(request.runDate());
    run.setPeriodEnd(request.runDate());
    run.setRunDate(request.runDate());
    run.setRunNumber(reference);
    run.setNotes(memo);
    run.setTotalAmount(totalGross);
    run.setStatus("DRAFT");
    run.setProcessedBy(resolveCurrentUsername());
    PayrollRun savedRun = payrollRunRepository.save(run);

    List<PayrollRunLine> persistedLines = new ArrayList<>();
    for (PayrollBatchPaymentResponse.LineTotal line : lineTotals) {
      PayrollRunLine entity = new PayrollRunLine();
      entity.setPayrollRun(savedRun);
      entity.setName(line.name());
      entity.setDaysWorked(line.days());
      entity.setDailyWage(line.dailyWage());
      entity.setAdvances(line.advances());
      entity.setLineTotal(line.netPay());
      entity.setNotes(line.notes());
      persistedLines.add(entity);
    }
    payrollRunLineRepository.saveAll(persistedLines);

    List<JournalEntryRequest.JournalLineRequest> payrollLines = new ArrayList<>();
    BigDecimal totalCredits = BigDecimal.ZERO;

    if (totalNetPay.compareTo(BigDecimal.ZERO) > 0) {
      payrollLines.add(new JournalEntryRequest.JournalLineRequest(
          cash.getId(), "Net payroll disbursement", BigDecimal.ZERO, totalNetPay));
      totalCredits = totalCredits.add(totalNetPay);
    }

    if (taxPayable != null && totalTaxWithholding.compareTo(BigDecimal.ZERO) > 0) {
      payrollLines.add(new JournalEntryRequest.JournalLineRequest(
          taxPayable.getId(), "Employee tax withholding (TDS)", BigDecimal.ZERO, totalTaxWithholding));
      totalCredits = totalCredits.add(totalTaxWithholding);
    }

    if (pfPayable != null && totalPfWithholding.compareTo(BigDecimal.ZERO) > 0) {
      payrollLines.add(new JournalEntryRequest.JournalLineRequest(
          pfPayable.getId(), "Employee PF contribution", BigDecimal.ZERO, totalPfWithholding));
      totalCredits = totalCredits.add(totalPfWithholding);
    }

    payrollLines.add(
        0,
        new JournalEntryRequest.JournalLineRequest(expense.getId(), "Payroll expense", totalCredits, BigDecimal.ZERO));

    JournalEntryDto payrollJe =
        createJournalEntry(new JournalEntryRequest(reference, request.runDate(), memo, null, null, Boolean.FALSE, payrollLines));
    JournalEntry payrollEntry = companyEntityLookup.requireJournalEntry(company, payrollJe.id());

    Long employerContribJournalId = null;
    if ((employerTaxAmount.compareTo(BigDecimal.ZERO) > 0 && employerTaxExpense != null && taxPayable != null)
        || (employerPfAmount.compareTo(BigDecimal.ZERO) > 0 && employerPfExpense != null && pfPayable != null)) {

      List<JournalEntryRequest.JournalLineRequest> employerLines = new ArrayList<>();

      if (employerTaxAmount.compareTo(BigDecimal.ZERO) > 0 && employerTaxExpense != null && taxPayable != null) {
        employerLines.add(new JournalEntryRequest.JournalLineRequest(
            employerTaxExpense.getId(), "Employer tax contribution", employerTaxAmount, BigDecimal.ZERO));
        employerLines.add(new JournalEntryRequest.JournalLineRequest(
            taxPayable.getId(), "Employer tax payable", BigDecimal.ZERO, employerTaxAmount));
      }

      if (employerPfAmount.compareTo(BigDecimal.ZERO) > 0 && employerPfExpense != null && pfPayable != null) {
        employerLines.add(new JournalEntryRequest.JournalLineRequest(
            employerPfExpense.getId(), "Employer PF contribution", employerPfAmount, BigDecimal.ZERO));
        employerLines.add(new JournalEntryRequest.JournalLineRequest(
            pfPayable.getId(), "Employer PF payable", BigDecimal.ZERO, employerPfAmount));
      }

      if (!employerLines.isEmpty()) {
        String employerRef = reference + "-EMP";
        JournalEntryDto employerJe =
            createJournalEntry(new JournalEntryRequest(
                employerRef, request.runDate(), "Employer contributions for " + memo, null, null, Boolean.FALSE, employerLines));
        employerContribJournalId = employerJe.id();
      }
    }

    savedRun.setStatus(PayrollRun.PayrollStatus.PAID);
    savedRun.setJournalEntryId(payrollEntry.getId());
    savedRun.setJournalEntry(payrollEntry);
    savedRun.setPaymentJournalEntryId(payrollEntry.getId());
    String paymentReference =
        StringUtils.hasText(payrollEntry.getReferenceNumber())
            ? payrollEntry.getReferenceNumber().trim()
            : reference;
    savedRun.setPaymentReference(paymentReference);
    savedRun.setPaymentDate(
        payrollEntry.getEntryDate() != null ? payrollEntry.getEntryDate() : request.runDate());
    for (PayrollRunLine line : persistedLines) {
      line.setPaymentStatus(PayrollRunLine.PaymentStatus.PAID);
      line.setPaymentReference(paymentReference);
    }

    return new PayrollBatchPaymentResponse(
        savedRun.getId(),
        savedRun.getRunDate(),
        totalGross.setScale(2, RoundingMode.HALF_UP),
        totalTaxWithholding.setScale(2, RoundingMode.HALF_UP),
        totalPfWithholding.setScale(2, RoundingMode.HALF_UP),
        totalAdvances.setScale(2, RoundingMode.HALF_UP),
        totalNetPay.setScale(2, RoundingMode.HALF_UP),
        employerTaxAmount.setScale(2, RoundingMode.HALF_UP),
        employerPfAmount.setScale(2, RoundingMode.HALF_UP),
        totalEmployerCost.setScale(2, RoundingMode.HALF_UP),
        payrollEntry.getId(),
        employerContribJournalId,
        lineTotals);
  }

  @Transactional
  public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run = companyEntityLookup.lockPayrollRun(company, request.payrollRunId());

    if (run.getStatus() == PayrollRun.PayrollStatus.PAID && run.getPaymentJournalEntryId() == null) {
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
        requireCashAccountForSettlement(company, request.cashAccountId(), "payroll payment");
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");

    Account salaryPayableAccount =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE")
            .orElseThrow(() -> new ApplicationException(
                ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                "Salary payable account (SALARY-PAYABLE) is required to record payroll payments"));

    JournalEntry postingJournal = companyEntityLookup.requireJournalEntry(company, run.getJournalEntryId());
    BigDecimal payableAmount = BigDecimal.ZERO;
    if (postingJournal.getLines() != null) {
      for (var line : postingJournal.getLines()) {
        if (line.getAccount() == null || line.getAccount().getId() == null) { continue; }
        if (!salaryPayableAccount.getId().equals(line.getAccount().getId())) { continue; }
        BigDecimal credit = MoneyUtils.zeroIfNull(line.getCredit());
        BigDecimal debit = MoneyUtils.zeroIfNull(line.getDebit());
        payableAmount = payableAmount.add(credit.subtract(debit));
      }
    }
    if (payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
              ErrorCode.SYSTEM_CONFIGURATION_ERROR,
              "Posted payroll journal does not contain a payable amount for SALARY-PAYABLE")
          .withDetail("postingJournalId", postingJournal.getId());
    }
    if (payableAmount.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Payroll payment amount does not match salary payable from the posted payroll"
                  + " journal")
          .withDetail("expectedAmount", payableAmount)
          .withDetail("requestAmount", amount);
    }

    if (run.getPaymentJournalEntryId() != null) {
      JournalEntry paid = companyEntityLookup.requireJournalEntry(company, run.getPaymentJournalEntryId());
      validatePayrollPaymentIdempotency(request, paid, salaryPayableAccount, cashAccount, amount);
      log.info(
          "Payroll run {} already has payment journal {}, returning existing",
          run.getId(),
          paid.getReferenceNumber());
      return toDto(paid);
    }

    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payroll payment for " + run.getRunDate();
    String reference = resolvePayrollPaymentReference(run, request, company);

    JournalEntryRequest payload = new JournalEntryRequest(
            reference,
            currentDate(company),
            memo,
            null,
            null,
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(salaryPayableAccount.getId(), memo, payableAmount, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, payableAmount)));
    JournalEntryDto entry = createJournalEntry(payload);
    JournalEntry paymentJournal = companyEntityLookup.requireJournalEntry(company, entry.id());

    run.setPaymentJournalEntryId(paymentJournal.getId());
    payrollRunRepository.save(run);
    return entry;
  }

  @Override
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryService.createJournalEntry(request);
  }

  @Override
  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
  }

  private BigDecimal totalLinesAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
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
