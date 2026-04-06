package com.bigbrightpaints.erp.modules.hr.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.Attendance;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService.PayrollRunDto;

@Service
public class PayrollPostingService {

  private static final String PAYROLL_ACCOUNTS_CANONICAL_PATH = "/api/v1/accounting/accounts";
  private static final String PAYROLL_PAYMENTS_CANONICAL_PATH =
      "/api/v1/accounting/payroll/payments";
  private static final String PAYROLL_MIGRATION_SET = "v2";
  private static final Map<String, AccountType> REQUIRED_PAYROLL_ACCOUNT_TYPES =
      Map.ofEntries(
          Map.entry("SALARY-EXP", AccountType.EXPENSE),
          Map.entry("WAGE-EXP", AccountType.EXPENSE),
          Map.entry("SALARY-PAYABLE", AccountType.LIABILITY),
          Map.entry("EMP-ADV", AccountType.ASSET),
          Map.entry("PF-PAYABLE", AccountType.LIABILITY),
          Map.entry("ESI-PAYABLE", AccountType.LIABILITY),
          Map.entry("TDS-PAYABLE", AccountType.LIABILITY),
          Map.entry("PROFESSIONAL-TAX-PAYABLE", AccountType.LIABILITY));
  private static final List<String> REQUIRED_PAYROLL_ACCOUNTS =
      List.of(
          "SALARY-EXP",
          "WAGE-EXP",
          "SALARY-PAYABLE",
          "EMP-ADV",
          "PF-PAYABLE",
          "ESI-PAYABLE",
          "TDS-PAYABLE",
          "PROFESSIONAL-TAX-PAYABLE");

  private final PayrollRunRepository payrollRunRepository;
  private final PayrollRunLineRepository payrollRunLineRepository;
  private final EmployeeRepository employeeRepository;
  private final AttendanceRepository attendanceRepository;
  private final AccountingFacade accountingFacade;
  private final AccountRepository accountRepository;
  private final CompanyContextService companyContextService;
  private final CompanyScopedHrLookupService hrLookupService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyClock companyClock;
  private final AuditService auditService;

  @Autowired
  public PayrollPostingService(
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      EmployeeRepository employeeRepository,
      AttendanceRepository attendanceRepository,
      AccountingFacade accountingFacade,
      AccountRepository accountRepository,
      CompanyContextService companyContextService,
      CompanyScopedHrLookupService hrLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyClock companyClock,
      AuditService auditService) {
    this.payrollRunRepository = payrollRunRepository;
    this.payrollRunLineRepository = payrollRunLineRepository;
    this.employeeRepository = employeeRepository;
    this.attendanceRepository = attendanceRepository;
    this.accountingFacade = accountingFacade;
    this.accountRepository = accountRepository;
    this.companyContextService = companyContextService;
    this.hrLookupService = hrLookupService;
    this.accountingLookupService = accountingLookupService;
    this.companyClock = companyClock;
    this.auditService = auditService;
  }

  public PayrollPostingService(
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      EmployeeRepository employeeRepository,
      AttendanceRepository attendanceRepository,
      AccountingFacade accountingFacade,
      AccountRepository accountRepository,
      CompanyContextService companyContextService,
      com.bigbrightpaints.erp.core.util.CompanyEntityLookup companyEntityLookup,
      CompanyClock companyClock,
      AuditService auditService) {
    this(
        payrollRunRepository,
        payrollRunLineRepository,
        employeeRepository,
        attendanceRepository,
        accountingFacade,
        accountRepository,
        companyContextService,
        CompanyScopedHrLookupService.fromLegacy(companyEntityLookup),
        CompanyScopedAccountingLookupService.fromLegacy(companyEntityLookup),
        companyClock,
        auditService);
  }

