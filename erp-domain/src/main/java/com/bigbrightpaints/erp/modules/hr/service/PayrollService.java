package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.hr.domain.*;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;

@Service
public class PayrollService {

    private static final BigDecimal ADVANCE_DEDUCTION_CAP = new BigDecimal("0.20");
    private static final String PAYROLL_ACCOUNTS_CANONICAL_PATH = "/api/v1/accounting/accounts";
    private static final String PAYROLL_PAYMENTS_CANONICAL_PATH = "/api/v1/accounting/payroll/payments";
    private static final String PAYROLL_MIGRATION_SET = "v2";
    private static final Map<String, AccountType> REQUIRED_PAYROLL_ACCOUNT_TYPES = Map.of(
            "SALARY-EXP", AccountType.EXPENSE,
            "WAGE-EXP", AccountType.EXPENSE,
            "SALARY-PAYABLE", AccountType.LIABILITY,
            "EMP-ADV", AccountType.ASSET
    );
    private static final List<String> REQUIRED_PAYROLL_ACCOUNTS = List.of(
            "SALARY-EXP",
            "WAGE-EXP",
            "SALARY-PAYABLE",
            "EMP-ADV"
    );

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunLineRepository payrollRunLineRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final AccountingFacade accountingFacade;
    private final AccountRepository accountRepository;
    private final CompanyContextService companyContextService;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private final AuditService auditService;

    public PayrollService(PayrollRunRepository payrollRunRepository,
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
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
        this.accountingFacade = accountingFacade;
        this.accountRepository = accountRepository;
        this.companyContextService = companyContextService;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
        this.auditService = auditService;
    }

    // ===== PAYROLL RUN MANAGEMENT =====

