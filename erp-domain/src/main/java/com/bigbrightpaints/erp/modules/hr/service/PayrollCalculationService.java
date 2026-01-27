package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.hr.domain.*;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Auto-calculates payroll based on attendance.
 * 
 * Business Rules:
 * - Staff (MONTHLY): Salary / working days * present days
 * - Labour (WEEKLY): Daily wage * present days
 * - Half day = 0.5 day pay
 * - Weekends excluded from calculations
 * - Advance payments deducted from final amount
 * 
 * Scheduled: Every Saturday at 3:00 PM IST
 */
@Service
public class PayrollCalculationService {

    private static final Logger log = LoggerFactory.getLogger(PayrollCalculationService.class);
    private static final BigDecimal ADVANCE_DEDUCTION_CAP = new BigDecimal("0.20");

    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunLineRepository payrollRunLineRepository;
    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${erp.payroll.notification-email:mdanas7869292@gmail.com}")
    private String payrollNotificationEmail;

    @Value("${erp.mail.from-address:bigbrightpaints@gmail.com}")
    private String fromAddress;

    public PayrollCalculationService(EmployeeRepository employeeRepository,
                                     AttendanceRepository attendanceRepository,
                                     PayrollRunRepository payrollRunRepository,
                                     PayrollRunLineRepository payrollRunLineRepository,
                                     CompanyContextService companyContextService,
                                     CompanyClock companyClock,
                                     JavaMailSender mailSender,
                                     TemplateEngine templateEngine) {
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.payrollRunLineRepository = payrollRunLineRepository;
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    /**
     * Calculate weekly payroll for labourers (runs every Saturday)
     */
    @Transactional
    public PayrollSummary calculateWeeklyPayroll() {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        
        // Get the week range (Monday to Saturday of the latest completed week)
        LocalDate weekEnd = today.getDayOfWeek() == DayOfWeek.SATURDAY
                ? today
                : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        LocalDate weekStart = weekEnd.minusDays(5); // Monday

        String reference = "WEEKLY-" + weekEnd.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Optional<PayrollRun> existing = payrollRunRepository.findByCompanyAndIdempotencyKey(company, reference);
        if (existing.isPresent()) {
            return buildSummaryFromRun(existing.get());
        }
        
        log.info("Calculating weekly payroll for {} to {}", weekStart, weekEnd);
        
        // Get all active labourers
        List<Employee> labourers = employeeRepository.findByCompanyAndPaymentScheduleAndStatus(
                company, Employee.PaymentSchedule.WEEKLY, "ACTIVE");
        
        List<PayrollLineItem> lineItems = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalAdvances = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        
        for (Employee labourer : labourers) {
            PayrollLineItem item = calculateEmployeePay(company, labourer, weekStart, weekEnd);
            if (item.netPay().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            lineItems.add(item);
            totalGross = totalGross.add(item.grossPay());
            totalAdvances = totalAdvances.add(item.advanceDeduction());
            totalNet = totalNet.add(item.netPay());
        }
        
        // Create payroll run
        PayrollRun run = createPayrollRun(company, reference, PayrollRun.RunType.WEEKLY,
                weekStart, weekEnd, today, totalGross, totalAdvances, totalNet, lineItems);
        
        PayrollSummary summary = new PayrollSummary(
                run.getId(),
                reference,
                "WEEKLY",
                weekStart,
                weekEnd,
                lineItems,
                totalNet,
                nowLocal(company)
        );
        
        // Send notification email
        sendPayrollNotification(summary);
        
        return summary;
    }

    /**
     * Calculate monthly payroll for staff (runs end of month)
     */
    @Transactional
    public PayrollSummary calculateMonthlyPayroll() {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        
        // Get the month range
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());

        String reference = "MONTHLY-" + monthEnd.format(DateTimeFormatter.ofPattern("yyyyMM"));
        Optional<PayrollRun> existing = payrollRunRepository.findByCompanyAndIdempotencyKey(company, reference);
        if (existing.isPresent()) {
            return buildSummaryFromRun(existing.get());
        }
        
        log.info("Calculating monthly payroll for {} to {}", monthStart, monthEnd);
        
        // Get all active staff
        List<Employee> staff = employeeRepository.findByCompanyAndPaymentScheduleAndStatus(
                company, Employee.PaymentSchedule.MONTHLY, "ACTIVE");
        
        List<PayrollLineItem> lineItems = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalAdvances = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        
        for (Employee employee : staff) {
            PayrollLineItem item = calculateEmployeePay(company, employee, monthStart, monthEnd);
            if (item.netPay().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            lineItems.add(item);
            totalGross = totalGross.add(item.grossPay());
            totalAdvances = totalAdvances.add(item.advanceDeduction());
            totalNet = totalNet.add(item.netPay());
        }
        
        // Create payroll run
        PayrollRun run = createPayrollRun(company, reference, PayrollRun.RunType.MONTHLY,
                monthStart, monthEnd, today, totalGross, totalAdvances, totalNet, lineItems);
        
        PayrollSummary summary = new PayrollSummary(
                run.getId(),
                reference,
                "MONTHLY",
                monthStart,
                monthEnd,
                lineItems,
                totalNet,
                nowLocal(company)
        );
        
        // Send notification email
        sendPayrollNotification(summary);
        
        return summary;
    }

    /**
     * Calculate pay for a single employee based on attendance
     */
    private PayrollLineItem calculateEmployeePay(Company company, Employee employee, 
                                                  LocalDate startDate, LocalDate endDate) {
        List<Attendance> attendance = attendanceRepository.findByEmployeeAndAttendanceDateBetween(
                employee, startDate, endDate);

        BigDecimal presentDays = BigDecimal.ZERO;
        BigDecimal halfDays = BigDecimal.ZERO;
        BigDecimal leaveDays = BigDecimal.ZERO;
        BigDecimal holidayDays = BigDecimal.ZERO;
        BigDecimal overtimeHours = BigDecimal.ZERO;
        BigDecimal doubleOtHours = BigDecimal.ZERO;

        for (Attendance att : attendance) {
            switch (att.getStatus()) {
                case PRESENT -> presentDays = presentDays.add(BigDecimal.ONE);
                case HALF_DAY -> halfDays = halfDays.add(BigDecimal.ONE);
                case LEAVE -> leaveDays = leaveDays.add(BigDecimal.ONE);
                case HOLIDAY -> holidayDays = holidayDays.add(BigDecimal.ONE);
                case ABSENT, WEEKEND -> {}
            }
            if (att.getOvertimeHours() != null) {
                overtimeHours = overtimeHours.add(att.getOvertimeHours());
            }
            if (att.getDoubleOvertimeHours() != null) {
                doubleOtHours = doubleOtHours.add(att.getDoubleOvertimeHours());
            }
        }

        BigDecimal dailyRate = employee.getDailyRate() != null ? employee.getDailyRate() : BigDecimal.ZERO;
        BigDecimal hourlyRate = dailyRate.divide(employee.getStandardHoursPerDay(), 2, RoundingMode.HALF_UP);

        BigDecimal effectiveDays = presentDays.add(halfDays.multiply(new BigDecimal("0.5")));
        BigDecimal basePay = dailyRate.multiply(effectiveDays);

        BigDecimal otRate = hourlyRate.multiply(employee.getOvertimeRateMultiplier());
        BigDecimal doubleOtRate = hourlyRate.multiply(employee.getDoubleOtRateMultiplier());
        BigDecimal overtimePay = otRate.multiply(overtimeHours).add(doubleOtRate.multiply(doubleOtHours));

        BigDecimal holidayPay = dailyRate.multiply(holidayDays);
        BigDecimal grossPay = basePay.add(overtimePay).add(holidayPay);

        BigDecimal balance = Optional.ofNullable(employee.getAdvanceBalance()).orElse(BigDecimal.ZERO);
        BigDecimal advanceDeduction = balance.compareTo(BigDecimal.ZERO) > 0
                ? balance.min(grossPay.multiply(ADVANCE_DEDUCTION_CAP).setScale(2, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;

        BigDecimal totalDeductions = advanceDeduction;
        BigDecimal netPay = grossPay.subtract(totalDeductions);

        return new PayrollLineItem(
                employee.getId(),
                employee.getFullName(),
                employee.getEmployeeType().name(),
                dailyRate,
                presentDays,
                halfDays,
                overtimeHours,
                doubleOtHours,
                basePay,
                overtimePay,
                holidayPay,
                grossPay,
                advanceDeduction,
                totalDeductions,
                netPay
        );
    }

    /**
     * Preview payroll without creating a run
     */
    @Transactional(readOnly = true)
    public PayrollSummary previewPayroll(Employee.PaymentSchedule schedule) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        
        LocalDate startDate, endDate;
        String type;
        
        if (schedule == Employee.PaymentSchedule.WEEKLY) {
            endDate = today.getDayOfWeek() == DayOfWeek.SATURDAY 
                    ? today.minusDays(1) 
                    : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
            startDate = endDate.minusDays(5);
            type = "WEEKLY";
        } else {
            startDate = today.withDayOfMonth(1);
            endDate = today.with(TemporalAdjusters.lastDayOfMonth());
            type = "MONTHLY";
        }
        
        List<Employee> employees = employeeRepository.findByCompanyAndPaymentScheduleAndStatus(
                company, schedule, "ACTIVE");
        
        List<PayrollLineItem> lineItems = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalAdvances = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        
        for (Employee employee : employees) {
            PayrollLineItem item = calculateEmployeePay(company, employee, startDate, endDate);
            lineItems.add(item);
            totalGross = totalGross.add(item.grossPay());
            totalAdvances = totalAdvances.add(item.advanceDeduction());
            totalNet = totalNet.add(item.netPay());
        }
        
        return new PayrollSummary(
                null, // Not saved yet
                "PREVIEW-" + type,
                type,
                startDate,
                endDate,
                lineItems,
                totalNet,
                nowLocal(company)
        );
    }

    /**
     * Record an advance payment to an employee
     */
    @Transactional
    public void recordAdvancePayment(Long employeeId, BigDecimal amount) {
        Company company = companyContextService.requireCurrentCompany();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Advance amount must be positive");
        }
        Employee employee = employeeRepository.findByCompanyAndId(company, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        BigDecimal currentBalance = employee.getAdvanceBalance() != null ? employee.getAdvanceBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = currentBalance.add(amount);
        employee.setAdvanceBalance(newBalance);
        employeeRepository.save(employee);
        
        log.info("Recorded advance payment of {} for {}. New balance: {}", 
                amount, employee.getFullName(), newBalance);
    }

    private PayrollRun createPayrollRun(Company company, String reference, PayrollRun.RunType runType,
                                         LocalDate periodStart, LocalDate periodEnd, LocalDate runDate,
                                         BigDecimal totalGross, BigDecimal totalAdvances, BigDecimal totalNet,
                                         List<PayrollLineItem> lineItems) {
        Optional<PayrollRun> existing = payrollRunRepository.findByCompanyAndIdempotencyKey(company, reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunType(runType);
        run.setPeriodStart(periodStart);
        run.setPeriodEnd(periodEnd);
        run.setRunDate(runDate);
        run.setRunNumber(reference);
        run.setTotalAmount(totalNet);
        run.setTotalNetPay(totalNet);
        run.setTotalDeductions(totalAdvances);
        run.setStatus(PayrollRun.PayrollStatus.DRAFT);
        run.setIdempotencyKey(reference);
        run.setNotes("Auto-calculated from attendance");
        run.setTotalEmployees(lineItems.size());
        run.setApprovedBy(null);
        run.setApprovedAt(null);
        payrollRunRepository.save(run);
        
        // Create line items
        for (PayrollLineItem item : lineItems) {
            PayrollRunLine line = new PayrollRunLine();
            line.setPayrollRun(run);
            Employee employee = employeeRepository.findByCompanyAndId(company, item.employeeId())
                    .orElseThrow(() -> new IllegalStateException("Employee not found for payroll run line: " + item.employeeId()));
            line.setEmployee(employee);
            line.setName(item.employeeName());
            BigDecimal effectiveDays = item.presentDays().add(item.halfDays().multiply(new BigDecimal("0.5")));
            line.setDaysWorked(effectiveDays.setScale(0, RoundingMode.HALF_UP).intValue());
            line.setPresentDays(item.presentDays());
            line.setHalfDays(item.halfDays());
            line.setOvertimeHours(item.overtimeHours());
            line.setDoubleOtHours(item.doubleOtHours());
            line.setDailyWage(item.dailyRate());
            line.setBasePay(item.basePay());
            line.setOvertimePay(item.overtimePay());
            line.setHolidayPay(item.holidayPay());
            line.setGrossPay(item.grossPay());
            line.setAdvanceDeduction(item.advanceDeduction());
            line.setAdvances(item.advanceDeduction());
            line.setTotalDeductions(item.totalDeductions());
            line.setNetPay(item.netPay());
            line.setLineTotal(item.netPay());
            line.setNotes(item.employeeType() + " - " + item.presentDays() + " full + " + item.halfDays() + " half days");
            payrollRunLineRepository.save(line);
        }
        
        return run;
    }

    private PayrollSummary buildSummaryFromRun(PayrollRun run) {
        Company company = run.getCompany();
        List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRunWithEmployeeOrderByEmployeeFirstNameAsc(run);
        List<PayrollLineItem> lineItems = lines.stream()
                .map(this::toLineItem)
                .toList();
        BigDecimal totalNet = lineItems.stream()
                .map(PayrollLineItem::netPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime calculatedAt = run.getCreatedAt() != null
                ? LocalDateTime.ofInstant(run.getCreatedAt(), companyClock.zoneId(company))
                : nowLocal(company);
        return new PayrollSummary(
                run.getId(),
                run.getRunNumber(),
                run.getRunType() != null ? run.getRunType().name() : "UNKNOWN",
                run.getPeriodStart(),
                run.getPeriodEnd(),
                lineItems,
                totalNet,
                calculatedAt
        );
    }

    private PayrollLineItem toLineItem(PayrollRunLine line) {
        Employee employee = line.getEmployee();
        String name = line.getName() != null ? line.getName() : (employee != null ? employee.getFullName() : "Unknown");
        String type = employee != null && employee.getEmployeeType() != null
                ? employee.getEmployeeType().name()
                : "UNKNOWN";
        return new PayrollLineItem(
                employee != null ? employee.getId() : null,
                name,
                type,
                safe(line.getDailyRate()),
                safe(line.getPresentDays()),
                safe(line.getHalfDays()),
                safe(line.getOvertimeHours()),
                safe(line.getDoubleOtHours()),
                safe(line.getBasePay()),
                safe(line.getOvertimePay()),
                safe(line.getHolidayPay()),
                safe(line.getGrossPay()),
                safe(line.getAdvanceDeduction()),
                safe(line.getTotalDeductions()),
                safe(line.getNetPay())
        );
    }

    private LocalDateTime nowLocal(Company company) {
        return LocalDateTime.ofInstant(companyClock.now(company), companyClock.zoneId(company));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void sendPayrollNotification(PayrollSummary summary) {
        Company company = companyContextService.requireCurrentCompany();
        try {
            // Generate PDF
            byte[] pdfContent = generatePayrollPdf(company, summary);
            
            String subject = String.format("Payroll Payment Sheet: %s - Total ₹%,.2f", 
                    summary.reference(), summary.totalAmount());
            
            String emailBody = String.format("""
                Dear Admin,
                
                Please find attached the %s Payroll Payment Sheet for the period %s to %s.
                
                SUMMARY:
                - Total Employees: %d
                - Total Gross: ₹%,.2f
                - Total Advance Deductions: ₹%,.2f
                - CASH TO WITHDRAW: ₹%,.2f
                
                Please print the attached PDF, withdraw the cash, and distribute payments.
                Each employee should sign against their name upon receiving payment.
                
                Generated: %s
                
                Regards,
                Big Bright Paints ERP System
                """,
                    summary.type(),
                    summary.startDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")),
                    summary.endDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")),
                    summary.lineItems().size(),
                    summary.lineItems().stream().map(PayrollLineItem::grossPay).reduce(BigDecimal.ZERO, BigDecimal::add),
                    summary.lineItems().stream().map(PayrollLineItem::advanceDeduction).reduce(BigDecimal.ZERO, BigDecimal::add),
                    summary.totalAmount(),
                    summary.calculatedAt().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm"))
            );
            
            String fileName = String.format("Payroll-%s.pdf", summary.reference());
            
            MimeMessagePreparator preparator = mimeMessage -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setTo(payrollNotificationEmail);
                helper.setFrom(fromAddress);
                helper.setSubject(subject);
                helper.setText(emailBody);
                helper.addAttachment(fileName, () -> new java.io.ByteArrayInputStream(pdfContent), "application/pdf");
            };
            
            mailSender.send(preparator);
            log.info("Sent payroll PDF notification to {}", payrollNotificationEmail);
        } catch (Exception e) {
            log.error("Failed to send payroll notification: {}", e.getMessage(), e);
        }
    }

    private byte[] generatePayrollPdf(Company company, PayrollSummary summary) {
        Context context = new Context();
        context.setVariable("companyName", company.getName());
        context.setVariable("payrollType", summary.type());
        context.setVariable("reference", summary.reference());
        context.setVariable("startDate", summary.startDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
        context.setVariable("endDate", summary.endDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
        context.setVariable("generatedAt", summary.calculatedAt().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm")));
        context.setVariable("lineItems", summary.lineItems());
        context.setVariable("totalGross", summary.lineItems().stream()
                .map(PayrollLineItem::grossPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        context.setVariable("totalAdvance", summary.lineItems().stream()
                .map(PayrollLineItem::advanceDeduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        context.setVariable("totalAmount", summary.totalAmount());
        
        String html = templateEngine.process("payroll-sheet", context);
        
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, "");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate payroll PDF", e);
        }
    }

    // DTOs
    public record PayrollLineItem(
            Long employeeId,
            String employeeName,
            String employeeType,
            BigDecimal dailyRate,
            BigDecimal presentDays,
            BigDecimal halfDays,
            BigDecimal overtimeHours,
            BigDecimal doubleOtHours,
            BigDecimal basePay,
            BigDecimal overtimePay,
            BigDecimal holidayPay,
            BigDecimal grossPay,
            BigDecimal advanceDeduction,
            BigDecimal totalDeductions,
            BigDecimal netPay
    ) {}

    public record PayrollSummary(
            Long payrollRunId,
            String reference,
            String type,
            LocalDate startDate,
            LocalDate endDate,
            List<PayrollLineItem> lineItems,
            BigDecimal totalAmount,
            LocalDateTime calculatedAt
    ) {}
}
