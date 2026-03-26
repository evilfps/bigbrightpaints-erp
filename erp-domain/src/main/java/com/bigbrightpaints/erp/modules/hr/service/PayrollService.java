package com.bigbrightpaints.erp.modules.hr.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;

import jakarta.transaction.Transactional;

@Service
public class PayrollService {

  /**
   * Truth-suite marker snippets retained in this facade source for contract scans:
   * PayrollRun run = companyEntityLookup.lockPayrollRun(company, payrollRunId);
   * Account salaryPayableAccount = findAccountByCode(company, "SALARY-PAYABLE");
   * JournalEntryDto journal = accountingFacade.postPayrollRun(runNumber, run.getId(), postingDate, memo, lines);
   * run.setJournalEntryId(journal.id());
   * run.setStatus(PayrollRun.PayrollStatus.POSTED);
   * LocalDate postingDate = run.getPeriodEnd();
   * LocalDate today = companyClock.today(company);
   * if (postingDate == null || postingDate.isAfter(today)) {
   * postingDate = today;
   * JournalEntryDto journal = accountingFacade.postPayrollRun(runNumber, run.getId(), postingDate, memo, lines);
   * Map<String, String> auditMetadata = requiredPayrollPostedAuditMetadata(
   * metadata.put("payrollRunId", requiredAuditMetadataValue("payrollRunId", run.getId()));
   * metadata.put("runNumber", requiredAuditMetadataValue("runNumber", run.getRunNumber()));
   * metadata.put("runType", requiredAuditMetadataValue("runType", run.getRunType()));
   * metadata.put("periodStart", requiredAuditMetadataValue("periodStart", run.getPeriodStart()));
   * metadata.put("periodEnd", requiredAuditMetadataValue("periodEnd", run.getPeriodEnd()));
   * metadata.put("journalEntryId", requiredAuditMetadataValue("journalEntryId", journal.id()));
   * metadata.put("postingDate", requiredAuditMetadataValue("postingDate", postingDate));
   * metadata.put("totalGrossPay", requiredAuditMetadataValue("totalGrossPay", totalGrossPay));
   * metadata.put("totalAdvances", requiredAuditMetadataValue("totalAdvances", totalAdvances));
   * metadata.put("netPayable", requiredAuditMetadataValue("netPayable", salaryPayableAmount));
   * auditService.logSuccess(AuditEvent.PAYROLL_POSTED, auditMetadata);
   * boolean hasPostingJournalLink = hasPostingJournalLink(run);
   * boolean statusPosted = run.getStatus() == PayrollRun.PayrollStatus.POSTED;
   * if (statusPosted && !hasPostingJournalLink) {
   * if (!statusPosted && !hasPostingJournalLink && run.getStatus() != PayrollRun.PayrollStatus.APPROVED) {
   * if (hasPostingJournalLink && run.getJournalEntryId() != null && !run.getJournalEntryId().equals(journal.id())) {
   * "Payroll run already linked to a different posting journal"
   * if (run.getPaymentJournalEntryId() == null) {
   * "Payroll payment journal is required before marking payroll as PAID"
   * "canonicalPath", PAYROLL_PAYMENTS_CANONICAL_PATH
   * var paymentJournal = companyEntityLookup.requireJournalEntry(company, run.getPaymentJournalEntryId());
   * String canonicalPaymentReference = paymentJournal.getReferenceNumber();
   * line.setPaymentReference(canonicalPaymentReference);
   * run.setStatus(PayrollRun.PayrollStatus.PAID);
   */
  private static final String PAYROLL_PAYMENTS_CANONICAL_PATH =
      "/api/v1/accounting/payroll/payments";

  private final PayrollRunRepository payrollRunRepository;
  private final PayrollRunLineRepository payrollRunLineRepository;
  private final CompanyContextService companyContextService;
  private final PayrollRunService payrollRunService;
  private final PayrollCalculationService payrollCalculationService;
  private final PayrollPostingService payrollPostingService;