    /**
     * Create a new payroll run for the given period
     */
    @Transactional
    public PayrollRunDto createPayrollRun(CreatePayrollRunRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Payroll run request is required");
        }
        if (request.runType() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Payroll run type is required");
        }
        if (request.periodStart() == null || request.periodEnd() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Payroll period start/end dates are required");
        }
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Payroll period end date cannot be before start date");
        }
        String idempotencyKey = buildIdempotencyKey(request.runType(), request.periodStart(), request.periodEnd());
        String requestSignature = buildRunSignature(request.runType(), request.periodStart(), request.periodEnd(), request.remarks());
        Optional<PayrollRun> existing = payrollRunRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isPresent()) {
            PayrollRun run = existing.get();
            assertRunSignatureMatches(run, requestSignature, idempotencyKey);
            ensureIdempotencyMetadata(run, idempotencyKey, requestSignature);
            return toDto(run);
        }
        Optional<PayrollRun> legacy = payrollRunRepository.findByCompanyAndRunTypeAndPeriodStartAndPeriodEnd(
                company, request.runType(), request.periodStart(), request.periodEnd());
        if (legacy.isPresent()) {
            PayrollRun run = legacy.get();
            assertRunSignatureMatches(run, requestSignature, idempotencyKey);
            ensureIdempotencyMetadata(run, idempotencyKey, requestSignature);
            return toDto(run);
        }

        String currentUser = getCurrentUser();

        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunType(request.runType());
        run.setPeriodStart(request.periodStart());
        run.setPeriodEnd(request.periodEnd());
        LocalDate runDate = request.periodEnd() != null ? request.periodEnd() : request.periodStart();
        run.setRunDate(runDate != null ? runDate : companyClock.today(company));
        run.setRunNumber(generateRunNumber(company, request.runType(), request.periodStart()));
        run.setCreatedBy(currentUser);
        run.setRemarks(request.remarks());
        run.setIdempotencyKey(idempotencyKey);
        run.setIdempotencyHash(requestSignature);

        try {
            run = payrollRunRepository.save(run);
            return toDto(run);
        } catch (DataIntegrityViolationException ex) {
            Optional<PayrollRun> concurrent = payrollRunRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
            if (concurrent.isPresent()) {
                PayrollRun existingRun = concurrent.get();
                assertRunSignatureMatches(existingRun, requestSignature, idempotencyKey);
                ensureIdempotencyMetadata(existingRun, idempotencyKey, requestSignature);
                return toDto(existingRun);
            }
            Optional<PayrollRun> legacyConcurrent = payrollRunRepository.findByCompanyAndRunTypeAndPeriodStartAndPeriodEnd(
                    company, request.runType(), request.periodStart(), request.periodEnd());
            if (legacyConcurrent.isPresent()) {
                PayrollRun existingRun = legacyConcurrent.get();
                assertRunSignatureMatches(existingRun, requestSignature, idempotencyKey);
                ensureIdempotencyMetadata(existingRun, idempotencyKey, requestSignature);
                return toDto(existingRun);
            }
            throw ex;
        }
    }

    /**
     * Create weekly payroll run for labourers (current week ending Saturday)
     */
    @Transactional
    public PayrollRunDto createWeeklyPayrollRun(LocalDate weekEndingDate) {
        LocalDate weekStart = weekEndingDate.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekEndingDate.with(DayOfWeek.SATURDAY);
        
        return createPayrollRun(new CreatePayrollRunRequest(
            PayrollRun.RunType.WEEKLY,
            weekStart,
            weekEnd,
            "Weekly payroll for labourers"
        ));
    }

    /**
     * Create monthly payroll run for staff
     */
    @Transactional
    public PayrollRunDto createMonthlyPayrollRun(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
        
        return createPayrollRun(new CreatePayrollRunRequest(
            PayrollRun.RunType.MONTHLY,
            monthStart,
            monthEnd,
            "Monthly payroll for staff - " + monthStart.getMonth() + " " + year
        ));
    }

    /**
     * Calculate pay for all employees in a payroll run
     */
    @Transactional
    public PayrollRunDto calculatePayroll(Long payrollRunId) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));

        if (run.getStatus() != PayrollRun.PayrollStatus.DRAFT) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Can only calculate payroll in DRAFT status")
                    .withDetail("payrollRunId", payrollRunId)
                    .withDetail("currentStatus", run.getStatus().name());
        }

        // Clear existing lines
        payrollRunLineRepository.deleteByPayrollRun(run);

        // Get employees based on run type
        List<Employee> employees = run.getRunType() == PayrollRun.RunType.WEEKLY
            ? employeeRepository.findByCompanyAndEmployeeTypeAndStatus(company, Employee.EmployeeType.LABOUR, "ACTIVE")
            : employeeRepository.findByCompanyAndEmployeeTypeAndStatus(company, Employee.EmployeeType.STAFF, "ACTIVE");

        BigDecimal totalBasePay = BigDecimal.ZERO;
        BigDecimal totalOvertimePay = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal totalNetPay = BigDecimal.ZERO;
        BigDecimal totalPresentDays = BigDecimal.ZERO;
        BigDecimal totalOtHours = BigDecimal.ZERO;

        for (Employee employee : employees) {
            PayrollRunLine line = calculateEmployeePay(run, employee);
            payrollRunLineRepository.save(line);

            totalBasePay = totalBasePay.add(line.getBasePay());
            totalOvertimePay = totalOvertimePay.add(line.getOvertimePay());
            totalDeductions = totalDeductions.add(line.getTotalDeductions());
            totalNetPay = totalNetPay.add(line.getNetPay());
            totalPresentDays = totalPresentDays.add(line.getPresentDays());
            totalOtHours = totalOtHours.add(line.getOvertimeHours()).add(line.getDoubleOtHours());
        }

        // Update run totals
        run.setTotalEmployees(employees.size());
        run.setTotalBasePay(totalBasePay);
        run.setTotalOvertimePay(totalOvertimePay);
        run.setTotalDeductions(totalDeductions);
        run.setTotalNetPay(totalNetPay);
        run.setTotalPresentDays(totalPresentDays);
        run.setTotalOvertimeHours(totalOtHours);
        run.setTotalAmount(totalNetPay);
        if (run.getRunDate() == null) {
            LocalDate runDate = run.getPeriodEnd() != null ? run.getPeriodEnd() : run.getPeriodStart();
            run.setRunDate(runDate != null ? runDate : companyClock.today(company));
        }
        run.setProcessedBy(getCurrentUser());
        run.setStatus(PayrollRun.PayrollStatus.CALCULATED);

        payrollRunRepository.save(run);
        return toDto(run);
    }

    /**
     * Calculate pay for a single employee
     */
    private PayrollRunLine calculateEmployeePay(PayrollRun run, Employee employee) {
        PayrollRunLine line = new PayrollRunLine();
        line.setPayrollRun(run);
        line.setEmployee(employee);
        line.setName(employee.getFullName());

        // Get attendance for period
        List<Attendance> attendance = attendanceRepository.findByEmployeeAndAttendanceDateBetween(
            employee, run.getPeriodStart(), run.getPeriodEnd());

        // Calculate attendance summary
        BigDecimal presentDays = BigDecimal.ZERO;
        BigDecimal halfDays = BigDecimal.ZERO;
        BigDecimal absentDays = BigDecimal.ZERO;
        BigDecimal leaveDays = BigDecimal.ZERO;
        BigDecimal holidayDays = BigDecimal.ZERO;
        BigDecimal regularHours = BigDecimal.ZERO;
        BigDecimal overtimeHours = BigDecimal.ZERO;
        BigDecimal doubleOtHours = BigDecimal.ZERO;

        for (Attendance att : attendance) {
            switch (att.getStatus()) {
                case PRESENT -> presentDays = presentDays.add(BigDecimal.ONE);
                case HALF_DAY -> halfDays = halfDays.add(BigDecimal.ONE);
                case ABSENT -> absentDays = absentDays.add(BigDecimal.ONE);
                case LEAVE -> leaveDays = leaveDays.add(BigDecimal.ONE);
                case HOLIDAY -> holidayDays = holidayDays.add(BigDecimal.ONE);
                case WEEKEND -> {} // Skip weekends
            }
            if (att.getRegularHours() != null) {
                regularHours = regularHours.add(att.getRegularHours());
            }
            if (att.getOvertimeHours() != null) {
                overtimeHours = overtimeHours.add(att.getOvertimeHours());
            }
            if (att.getDoubleOvertimeHours() != null) {
                doubleOtHours = doubleOtHours.add(att.getDoubleOvertimeHours());
            }
        }

        line.setPresentDays(presentDays);
        line.setHalfDays(halfDays);
        line.setAbsentDays(absentDays);
        line.setLeaveDays(leaveDays);
        line.setHolidayDays(holidayDays);
        line.setRegularHours(regularHours);
        line.setOvertimeHours(overtimeHours);
        line.setDoubleOtHours(doubleOtHours);

        // Calculate rates
        BigDecimal dailyRate = employee.getDailyRate();
        if (dailyRate == null) {
            dailyRate = BigDecimal.ZERO;
        }
        BigDecimal dailyWage = employee.getDailyWage() != null ? employee.getDailyWage() : dailyRate;
        BigDecimal hourlyRate = dailyRate.divide(employee.getStandardHoursPerDay(), 2, RoundingMode.HALF_UP);
        line.setDailyWage(dailyWage);
        line.setDailyRate(dailyRate);
        line.setHourlyRate(hourlyRate);
        line.setOtRateMultiplier(employee.getOvertimeRateMultiplier());
        line.setDoubleOtMultiplier(employee.getDoubleOtRateMultiplier());

        // Calculate base pay
        BigDecimal effectiveDays = presentDays.add(halfDays.multiply(new BigDecimal("0.5")));
        line.setDaysWorked(effectiveDays.setScale(0, RoundingMode.HALF_UP).intValue());
        BigDecimal basePay = dailyRate.multiply(effectiveDays);
        line.setBasePay(basePay);

        // Calculate overtime pay
        BigDecimal otRate = hourlyRate.multiply(employee.getOvertimeRateMultiplier());
        BigDecimal doubleOtRate = hourlyRate.multiply(employee.getDoubleOtRateMultiplier());
        BigDecimal overtimePay = otRate.multiply(overtimeHours).add(doubleOtRate.multiply(doubleOtHours));
        line.setOvertimePay(overtimePay);

        // Calculate holiday pay (if worked on holidays)
        BigDecimal holidayPay = dailyRate.multiply(holidayDays);
        line.setHolidayPay(holidayPay);

        // Calculate gross pay
        BigDecimal grossPay = basePay.add(overtimePay).add(holidayPay);
        line.setGrossPay(grossPay);

        // Calculate deductions
        BigDecimal advanceDeduction = calculateAdvanceDeduction(grossPay, employee);
        line.setAdvanceDeduction(advanceDeduction);
        line.setAdvances(advanceDeduction);

        line.setPfDeduction(BigDecimal.ZERO);

        BigDecimal totalDeductions = advanceDeduction;
        line.setTotalDeductions(totalDeductions);

        // Calculate net pay
        BigDecimal netPay = grossPay.subtract(totalDeductions);
        line.setNetPay(netPay);
        line.setLineTotal(netPay);

        return line;
    }

    /**
     * Approve a calculated payroll run
     */
    @Transactional
    public PayrollRunDto approvePayroll(Long payrollRunId) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));

        if (run.getStatus() != PayrollRun.PayrollStatus.CALCULATED) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Can only approve payroll in CALCULATED status")
                    .withDetail("payrollRunId", payrollRunId)
                    .withDetail("currentStatus", run.getStatus().name());
        }
        if (payrollRunLineRepository.findByPayrollRun(run).isEmpty()) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Cannot approve payroll run with no calculated lines")
                    .withDetail("payrollRunId", payrollRunId);
        }

        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        run.setApprovedBy(getCurrentUser());
        run.setApprovedAt(CompanyTime.now(company));
        run.setProcessedBy(getCurrentUser());

        payrollRunRepository.save(run);
        return toDto(run);
    }

    /**
     * Post payroll to accounting (create journal entries)
     */
    @Transactional
    public PayrollRunDto postPayrollToAccounting(Long payrollRunId) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = companyEntityLookup.lockPayrollRun(company, payrollRunId);
        boolean hasPostingJournalLink = hasPostingJournalLink(run);
        boolean statusPosted = run.getStatus() == PayrollRun.PayrollStatus.POSTED;

        if (statusPosted && !hasPostingJournalLink) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll run is POSTED but missing posting journal linkage")
                    .withDetail("payrollRunId", payrollRunId)
                    .withDetail("currentStatus", run.getStatus().name())
                    .withDetail("invariant", "posted_requires_journal_link");
        }

        if (!statusPosted && !hasPostingJournalLink && run.getStatus() != PayrollRun.PayrollStatus.APPROVED) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Can only post approved payroll")
                    .withDetail("payrollRunId", payrollRunId)
                    .withDetail("currentStatus", run.getStatus().name());
        }

        // Find required accounts
        Account salaryExpenseAccount = findAccountByCode(company, "SALARY-EXP");
        Account wageExpenseAccount = findAccountByCode(company, "WAGE-EXP");
        Account salaryPayableAccount = findAccountByCode(company, "SALARY-PAYABLE");

        // Load payroll lines to calculate totals
        List<PayrollRunLine> runLines = payrollRunLineRepository.findByPayrollRun(run);
        if (runLines.isEmpty()) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll run has no calculated lines; run calculate before posting")
                    .withDetail("payrollRunId", payrollRunId);
        }
        boolean hasUnsupportedDeductions = runLines.stream().anyMatch(line ->
                hasPositive(line.getPfDeduction())
                        || hasPositive(line.getTaxDeduction())
                        || hasPositive(line.getOtherDeductions()));
        if (hasUnsupportedDeductions) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Payroll statutory deductions (PF/tax/other) are not supported in runs. " +
                            "Only advance deductions are applied; use accounting payroll payments for statutory withholdings.");
        }

        // Calculate totals from run lines
        BigDecimal totalGrossPay = runLines.stream()
            .map(PayrollRunLine::getGrossPay)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalGrossPay.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll run total gross pay is zero; nothing to post")
                    .withDetail("payrollRunId", payrollRunId);
        }

        BigDecimal totalAdvances = runLines.stream()
            .map(PayrollRunLine::getAdvanceDeduction)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Account advanceAccount = null;
        if (totalAdvances.compareTo(BigDecimal.ZERO) > 0) {
            advanceAccount = findAccountByCode(company, "EMP-ADV");
        }

        // Salary payable is net of advances (advances are cleared via the advance account)
        BigDecimal salaryPayableAmount = totalGrossPay.subtract(totalAdvances);

        // Build journal entry
        // Debit: Expense (gross pay)
        // Credit: Salary Payable (net pay)
        // Credit: Employee Advances (advance recovery)
        List<JournalLineRequest> lines = new ArrayList<>();

        Account expenseAccount = run.getRunType() == PayrollRun.RunType.MONTHLY 
            ? salaryExpenseAccount : wageExpenseAccount;
        lines.add(new JournalLineRequest(expenseAccount.getId(), "Payroll expense", totalGrossPay, BigDecimal.ZERO));

        lines.add(new JournalLineRequest(salaryPayableAccount.getId(), "Payroll payable", BigDecimal.ZERO, salaryPayableAmount));
        if (advanceAccount != null) {
            lines.add(new JournalLineRequest(advanceAccount.getId(), "Advance recovery", BigDecimal.ZERO, totalAdvances));
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
        JournalEntryDto journal = accountingFacade.postPayrollRun(runNumber, run.getId(), postingDate, memo, lines);

        if (hasPostingJournalLink && run.getJournalEntryId() != null && !run.getJournalEntryId().equals(journal.id())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
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
            run.setJournalEntry(companyEntityLookup.requireJournalEntry(company, journal.id()));
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
            // Link attendance records to this payroll run
            for (PayrollRunLine line : runLines) {
                attendanceRepository.findByEmployeeAndAttendanceDateBetween(
                    line.getEmployee(), run.getPeriodStart(), run.getPeriodEnd()
                ).forEach(att -> {
                    att.setPayrollRunId(run.getId());
                    attendanceRepository.save(att);
                });
            }
        }

        if (updated) {
            payrollRunRepository.save(run);
        }
        if (!statusPosted) {
            Map<String, String> auditMetadata = requiredPayrollPostedAuditMetadata(
                    run,
                    journal,
                    postingDate,
                    totalGrossPay,
                    totalAdvances,
                    salaryPayableAmount);
            auditService.logSuccess(AuditEvent.PAYROLL_POSTED, auditMetadata);
        }
        return toDto(run);
    }

    /**
     * Mark payroll as paid
     */
    @Transactional
    public PayrollRunDto markAsPaid(Long payrollRunId, String paymentReference) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = companyEntityLookup.lockPayrollRun(company, payrollRunId);

        if (run.getPaymentJournalEntryId() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll payment journal is required before marking payroll as PAID")
                    .withDetail("canonicalPath", PAYROLL_PAYMENTS_CANONICAL_PATH);
        }
        var paymentJournal = companyEntityLookup.requireJournalEntry(company, run.getPaymentJournalEntryId());
        String canonicalPaymentReference = paymentJournal.getReferenceNumber();
        if (!StringUtils.hasText(canonicalPaymentReference)) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll payment journal reference is missing; re-record payment through accounting payroll payments")
                    .withDetail("payrollRunId", payrollRunId)
                    .withDetail("paymentJournalEntryId", run.getPaymentJournalEntryId())
                    .withDetail("canonicalPath", PAYROLL_PAYMENTS_CANONICAL_PATH);
        }
        canonicalPaymentReference = canonicalPaymentReference.trim();

        if (run.getStatus() != PayrollRun.PayrollStatus.POSTED
                && run.getStatus() != PayrollRun.PayrollStatus.PAID) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Can only mark posted payroll as paid");
        }

        // Update all lines to paid
        List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
        List<PayrollRunLine> dirtyLines = new ArrayList<>();
        for (PayrollRunLine line : lines) {
            if (line.getPaymentStatus() == PayrollRunLine.PaymentStatus.PAID) {
                continue;
            }
            line.setPaymentStatus(PayrollRunLine.PaymentStatus.PAID);
            line.setPaymentReference(canonicalPaymentReference);
            dirtyLines.add(line);
            
            // Deduct advance from employee if applicable
            BigDecimal advances = line.getAdvances() != null ? line.getAdvances() : BigDecimal.ZERO;
            if (advances.compareTo(BigDecimal.ZERO) > 0) {
                Employee emp = line.getEmployee();
                BigDecimal currentBalance = emp.getAdvanceBalance() != null ? emp.getAdvanceBalance() : BigDecimal.ZERO;
                BigDecimal newBalance = currentBalance.subtract(advances);
                if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                    newBalance = BigDecimal.ZERO;
                }
                if (newBalance.compareTo(currentBalance) != 0) {
                    emp.setAdvanceBalance(newBalance);
                    employeeRepository.save(emp);
                }
            }
        }
        if (!dirtyLines.isEmpty()) {
            payrollRunLineRepository.saveAll(dirtyLines);
        }

        if (run.getStatus() != PayrollRun.PayrollStatus.PAID) {
            run.setStatus(PayrollRun.PayrollStatus.PAID);
            payrollRunRepository.save(run);
        }
        return toDto(run);
    }

    // ===== QUERIES =====

    public List<PayrollRunDto> listPayrollRuns() {
        Company company = companyContextService.requireCurrentCompany();
        return payrollRunRepository.findByCompanyOrderByCreatedAtDesc(company)
            .stream().map(this::toDto).toList();
    }

    public List<PayrollRunDto> listPayrollRunsByType(PayrollRun.RunType runType) {
        Company company = companyContextService.requireCurrentCompany();
        return payrollRunRepository.findByCompanyAndRunTypeOrderByCreatedAtDesc(company, runType)
            .stream().map(this::toDto).toList();
    }

    public PayrollRunDto getPayrollRun(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, id)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));
        return toDto(run);
    }

    public List<PayrollRunLineDto> getPayrollRunLines(Long payrollRunId) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));
        return payrollRunLineRepository.findByPayrollRunWithEmployeeOrderByEmployeeFirstNameAsc(run)
            .stream().map(this::toLineDto).toList();
    }

    private BigDecimal calculateAdvanceDeduction(BigDecimal grossPay, Employee employee) {
        if (employee == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal balance = employee.getAdvanceBalance();
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal cap = grossPay.multiply(ADVANCE_DEDUCTION_CAP).setScale(2, RoundingMode.HALF_UP);
        return balance.min(cap);
    }

    private boolean hasPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
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

    private Map<String, String> requiredPayrollPostedAuditMetadata(PayrollRun run,
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

    private String requiredAuditMetadataValue(String key, Object value) {
        if (value == null) {
            throw missingPayrollPostedMetadataException(key);
        }
        String normalized = value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
        if (!StringUtils.hasText(normalized)) {
            throw missingPayrollPostedMetadataException(key);
        }
        return normalized;
    }

    private ApplicationException missingPayrollPostedMetadataException(String key) {
        return new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                "Payroll posting audit metadata is missing required key: " + key)
                .withDetail("auditEvent", AuditEvent.PAYROLL_POSTED.name())
                .withDetail("metadataKey", key);
    }

    /**
     * Get what-to-pay summary for current week (labourers)
     */
    public WeeklyPaySummaryDto getWeeklyPaySummary(LocalDate weekEndingDate) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate weekStart = weekEndingDate.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekEndingDate.with(DayOfWeek.SATURDAY);

        List<Employee> labourers = employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
            company, Employee.EmployeeType.LABOUR, "ACTIVE");
        List<Attendance> attendance = attendanceRepository.findByEmployeeTypeAndStatusAndDateRange(
            company, Employee.EmployeeType.LABOUR, "ACTIVE", weekStart, weekEnd);

        List<EmployeeWeeklyPayDto> employeePay = new ArrayList<>();
        BigDecimal totalBasePay = BigDecimal.ZERO;
        BigDecimal totalOtPay = BigDecimal.ZERO;
        BigDecimal totalNetPay = BigDecimal.ZERO;

        Map<Long, BigDecimal> presentDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> holidayDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> overtimeHoursByEmployee = new HashMap<>();
        Map<Long, BigDecimal> doubleOtHoursByEmployee = new HashMap<>();
        for (Attendance att : attendance) {
            Long employeeId = att.getEmployee().getId();
            if (att.getStatus() == Attendance.AttendanceStatus.PRESENT) {
                presentDaysByEmployee.merge(employeeId, BigDecimal.ONE, BigDecimal::add);
            } else if (att.getStatus() == Attendance.AttendanceStatus.HALF_DAY) {
                presentDaysByEmployee.merge(employeeId, new BigDecimal("0.5"), BigDecimal::add);
            } else if (att.getStatus() == Attendance.AttendanceStatus.HOLIDAY) {
                holidayDaysByEmployee.merge(employeeId, BigDecimal.ONE, BigDecimal::add);
            }
            if (att.getOvertimeHours() != null) {
                overtimeHoursByEmployee.merge(employeeId, att.getOvertimeHours(), BigDecimal::add);
            }
            if (att.getDoubleOvertimeHours() != null) {
                doubleOtHoursByEmployee.merge(employeeId, att.getDoubleOvertimeHours(), BigDecimal::add);
            }
        }

        for (Employee emp : labourers) {
            BigDecimal presentDays = presentDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal otHours = overtimeHoursByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal doubleOtHours = doubleOtHoursByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal holidayDays = holidayDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);

            BigDecimal baseDays = presentDays.add(holidayDays);
            BigDecimal basePay = emp.getDailyRate().multiply(baseDays);
            BigDecimal hourlyRate = emp.getDailyRate().divide(
                    emp.getStandardHoursPerDay(), 2, RoundingMode.HALF_UP);
            BigDecimal otPay = hourlyRate.multiply(
                    emp.getOvertimeRateMultiplier()).multiply(otHours);
            BigDecimal doubleOtPay = hourlyRate.multiply(
                    emp.getDoubleOtRateMultiplier()).multiply(doubleOtHours);
            BigDecimal netPay = basePay.add(otPay).add(doubleOtPay);

            BigDecimal daysWorkedExact = presentDays;
            int daysWorked = daysWorkedExact.setScale(0, RoundingMode.HALF_UP).intValue();
            employeePay.add(new EmployeeWeeklyPayDto(
                emp.getId(), emp.getFullName(), emp.getDailyRate(),
                daysWorked, daysWorkedExact, otHours, basePay, otPay, netPay
            ));

            totalBasePay = totalBasePay.add(basePay);
            totalOtPay = totalOtPay.add(otPay).add(doubleOtPay);
            totalNetPay = totalNetPay.add(netPay);
        }

        return new WeeklyPaySummaryDto(weekStart, weekEnd, labourers.size(),
            totalBasePay, totalOtPay, totalNetPay, employeePay);
    }

    /**
     * Get what-to-pay summary for current month (staff)
     */
    public MonthlyPaySummaryDto getMonthlyPaySummary(int year, int month) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());

        List<Employee> staff = employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
            company, Employee.EmployeeType.STAFF, "ACTIVE");
        List<Attendance> attendance = attendanceRepository.findByEmployeeTypeAndStatusAndDateRange(
            company, Employee.EmployeeType.STAFF, "ACTIVE", monthStart, monthEnd);

        List<EmployeeMonthlyPayDto> employeePay = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;

        Map<Long, BigDecimal> presentDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> halfDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> absentDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> holidayDaysByEmployee = new HashMap<>();
        Map<Long, BigDecimal> leaveDaysByEmployee = new HashMap<>();
        for (Attendance att : attendance) {
            Long employeeId = att.getEmployee().getId();
            switch (att.getStatus()) {
                case PRESENT -> presentDaysByEmployee.merge(
                        employeeId, BigDecimal.ONE, BigDecimal::add);
                case HALF_DAY -> presentDaysByEmployee.merge(
                        employeeId, new BigDecimal("0.5"), BigDecimal::add);
                case ABSENT -> absentDaysByEmployee.merge(
                        employeeId, BigDecimal.ONE, BigDecimal::add);
                case HOLIDAY -> holidayDaysByEmployee.merge(
                        employeeId, BigDecimal.ONE, BigDecimal::add);
                case LEAVE -> leaveDaysByEmployee.merge(
                        employeeId, BigDecimal.ONE, BigDecimal::add);
                default -> {
                }
            }
            if (att.getStatus() == Attendance.AttendanceStatus.HALF_DAY) {
                halfDaysByEmployee.merge(employeeId, BigDecimal.ONE, BigDecimal::add);
            }
        }

        for (Employee emp : staff) {
            BigDecimal presentDays = presentDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal halfDays = halfDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal absentDays = absentDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);
            BigDecimal holidayDays = holidayDaysByEmployee.getOrDefault(
                    emp.getId(), BigDecimal.ZERO);

            BigDecimal dailyRate = emp.getDailyRate();
            BigDecimal monthlySalary = emp.getMonthlySalary();
            BigDecimal grossPay;
            if (monthlySalary != null && monthlySalary.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deductionDays = absentDays;
                if (halfDays.compareTo(BigDecimal.ZERO) > 0) {
                    deductionDays = deductionDays.add(halfDays.multiply(new BigDecimal("0.5")));
                }
                BigDecimal absenceDeduction = dailyRate.multiply(deductionDays);
                grossPay = monthlySalary.subtract(absenceDeduction);
                if (grossPay.compareTo(BigDecimal.ZERO) < 0) {
                    grossPay = BigDecimal.ZERO;
                }
            } else {
                BigDecimal baseDays = presentDays;
                BigDecimal basePay = dailyRate.multiply(baseDays);
                BigDecimal holidayPay = dailyRate.multiply(holidayDays);
                grossPay = basePay.add(holidayPay);
            }

            // Statutory deductions are not applied in the summary flow
            BigDecimal pfDeduction = BigDecimal.ZERO;
            BigDecimal totalDed = BigDecimal.ZERO;
            BigDecimal netPay = grossPay;

            employeePay.add(new EmployeeMonthlyPayDto(
                emp.getId(), emp.getFullName(), emp.getMonthlySalary(),
                presentDays.setScale(0, RoundingMode.HALF_UP).intValue(),
                absentDays.setScale(0, RoundingMode.HALF_UP).intValue(),
                presentDays, halfDays, absentDays,
                grossPay, pfDeduction, netPay
            ));

            totalGross = totalGross.add(grossPay);
            totalDeductions = totalDeductions.add(totalDed);
            totalNet = totalNet.add(netPay);
        }

        return new MonthlyPaySummaryDto(year, month, staff.size(),
            totalGross, totalDeductions, totalNet, employeePay);
    }

    // ===== HELPER METHODS =====

    static String buildIdempotencyKey(PayrollRun.RunType runType, LocalDate periodStart, LocalDate periodEnd) {
        return "PAYROLL:%s:%s:%s".formatted(
                runType.name(),
                periodStart,
                periodEnd
        );
    }

    static String buildRunSignature(PayrollRun.RunType runType,
                                    LocalDate periodStart,
                                    LocalDate periodEnd,
                                    String remarks) {
        String normalizedRemarks = StringUtils.hasText(remarks) ? remarks.trim() : "";
        String signature = "%s|%s|%s|%s".formatted(runType.name(), periodStart, periodEnd, normalizedRemarks);
        return DigestUtils.sha256Hex(signature);
    }

    static String buildRunSignature(PayrollRun run) {
        if (run == null || run.getRunType() == null || run.getPeriodStart() == null || run.getPeriodEnd() == null) {
            return null;
        }
        return buildRunSignature(run.getRunType(), run.getPeriodStart(), run.getPeriodEnd(), run.getNotes());
    }

    private void assertRunSignatureMatches(PayrollRun run, String expectedSignature, String idempotencyKey) {
        if (run == null) {
            return;
        }
        if (run.getRunType() == null || run.getPeriodStart() == null || run.getPeriodEnd() == null) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Existing payroll run is missing canonical period fields")
                    .withDetail("payrollRunId", run.getId())
                    .withDetail("idempotencyKey", idempotencyKey);
        }
        String storedSignature = run.getIdempotencyHash();
        if (!StringUtils.hasText(storedSignature)) {
            String derivedSignature = buildRunSignature(run);
            if (StringUtils.hasText(derivedSignature) && !derivedSignature.equals(expectedSignature)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey);
            }
            return;
        }
        if (!storedSignature.equals(expectedSignature)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used with different payload")
                    .withDetail("idempotencyKey", idempotencyKey);
        }
    }

    private void ensureIdempotencyMetadata(PayrollRun run, String idempotencyKey, String signature) {
        boolean changed = false;
        if (!StringUtils.hasText(run.getIdempotencyKey())) {
            run.setIdempotencyKey(idempotencyKey);
            changed = true;
        }
        if (!StringUtils.hasText(run.getIdempotencyHash()) && StringUtils.hasText(signature)) {
            run.setIdempotencyHash(signature);
            changed = true;
        }
        if (changed) {
            payrollRunRepository.save(run);
        }
    }

    private String generateRunNumber(Company company, PayrollRun.RunType runType, LocalDate periodStart) {
        String prefix = runType == PayrollRun.RunType.WEEKLY ? "PR-W" : "PR-M";
        int year = periodStart.getYear();
        
        if (runType == PayrollRun.RunType.WEEKLY) {
            int weekNum = periodStart.get(WeekFields.ISO.weekOfYear());
            return String.format("%s-%d-W%02d", prefix, year, weekNum);
        } else {
            return String.format("%s-%d-%02d", prefix, year, periodStart.getMonthValue());
        }
    }

    private Account findAccountByCode(Company company, String code) {
        String normalizedCode = StringUtils.hasText(code) ? code.trim().toUpperCase(Locale.ROOT) : "";
        AccountType expectedType = REQUIRED_PAYROLL_ACCOUNT_TYPES.get(normalizedCode);
        String expectedTypeName = expectedType != null ? expectedType.name() : "UNKNOWN";

        return accountRepository.findByCompanyAndCodeIgnoreCase(company, normalizedCode)
                .orElseThrow(() -> {
                    ApplicationException exception = new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Required payroll account not found: " + normalizedCode
                                    + " (expected type: " + expectedTypeName + "). "
                                    + "Provision this account in Chart of Accounts before posting payroll.")
                            .withDetail("accountCode", normalizedCode)
                            .withDetail("expectedAccountType", expectedTypeName)
                            .withDetail("requiredPayrollAccounts", REQUIRED_PAYROLL_ACCOUNTS)
                            .withDetail("migrationSet", PAYROLL_MIGRATION_SET)
                            .withDetail("manualProvisioningRequired", true)
                            .withDetail("canonicalPath", PAYROLL_ACCOUNTS_CANONICAL_PATH);

                    return exception;
                });
    }

    private String getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "SYSTEM";
    }

    private PayrollRunDto toDto(PayrollRun run) {
        return new PayrollRunDto(
            run.getId(), run.getPublicId(), run.getRunNumber(),
            run.getRunType().name(), run.getPeriodStart(), run.getPeriodEnd(),
            run.getStatus().name(), run.getTotalEmployees(),
            run.getTotalPresentDays(), run.getTotalOvertimeHours(),
            run.getTotalBasePay(), run.getTotalOvertimePay(),
            run.getTotalDeductions(), run.getTotalNetPay(),
            run.getJournalEntryId(), run.getCreatedBy(), run.getCreatedAt(),
            run.getApprovedBy(), run.getApprovedAt(),
            run.getPostedBy(), run.getPostedAt(), run.getRemarks()
        );
    }

    private PayrollRunLineDto toLineDto(PayrollRunLine line) {
        Employee emp = line.getEmployee();
        return new PayrollRunLineDto(
            line.getId(), emp.getId(), emp.getFullName(), emp.getEmployeeType().name(),
            line.getPresentDays(), line.getHalfDays(), line.getAbsentDays(),
            line.getLeaveDays(), line.getHolidayDays(),
            line.getRegularHours(), line.getOvertimeHours(), line.getDoubleOtHours(),
            line.getDailyRate(), line.getHourlyRate(),
            line.getBasePay(), line.getOvertimePay(), line.getHolidayPay(), line.getGrossPay(),
            line.getAdvanceDeduction(), line.getPfDeduction(), line.getTotalDeductions(),
            line.getNetPay(), line.getPaymentStatus().name(), line.getPaymentReference()
        );
    }

    // ===== DTOs =====

    public record CreatePayrollRunRequest(
        PayrollRun.RunType runType,
        LocalDate periodStart,
        LocalDate periodEnd,
        String remarks
    ) {}

    public record PayrollRunDto(
        Long id, UUID publicId, String runNumber, String runType,
        LocalDate periodStart, LocalDate periodEnd, String status,
        Integer totalEmployees, BigDecimal totalPresentDays, BigDecimal totalOvertimeHours,
        BigDecimal totalBasePay, BigDecimal totalOvertimePay,
        BigDecimal totalDeductions, BigDecimal totalNetPay,
        Long journalEntryId, String createdBy, Instant createdAt,
        String approvedBy, Instant approvedAt, String postedBy, Instant postedAt,
        String remarks
    ) {}

    public record PayrollRunLineDto(
        Long id, Long employeeId, String employeeName, String employeeType,
        BigDecimal presentDays, BigDecimal halfDays, BigDecimal absentDays,
        BigDecimal leaveDays, BigDecimal holidayDays,
        BigDecimal regularHours, BigDecimal overtimeHours, BigDecimal doubleOtHours,
        BigDecimal dailyRate, BigDecimal hourlyRate,
        BigDecimal basePay, BigDecimal overtimePay, BigDecimal holidayPay, BigDecimal grossPay,
        BigDecimal advanceDeduction, BigDecimal pfDeduction, BigDecimal totalDeductions,
        BigDecimal netPay, String paymentStatus, String paymentReference
    ) {}

    public record WeeklyPaySummaryDto(
        LocalDate weekStart, LocalDate weekEnd, int totalLabourers,
        BigDecimal totalBasePay, BigDecimal totalOvertimePay, BigDecimal totalNetPay,
        List<EmployeeWeeklyPayDto> employees
    ) {}

    public record EmployeeWeeklyPayDto(
        Long employeeId, String name, BigDecimal dailyRate,
        int daysWorked, BigDecimal daysWorkedExact, BigDecimal overtimeHours,
        BigDecimal basePay, BigDecimal overtimePay, BigDecimal netPay
    ) {}

    public record MonthlyPaySummaryDto(
        int year, int month, int totalStaff,
        BigDecimal totalGrossPay, BigDecimal totalDeductions, BigDecimal totalNetPay,
        List<EmployeeMonthlyPayDto> employees
    ) {}

    public record EmployeeMonthlyPayDto(
        Long employeeId, String name, BigDecimal monthlySalary,
        int presentDays, int absentDays,
        BigDecimal presentDaysExact, BigDecimal halfDaysExact, BigDecimal absentDaysExact,
        BigDecimal grossPay, BigDecimal pfDeduction, BigDecimal netPay
    ) {}
}
