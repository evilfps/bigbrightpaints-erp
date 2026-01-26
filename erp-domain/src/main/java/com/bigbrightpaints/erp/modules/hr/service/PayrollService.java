package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.hr.domain.*;
import jakarta.transaction.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunLineRepository payrollRunLineRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final AccountingService accountingService;
    private final AccountRepository accountRepository;
    private final CompanyContextService companyContextService;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private final AuditService auditService;

    public PayrollService(PayrollRunRepository payrollRunRepository,
                          PayrollRunLineRepository payrollRunLineRepository,
                          EmployeeRepository employeeRepository,
                          AttendanceRepository attendanceRepository,
                          AccountingService accountingService,
                          AccountRepository accountRepository,
                          CompanyContextService companyContextService,
                          CompanyEntityLookup companyEntityLookup,
                          CompanyClock companyClock,
                          AuditService auditService) {
        this.payrollRunRepository = payrollRunRepository;
        this.payrollRunLineRepository = payrollRunLineRepository;
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
        this.accountingService = accountingService;
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
        String currentUser = getCurrentUser();

        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunType(request.runType());
        run.setPeriodStart(request.periodStart());
        run.setPeriodEnd(request.periodEnd());
        LocalDate runDate = request.periodEnd() != null ? request.periodEnd() : request.periodStart();
        run.setRunDate(runDate != null ? runDate : LocalDate.now());
        run.setRunNumber(generateRunNumber(company, request.runType(), request.periodStart()));
        run.setCreatedBy(currentUser);
        run.setRemarks(request.remarks());

        run = payrollRunRepository.save(run);
        return toDto(run);
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
            throw new IllegalStateException("Can only calculate payroll in DRAFT status");
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
            throw new IllegalStateException("Can only approve payroll in CALCULATED status");
        }

        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        run.setApprovedBy(getCurrentUser());
        run.setApprovedAt(Instant.now());

        payrollRunRepository.save(run);
        return toDto(run);
    }

    /**
     * Post payroll to accounting (create journal entries)
     */
    @Transactional
    public PayrollRunDto postPayrollToAccounting(Long payrollRunId) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));

        if (run.getStatus() != PayrollRun.PayrollStatus.APPROVED) {
            throw new IllegalStateException("Can only post approved payroll");
        }

        // Find required accounts
        Account salaryExpenseAccount = findAccountByCode(company, "SALARY-EXP");
        Account wageExpenseAccount = findAccountByCode(company, "WAGE-EXP");
        Account salaryPayableAccount = findAccountByCode(company, "SALARY-PAYABLE");

        // Load payroll lines to calculate totals
        List<PayrollRunLine> runLines = payrollRunLineRepository.findByPayrollRun(run);
        boolean hasUnsupportedDeductions = runLines.stream().anyMatch(line ->
                hasPositive(line.getPfDeduction())
                        || hasPositive(line.getTaxDeduction())
                        || hasPositive(line.getOtherDeductions()));
        if (hasUnsupportedDeductions) {
            throw new IllegalStateException("Payroll statutory deductions (PF/tax/other) are not supported in runs. " +
                    "Only advance deductions are applied; use accounting payroll payments for statutory withholdings.");
        }

        // Calculate totals from run lines
        BigDecimal totalGrossPay = runLines.stream()
            .map(PayrollRunLine::getGrossPay)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

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
        JournalEntryRequest journalRequest = new JournalEntryRequest(
            "PAYROLL-" + run.getRunNumber(),    // referenceNumber
            postingDate,                         // entryDate
            "Payroll - " + run.getRunNumber(),   // memo
            null,                                // dealerId
            null,                                // supplierId
            false,                               // adminOverride
            lines                                // lines
        );

        JournalEntryDto journal = accountingService.createJournalEntry(journalRequest);

        // Update run status
        run.setJournalEntryId(journal.id());
        run.setJournalEntry(companyEntityLookup.requireJournalEntry(company, journal.id()));
        run.setStatus(PayrollRun.PayrollStatus.POSTED);
        run.setPostedBy(getCurrentUser());
        run.setPostedAt(Instant.now());

        // Link attendance records to this payroll run
        for (PayrollRunLine line : runLines) {
            attendanceRepository.findByEmployeeAndAttendanceDateBetween(
                line.getEmployee(), run.getPeriodStart(), run.getPeriodEnd()
            ).forEach(att -> {
                att.setPayrollRunId(run.getId());
                attendanceRepository.save(att);
            });
        }

        payrollRunRepository.save(run);
        Map<String, String> auditMetadata = new HashMap<>();
        if (run.getId() != null) {
            auditMetadata.put("payrollRunId", run.getId().toString());
        }
        if (run.getRunNumber() != null) {
            auditMetadata.put("runNumber", run.getRunNumber());
        }
        if (run.getRunType() != null) {
            auditMetadata.put("runType", run.getRunType().name());
        }
        if (run.getPeriodStart() != null) {
            auditMetadata.put("periodStart", run.getPeriodStart().toString());
        }
        if (run.getPeriodEnd() != null) {
            auditMetadata.put("periodEnd", run.getPeriodEnd().toString());
        }
        if (journal != null && journal.id() != null) {
            auditMetadata.put("journalEntryId", journal.id().toString());
        }
        if (postingDate != null) {
            auditMetadata.put("postingDate", postingDate.toString());
        }
        auditMetadata.put("totalGrossPay", totalGrossPay.toPlainString());
        auditMetadata.put("totalAdvances", totalAdvances.toPlainString());
        auditMetadata.put("netPayable", salaryPayableAmount.toPlainString());
        auditService.logSuccess(AuditEvent.PAYROLL_POSTED, auditMetadata);
        return toDto(run);
    }

    /**
     * Mark payroll as paid
     */
    @Transactional
    public PayrollRunDto markAsPaid(Long payrollRunId, String paymentReference) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));

        if (run.getStatus() != PayrollRun.PayrollStatus.POSTED) {
            throw new IllegalStateException("Can only mark posted payroll as paid");
        }

        // Update all lines to paid
        List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
        for (PayrollRunLine line : lines) {
            line.setPaymentStatus(PayrollRunLine.PaymentStatus.PAID);
            line.setPaymentReference(paymentReference);
            
            // Deduct advance from employee if applicable
            if (line.getAdvances().compareTo(BigDecimal.ZERO) > 0) {
                Employee emp = line.getEmployee();
                emp.setAdvanceBalance(emp.getAdvanceBalance().subtract(line.getAdvances()));
                employeeRepository.save(emp);
            }
        }
        payrollRunLineRepository.saveAll(lines);

        run.setStatus(PayrollRun.PayrollStatus.PAID);
        payrollRunRepository.save(run);
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
            BigDecimal baseDays = presentDays.add(halfDays.multiply(new BigDecimal("0.5")));
            BigDecimal basePay = dailyRate.multiply(baseDays);
            BigDecimal holidayPay = dailyRate.multiply(holidayDays);
            BigDecimal grossPay = basePay.add(holidayPay);

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
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
            .orElseThrow(() -> new IllegalStateException("Account not found: " + code + 
                ". Please create this account in Chart of Accounts."));
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