  @Transactional
  public PayrollRunDto approvePayroll(Long payrollRunId) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run =
        payrollRunRepository
            .findByCompanyAndId(company, payrollRunId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Payroll run not found"));

    if (run.getStatus() != PayrollRun.PayrollStatus.CALCULATED) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE, "Can only approve payroll in CALCULATED status")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("currentStatus", run.getStatus().name());
    }
    if (payrollRunLineRepository.findByPayrollRun(run).isEmpty()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Cannot approve payroll run with no calculated lines")
          .withDetail("payrollRunId", payrollRunId);
    }

    run.setStatus(PayrollRun.PayrollStatus.APPROVED);
    run.setApprovedBy(getCurrentUser());
    run.setApprovedAt(CompanyTime.now(company));
    run.setProcessedBy(getCurrentUser());

    payrollRunRepository.save(run);
    return PayrollService.toDto(run);
  }

  @Transactional
  public PayrollRunDto postPayrollToAccounting(Long payrollRunId) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run = hrLookupService.lockPayrollRun(company, payrollRunId);
    boolean hasPostingJournalLink = hasPostingJournalLink(run);
    boolean statusPosted = run.getStatus() == PayrollRun.PayrollStatus.POSTED;

    if (statusPosted && !hasPostingJournalLink) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll run is POSTED but missing posting journal linkage")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("currentStatus", run.getStatus().name())
          .withDetail("invariant", "posted_requires_journal_link");
    }

    if (!statusPosted
        && !hasPostingJournalLink
        && run.getStatus() != PayrollRun.PayrollStatus.APPROVED) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE, "Can only post approved payroll")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("currentStatus", run.getStatus().name());
    }

    Account salaryExpenseAccount = findAccountByCode(company, "SALARY-EXP");
    Account wageExpenseAccount = findAccountByCode(company, "WAGE-EXP");
    Account salaryPayableAccount = findAccountByCode(company, "SALARY-PAYABLE");
    Account pfPayableAccount = findAccountByCode(company, "PF-PAYABLE");
    Account esiPayableAccount = findAccountByCode(company, "ESI-PAYABLE");
    Account tdsPayableAccount = findAccountByCode(company, "TDS-PAYABLE");
    Account professionalTaxPayableAccount = findAccountByCode(company, "PROFESSIONAL-TAX-PAYABLE");

    List<PayrollRunLine> runLines = payrollRunLineRepository.findByPayrollRun(run);
    if (runLines.isEmpty()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll run has no calculated lines; run calculate before posting")
          .withDetail("payrollRunId", payrollRunId);
    }

    BigDecimal totalGrossPay = sum(runLines, PayrollRunLine::getGrossPay);
    if (totalGrossPay.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll run total gross pay is zero; nothing to post")
          .withDetail("payrollRunId", payrollRunId);
    }

    BigDecimal totalLoans =
        sum(
            runLines,
            line ->
                firstPositive(
                    line.getLoanDeduction(), line.getAdvanceDeduction(), line.getAdvances()));
    BigDecimal totalPf = sum(runLines, PayrollRunLine::getPfDeduction);
    BigDecimal totalEsi = sum(runLines, PayrollRunLine::getEsiDeduction);
    BigDecimal totalTds = sum(runLines, PayrollRunLine::getTaxDeduction);
    BigDecimal totalProfessionalTax = sum(runLines, PayrollRunLine::getProfessionalTaxDeduction);
    BigDecimal totalOtherDeductions = sum(runLines, PayrollRunLine::getOtherDeductions);
    if (totalOtherDeductions.compareTo(BigDecimal.ZERO) > 0) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Payroll other deductions must be classified before posting")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("totalOtherDeductions", totalOtherDeductions);
    }

    Account loanAccount = null;
    if (totalLoans.compareTo(BigDecimal.ZERO) > 0) {
      loanAccount = findAccountByCode(company, "EMP-ADV");
    }

    BigDecimal salaryPayableAmount =
        totalGrossPay
            .subtract(totalLoans)
            .subtract(totalPf)
            .subtract(totalEsi)
            .subtract(totalTds)
            .subtract(totalProfessionalTax);

    if (salaryPayableAmount.compareTo(BigDecimal.ZERO) < 0) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Total deductions cannot exceed gross payroll")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("totalGrossPay", totalGrossPay)
          .withDetail(
              "totalDeductions",
              totalLoans.add(totalPf).add(totalEsi).add(totalTds).add(totalProfessionalTax));
    }

    List<JournalLineRequest> lines = new ArrayList<>();
    Account expenseAccount =
        run.getRunType() == PayrollRun.RunType.MONTHLY ? salaryExpenseAccount : wageExpenseAccount;
    lines.add(
        new JournalLineRequest(
            expenseAccount.getId(), "Payroll expense", totalGrossPay, BigDecimal.ZERO));

    addLiabilityLine(lines, salaryPayableAccount, "Payroll payable", salaryPayableAmount);
    addLiabilityLine(lines, pfPayableAccount, "PF payable", totalPf);
    addLiabilityLine(lines, esiPayableAccount, "ESI payable", totalEsi);
    addLiabilityLine(lines, tdsPayableAccount, "TDS payable", totalTds);
    addLiabilityLine(
        lines, professionalTaxPayableAccount, "Professional tax payable", totalProfessionalTax);
    if (loanAccount != null) {
      addLiabilityLine(lines, loanAccount, "Loan/advance recovery", totalLoans);
    }

    LocalDate postingDate = run.getPeriodEnd();
    LocalDate today = companyClock.today(company);
    if (postingDate == null || postingDate.isAfter(today)) {
      postingDate = today;
    }

    String runNumber = run.getRunNumber();
    if (!StringUtils.hasText(runNumber) && run.getId() != null) {
      runNumber = "LEGACY-" + run.getId();
      run.setRunNumber(runNumber);
    }

    String memo = "Payroll - " + (runNumber != null ? runNumber : "RUN");
    JournalCreationRequest standardizedPayrollRequest =
        new JournalCreationRequest(
            totalGrossPay,
            expenseAccount.getId(),
            salaryPayableAccount.getId(),
            memo,
            "PAYROLL",
            "PAYROLL-" + (runNumber != null ? runNumber : "RUN"),
            null,
            lines.stream()
                .map(
                    line ->
                        new JournalCreationRequest.LineRequest(
                            line.accountId(), line.debit(), line.credit(), line.description()))
                .toList(),
            postingDate,
            null,
            null,
            false);
    lines = standardizedPayrollRequest.resolvedLines();

    JournalEntryDto journal =
        accountingFacade.postPayrollRun(runNumber, run.getId(), postingDate, memo, lines);

    if (hasPostingJournalLink
        && run.getJournalEntryId() != null
        && !run.getJournalEntryId().equals(journal.id())) {
      throw new ApplicationException(
              ErrorCode.CONCURRENCY_CONFLICT,
              "Payroll run already linked to a different posting journal")
          .withDetail("payrollRunId", run.getId())
          .withDetail("journalEntryId", run.getJournalEntryId())
          .withDetail("postedJournalEntryId", journal.id());
    }

    boolean updated = false;
    if (run.getJournalEntryId() == null) {
      run.setJournalEntryId(journal.id());
      updated = true;
    }
    if (run.getJournalEntry() == null) {
      run.setJournalEntry(accountingLookupService.requireJournalEntry(company, journal.id()));
      updated = true;
    }
    if (run.getStatus() != PayrollRun.PayrollStatus.POSTED) {
      run.setStatus(PayrollRun.PayrollStatus.POSTED);
      if (run.getPostedBy() == null) {
        run.setPostedBy(getCurrentUser());
      }
      if (run.getPostedAt() == null) {
        run.setPostedAt(CompanyTime.now(company));
      }
      if (run.getTotalAmount() == null) {
        run.setTotalAmount(run.getTotalNetPay());
      }
      updated = true;
    }

    if (!statusPosted) {
      Map<Long, List<Attendance>> attendanceByEmployeeId =
          attendanceRepository
              .findByCompanyAndEmployeeIdsAndDateRange(
                  company,
                  runLines.stream().map(line -> line.getEmployee().getId()).distinct().toList(),
                  run.getPeriodStart(),
                  run.getPeriodEnd())
              .stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(record -> record.getEmployee().getId()));

      List<Attendance> linkedAttendance = new ArrayList<>();
      for (PayrollRunLine line : runLines) {
        List<Attendance> employeeAttendance =
            attendanceByEmployeeId.getOrDefault(line.getEmployee().getId(), List.of());
        for (Attendance attendance : employeeAttendance) {
          attendance.setPayrollRunId(run.getId());
          linkedAttendance.add(attendance);
        }
      }
      if (!linkedAttendance.isEmpty()) {
        attendanceRepository.saveAll(linkedAttendance);
      }
    }

    if (updated) {
      payrollRunRepository.save(run);
    }

    if (!statusPosted) {
      Map<String, String> auditMetadata =
          PayrollService.requiredPayrollPostedAuditMetadata(
              run, journal, postingDate, totalGrossPay, totalLoans, salaryPayableAmount);
      auditMetadata.put("totalPf", totalPf.toPlainString());
      auditMetadata.put("totalEsi", totalEsi.toPlainString());
      auditMetadata.put("totalTds", totalTds.toPlainString());
      auditMetadata.put("totalProfessionalTax", totalProfessionalTax.toPlainString());
      auditService.logSuccess(AuditEvent.PAYROLL_POSTED, auditMetadata);
    }

    return PayrollService.toDto(run);
  }

  @Transactional
  public PayrollRunDto markAsPaid(Long payrollRunId, String paymentReference) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run = hrLookupService.lockPayrollRun(company, payrollRunId);

    if (run.getPaymentJournalEntryId() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll payment journal is required before marking payroll as PAID")
          .withDetail("canonicalPath", PAYROLL_PAYMENTS_CANONICAL_PATH);
    }

    var paymentJournal =
        accountingLookupService.requireJournalEntry(company, run.getPaymentJournalEntryId());
    String canonicalPaymentReference = paymentJournal.getReferenceNumber();
    if (!StringUtils.hasText(canonicalPaymentReference)) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll payment journal reference is missing; re-record payment through accounting"
                  + " payroll payments")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("paymentJournalEntryId", run.getPaymentJournalEntryId())
          .withDetail("canonicalPath", PAYROLL_PAYMENTS_CANONICAL_PATH);
    }
    canonicalPaymentReference = canonicalPaymentReference.trim();
    String persistedPaymentReference =
        StringUtils.hasText(paymentReference) ? paymentReference.trim() : canonicalPaymentReference;

    if (run.getStatus() != PayrollRun.PayrollStatus.POSTED
        && run.getStatus() != PayrollRun.PayrollStatus.PAID) {
      throw new ApplicationException(
          ErrorCode.BUSINESS_INVALID_STATE, "Can only mark posted payroll as paid");
    }

    List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
    List<PayrollRunLine> dirtyLines = new ArrayList<>();
    for (PayrollRunLine line : lines) {
      if (line.getPaymentStatus() == PayrollRunLine.PaymentStatus.PAID) {
        continue;
      }
      line.setPaymentStatus(PayrollRunLine.PaymentStatus.PAID);
      line.setPaymentReference(canonicalPaymentReference);
      dirtyLines.add(line);

      BigDecimal advances =
          firstPositive(line.getLoanDeduction(), line.getAdvanceDeduction(), line.getAdvances());
      if (advances.compareTo(BigDecimal.ZERO) > 0) {
        Employee employee = line.getEmployee();
        BigDecimal currentBalance =
            employee.getAdvanceBalance() != null ? employee.getAdvanceBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.subtract(advances);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
          newBalance = BigDecimal.ZERO;
        }
        if (newBalance.compareTo(currentBalance) != 0) {
          employee.setAdvanceBalance(newBalance);
          employeeRepository.save(employee);
        }
      }
    }

    if (!dirtyLines.isEmpty()) {
      payrollRunLineRepository.saveAll(dirtyLines);
    }

    boolean payrollRunDirty = false;
    if (run.getStatus() != PayrollRun.PayrollStatus.PAID) {
      run.setStatus(PayrollRun.PayrollStatus.PAID);
      payrollRunDirty = true;
    }
    if (!Objects.equals(run.getPaymentReference(), persistedPaymentReference)) {
      run.setPaymentReference(persistedPaymentReference);
      payrollRunDirty = true;
    }
    LocalDate paymentDate =
        paymentJournal.getEntryDate() != null
            ? paymentJournal.getEntryDate()
            : companyClock.today(company);
    if (!Objects.equals(run.getPaymentDate(), paymentDate)) {
      run.setPaymentDate(paymentDate);
      payrollRunDirty = true;
    }
    if (payrollRunDirty) {
      payrollRunRepository.save(run);
    }

    return PayrollService.toDto(run);
  }

  private boolean hasPostingJournalLink(PayrollRun run) {
    if (run == null) {
      return false;
    }
    if (run.getJournalEntryId() != null) {
      return true;
    }
    return run.getJournalEntry() != null && run.getJournalEntry().getId() != null;
  }

  private BigDecimal sum(
      List<PayrollRunLine> lines, Function<PayrollRunLine, BigDecimal> selector) {
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return lines.stream()
        .map(selector)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private void addLiabilityLine(
      List<JournalLineRequest> lines, Account account, String description, BigDecimal amount) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    lines.add(new JournalLineRequest(account.getId(), description, BigDecimal.ZERO, amount));
  }

  private BigDecimal firstPositive(BigDecimal... values) {
    if (values == null) {
      return BigDecimal.ZERO;
    }
    for (BigDecimal value : values) {
      if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
        return value;
      }
    }
    return BigDecimal.ZERO;
  }

  private Account findAccountByCode(Company company, String code) {
    String normalizedCode = StringUtils.hasText(code) ? code.trim().toUpperCase(Locale.ROOT) : "";
    AccountType expectedType = REQUIRED_PAYROLL_ACCOUNT_TYPES.get(normalizedCode);
    String expectedTypeName = expectedType != null ? expectedType.name() : "UNKNOWN";

    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, normalizedCode)
        .orElseThrow(
            () ->
                new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Required payroll account not found: "
                            + normalizedCode
                            + " (expected type: "
                            + expectedTypeName
                            + "). "
                            + "Provision this account in Chart of Accounts before posting payroll.")
                    .withDetail("accountCode", normalizedCode)
                    .withDetail("expectedAccountType", expectedTypeName)
                    .withDetail("requiredPayrollAccounts", REQUIRED_PAYROLL_ACCOUNTS)
                    .withDetail("migrationSet", PAYROLL_MIGRATION_SET)
                    .withDetail("manualProvisioningRequired", true)
                    .withDetail("canonicalPath", PAYROLL_ACCOUNTS_CANONICAL_PATH));
  }

  private String getCurrentUser() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }
}