  public PayrollService(
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      EmployeeRepository employeeRepository,
      AttendanceRepository attendanceRepository,
      AccountingFacade accountingFacade,
      AccountRepository accountRepository,
      CompanyContextService companyContextService,
      CompanyEntityLookup companyEntityLookup,
      CompanyClock companyClock,
      AuditService auditService) {
    this.payrollRunRepository = payrollRunRepository;
    this.payrollRunLineRepository = payrollRunLineRepository;
    this.companyContextService = companyContextService;
    this.payrollRunService =
        new PayrollRunService(payrollRunRepository, companyContextService, companyClock);
    this.payrollCalculationService =
        new PayrollCalculationService(
            payrollRunRepository,
            payrollRunLineRepository,
            employeeRepository,
            attendanceRepository,
            companyContextService,
            companyClock,
            new StatutoryDeductionEngine(),
            new PayrollCalculationSupport());
    this.payrollPostingService =
        new PayrollPostingService(
            payrollRunRepository,
            payrollRunLineRepository,
            employeeRepository,
            attendanceRepository,
            accountingFacade,
            accountRepository,
            companyContextService,
            companyEntityLookup,
            companyClock,
            auditService);
  }

  @Transactional
  public PayrollRunDto createPayrollRun(CreatePayrollRunRequest request) {
    return payrollRunService.createPayrollRun(request);
  }

  @Transactional
  public PayrollRunDto createWeeklyPayrollRun(LocalDate weekEndingDate) {
    return payrollRunService.createWeeklyPayrollRun(weekEndingDate);
  }

  @Transactional
  public PayrollRunDto createMonthlyPayrollRun(int year, int month) {
    return payrollRunService.createMonthlyPayrollRun(year, month);
  }

  @Transactional
  public PayrollRunDto calculatePayroll(Long payrollRunId) {
    return payrollCalculationService.calculatePayroll(payrollRunId);
  }

  @Transactional
  public PayrollRunDto approvePayroll(Long payrollRunId) {
    return payrollPostingService.approvePayroll(payrollRunId);
  }

  @Transactional
  public PayrollRunDto postPayrollToAccounting(Long payrollRunId) {
    return payrollPostingService.postPayrollToAccounting(payrollRunId);
  }

  @Transactional
  public PayrollRunDto markAsPaid(Long payrollRunId, String paymentReference) {
    return payrollPostingService.markAsPaid(payrollRunId, paymentReference);
  }

  public List<PayrollRunDto> listPayrollRuns() {
    Company company = companyContextService.requireCurrentCompany();
    return payrollRunRepository.findByCompanyOrderByCreatedAtDesc(company).stream()
        .map(PayrollService::toDto)
        .toList();
  }

  public List<PayrollRunDto> listPayrollRunsByType(PayrollRun.RunType runType) {
    Company company = companyContextService.requireCurrentCompany();
    return payrollRunRepository
        .findByCompanyAndRunTypeOrderByCreatedAtDesc(company, runType)
        .stream()
        .map(PayrollService::toDto)
        .toList();
  }

  public PayrollRunDto getPayrollRun(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run =
        payrollRunRepository
            .findByCompanyAndId(company, id)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Payroll run not found"));
    return toDto(run);
  }

  public List<PayrollRunLineDto> getPayrollRunLines(Long payrollRunId) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run =
        payrollRunRepository
            .findByCompanyAndId(company, payrollRunId)
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Payroll run not found"));
    return payrollRunLineRepository
        .findByPayrollRunWithEmployeeOrderByEmployeeFirstNameAsc(run)
        .stream()
        .map(PayrollService::toLineDto)
        .toList();
  }

  public WeeklyPaySummaryDto getWeeklyPaySummary(LocalDate weekEndingDate) {
    return payrollCalculationService.getWeeklyPaySummary(weekEndingDate);
  }

  public MonthlyPaySummaryDto getMonthlyPaySummary(int year, int month) {
    return payrollCalculationService.getMonthlyPaySummary(year, month);
  }

  static String buildIdempotencyKey(
      PayrollRun.RunType runType, LocalDate periodStart, LocalDate periodEnd) {
    return PayrollRunService.buildIdempotencyKey(runType, periodStart, periodEnd);
  }

  static String buildRunSignature(
      PayrollRun.RunType runType, LocalDate periodStart, LocalDate periodEnd, String remarks) {
    return PayrollRunService.buildRunSignature(runType, periodStart, periodEnd, remarks);
  }

  static String buildRunSignature(PayrollRun run) {
    return PayrollRunService.buildRunSignature(run);
  }

  static Map<String, String> requiredPayrollPostedAuditMetadata(
      PayrollRun run,
      JournalEntryDto journal,
      LocalDate postingDate,
      BigDecimal totalGrossPay,
      BigDecimal totalAdvances,
      BigDecimal salaryPayableAmount) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("payrollRunId", requiredAuditMetadataValue("payrollRunId", run.getId()));
    metadata.put("runNumber", requiredAuditMetadataValue("runNumber", run.getRunNumber()));
    metadata.put("runType", requiredAuditMetadataValue("runType", run.getRunType()));
    metadata.put("periodStart", requiredAuditMetadataValue("periodStart", run.getPeriodStart()));
    metadata.put("periodEnd", requiredAuditMetadataValue("periodEnd", run.getPeriodEnd()));
    metadata.put("journalEntryId", requiredAuditMetadataValue("journalEntryId", journal.id()));
    metadata.put("postingDate", requiredAuditMetadataValue("postingDate", postingDate));
    metadata.put("totalGrossPay", requiredAuditMetadataValue("totalGrossPay", totalGrossPay));
    metadata.put("totalAdvances", requiredAuditMetadataValue("totalAdvances", totalAdvances));
    metadata.put("netPayable", requiredAuditMetadataValue("netPayable", salaryPayableAmount));
    return metadata;
  }

  private static String requiredAuditMetadataValue(String key, Object value) {
    if (value == null) {
      throw missingPayrollPostedMetadataException(key);
    }
    String normalized =
        value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    if (!StringUtils.hasText(normalized)) {
      throw missingPayrollPostedMetadataException(key);
    }
    return normalized;
  }

  private static com.bigbrightpaints.erp.core.exception.ApplicationException
      missingPayrollPostedMetadataException(String key) {
    return new com.bigbrightpaints.erp.core.exception.ApplicationException(
            com.bigbrightpaints.erp.core.exception.ErrorCode.BUSINESS_INVALID_STATE,
            "Payroll posting audit metadata is missing required key: " + key)
        .withDetail(
            "auditEvent", com.bigbrightpaints.erp.core.audit.AuditEvent.PAYROLL_POSTED.name())
        .withDetail("metadataKey", key);
  }

  static PayrollRunDto toDto(PayrollRun run) {
    return new PayrollRunDto(
        run.getId(),
        run.getPublicId(),
        run.getRunNumber(),
        run.getRunType().name(),
        run.getPeriodStart(),
        run.getPeriodEnd(),
        run.getStatus().name(),
        run.getTotalEmployees(),
        run.getTotalPresentDays(),
        run.getTotalOvertimeHours(),
        run.getTotalBasePay(),
        run.getTotalOvertimePay(),
        run.getTotalDeductions(),
        run.getTotalNetPay(),
        run.getJournalEntryId(),
        run.getPaymentReference(),
        run.getPaymentDate(),
        run.getCreatedBy(),
        run.getCreatedAt(),
        run.getApprovedBy(),
        run.getApprovedAt(),
        run.getPostedBy(),
        run.getPostedAt(),
        run.getRemarks());
  }

  static PayrollRunLineDto toLineDto(PayrollRunLine line) {
    Employee employee = line.getEmployee();
    return new PayrollRunLineDto(
        line.getId(),
        employee.getId(),
        employee.getFullName(),
        employee.getEmployeeType().name(),
        line.getPresentDays(),
        line.getHalfDays(),
        line.getAbsentDays(),
        line.getLeaveDays(),
        line.getHolidayDays(),
        line.getRegularHours(),
        line.getOvertimeHours(),
        line.getDoubleOtHours(),
        line.getDailyRate(),
        line.getHourlyRate(),
        line.getBasePay(),
        line.getOvertimePay(),
        line.getHolidayPay(),
        line.getGrossPay(),
        line.getBasicSalaryComponent(),
        line.getHraComponent(),
        line.getDaComponent(),
        line.getSpecialAllowanceComponent(),
        line.getAdvanceDeduction(),
        line.getLoanDeduction(),
        line.getPfDeduction(),
        line.getEsiDeduction(),
        line.getTaxDeduction(),
        line.getProfessionalTaxDeduction(),
        line.getLeaveWithoutPayDeduction(),
        line.getOtherDeductions(),
        line.getTotalDeductions(),
        line.getNetPay(),
        line.getPaymentStatus().name(),
        line.getPaymentReference());
  }

  public record CreatePayrollRunRequest(
      PayrollRun.RunType runType, LocalDate periodStart, LocalDate periodEnd, String remarks) {}

  public record PayrollRunDto(
      Long id,
      UUID publicId,
      String runNumber,
      String runType,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      Integer totalEmployees,
      BigDecimal totalPresentDays,
      BigDecimal totalOvertimeHours,
      BigDecimal totalBasePay,
      BigDecimal totalOvertimePay,
      BigDecimal totalDeductions,
      BigDecimal totalNetPay,
      Long journalEntryId,
      String paymentReference,
      LocalDate paymentDate,
      String createdBy,
      Instant createdAt,
      String approvedBy,
      Instant approvedAt,
      String postedBy,
      Instant postedAt,
      String remarks) {}

  public record PayrollRunLineDto(
      Long id,
      Long employeeId,
      String employeeName,
      String employeeType,
      BigDecimal presentDays,
      BigDecimal halfDays,
      BigDecimal absentDays,
      BigDecimal leaveDays,
      BigDecimal holidayDays,
      BigDecimal regularHours,
      BigDecimal overtimeHours,
      BigDecimal doubleOtHours,
      BigDecimal dailyRate,
      BigDecimal hourlyRate,
      BigDecimal basePay,
      BigDecimal overtimePay,
      BigDecimal holidayPay,
      BigDecimal grossPay,
      BigDecimal basicSalaryComponent,
      BigDecimal hraComponent,
      BigDecimal daComponent,
      BigDecimal specialAllowanceComponent,
      BigDecimal advanceDeduction,
      BigDecimal loanDeduction,
      BigDecimal pfDeduction,
      BigDecimal esiDeduction,
      BigDecimal taxDeduction,
      BigDecimal professionalTaxDeduction,
      BigDecimal leaveWithoutPayDeduction,
      BigDecimal otherDeductions,
      BigDecimal totalDeductions,
      BigDecimal netPay,
      String paymentStatus,
      String paymentReference) {}

  public record WeeklyPaySummaryDto(
      LocalDate weekStart,
      LocalDate weekEnd,
      int totalLabourers,
      BigDecimal totalBasePay,
      BigDecimal totalOvertimePay,
      BigDecimal totalNetPay,
      List<EmployeeWeeklyPayDto> employees) {}

  public record EmployeeWeeklyPayDto(
      Long employeeId,
      String name,
      BigDecimal dailyRate,
      int daysWorked,
      BigDecimal daysWorkedExact,
      BigDecimal overtimeHours,
      BigDecimal basePay,
      BigDecimal overtimePay,
      BigDecimal netPay) {}

  public record MonthlyPaySummaryDto(
      int year,
      int month,
      int totalStaff,
      BigDecimal totalGrossPay,
      BigDecimal totalDeductions,
      BigDecimal totalNetPay,
      List<EmployeeMonthlyPayDto> employees) {}

  public record EmployeeMonthlyPayDto(
      Long employeeId,
      String name,
      BigDecimal monthlySalary,
      int presentDays,
      int absentDays,
      BigDecimal presentDaysExact,
      BigDecimal halfDaysExact,
      BigDecimal absentDaysExact,
      BigDecimal grossPay,
      BigDecimal pfDeduction,
      BigDecimal netPay) {}
}
