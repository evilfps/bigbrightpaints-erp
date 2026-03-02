package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
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
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PayrollCalculationServiceIndianRulesTest {

    @Test
    void calculatePayroll_monthlyTemplateEmployee_computesIndianComponentsAndDeductions() {
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);

        PayrollCalculationService service = new PayrollCalculationService(
                payrollRunRepository,
                payrollRunLineRepository,
                employeeRepository,
                attendanceRepository,
                companyContextService,
                companyClock,
                new StatutoryDeductionEngine(),
                new PayrollCalculationSupport());

        Company company = new Company();
        PayrollRun run = new PayrollRun();
        run.setRunType(PayrollRun.RunType.MONTHLY);
        run.setStatus(PayrollRun.PayrollStatus.DRAFT);
        run.setPeriodStart(LocalDate.of(2026, 2, 1));
        run.setPeriodEnd(LocalDate.of(2026, 2, 28));

        Employee employee = new Employee();
        employee.setFirstName("Asha");
        employee.setLastName("Iyer");
        employee.setStatus("ACTIVE");
        employee.setEmployeeType(Employee.EmployeeType.STAFF);
        employee.setMonthlySalary(new BigDecimal("30000"));
        employee.setWorkingDaysPerMonth(26);
        employee.setStandardHoursPerDay(new BigDecimal("8"));
        employee.setOvertimeRateMultiplier(new BigDecimal("1.5"));
        employee.setDoubleOtRateMultiplier(new BigDecimal("2.0"));
        employee.setTaxRegime(Employee.TaxRegime.NEW);
        employee.setAdvanceBalance(new BigDecimal("500"));

        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setBasicPay(new BigDecimal("15000"));
        template.setHra(new BigDecimal("7500"));
        template.setDa(new BigDecimal("3000"));
        template.setSpecialAllowance(new BigDecimal("4500"));
        template.setEmployeePfRate(new BigDecimal("12.00"));
        template.setEmployeeEsiRate(new BigDecimal("0.75"));
        template.setEsiEligibilityThreshold(new BigDecimal("21000"));
        template.setProfessionalTax(new BigDecimal("200"));
        employee.setSalaryStructureTemplate(template);

        Attendance present1 = attendance(employee, Attendance.AttendanceStatus.PRESENT, new BigDecimal("8"), BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance present2 = attendance(employee, Attendance.AttendanceStatus.PRESENT, new BigDecimal("8"), BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance halfDay = attendance(employee, Attendance.AttendanceStatus.HALF_DAY, new BigDecimal("4"), BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance absent = attendance(employee, Attendance.AttendanceStatus.ABSENT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance leave = attendance(employee, Attendance.AttendanceStatus.LEAVE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance holiday = attendance(employee, Attendance.AttendanceStatus.HOLIDAY, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(payrollRunRepository.findByCompanyAndId(company, 51L)).thenReturn(Optional.of(run));
        when(employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
                company, Employee.EmployeeType.STAFF, "ACTIVE")).thenReturn(List.of(employee));
        when(attendanceRepository.findByEmployeeAndAttendanceDateBetween(
                eq(employee), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(present1, present2, halfDay, absent, leave, holiday));
        when(payrollRunRepository.save(any(PayrollRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.calculatePayroll(51L);

        ArgumentCaptor<PayrollRunLine> lineCaptor = ArgumentCaptor.forClass(PayrollRunLine.class);
        verify(payrollRunLineRepository).save(lineCaptor.capture());
        PayrollRunLine line = lineCaptor.getValue();

        assertThat(line.getBasicSalaryComponent()).isEqualByComparingTo("13557.69");
        assertThat(line.getHraComponent()).isEqualByComparingTo("6778.85");
        assertThat(line.getDaComponent()).isEqualByComparingTo("2711.54");
        assertThat(line.getSpecialAllowanceComponent()).isEqualByComparingTo("4067.31");
        assertThat(line.getBasePay()).isEqualByComparingTo("27115.39");
        assertThat(line.getLeaveWithoutPayDeduction()).isEqualByComparingTo("2884.61");

        assertThat(line.getPfDeduction()).isEqualByComparingTo("1626.92");
        assertThat(line.getEsiDeduction()).isEqualByComparingTo("0.00");
        assertThat(line.getProfessionalTaxDeduction()).isEqualByComparingTo("200.00");
        assertThat(line.getLoanDeduction()).isEqualByComparingTo("500.00");
        assertThat(line.getTaxDeduction()).isEqualByComparingTo("326.92");
        assertThat(line.getTotalDeductions()).isEqualByComparingTo("2653.84");
        assertThat(line.getNetPay()).isEqualByComparingTo("25615.40");

        ArgumentCaptor<PayrollRun> runCaptor = ArgumentCaptor.forClass(PayrollRun.class);
        verify(payrollRunRepository).save(runCaptor.capture());
        PayrollRun savedRun = runCaptor.getValue();
        assertThat(savedRun.getStatus()).isEqualTo(PayrollRun.PayrollStatus.CALCULATED);
        assertThat(savedRun.getTotalDeductions()).isEqualByComparingTo("2653.84");
        assertThat(savedRun.getTotalNetPay()).isEqualByComparingTo("25615.40");
    }

    @Test
    void getMonthlyPaySummary_includesPfEsiProfessionalTaxAndLoanDeductions() {
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);

        PayrollCalculationService service = new PayrollCalculationService(
                payrollRunRepository,
                payrollRunLineRepository,
                employeeRepository,
                attendanceRepository,
                companyContextService,
                companyClock,
                new StatutoryDeductionEngine(),
                new PayrollCalculationSupport());

        Company company = new Company();
        Employee employee = new Employee();
        employee.setFirstName("Rahul");
        employee.setLastName("Sharma");
        employee.setStatus("ACTIVE");
        employee.setEmployeeType(Employee.EmployeeType.STAFF);
        employee.setMonthlySalary(new BigDecimal("30000"));
        employee.setWorkingDaysPerMonth(26);
        employee.setStandardHoursPerDay(new BigDecimal("8"));
        employee.setTaxRegime(Employee.TaxRegime.NEW);
        employee.setAdvanceBalance(new BigDecimal("300"));

        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setBasicPay(new BigDecimal("12000"));
        template.setHra(new BigDecimal("9000"));
        template.setDa(new BigDecimal("3000"));
        template.setSpecialAllowance(new BigDecimal("6000"));
        template.setEmployeePfRate(new BigDecimal("12.00"));
        template.setEmployeeEsiRate(new BigDecimal("0.75"));
        template.setEsiEligibilityThreshold(new BigDecimal("40000"));
        template.setProfessionalTax(new BigDecimal("200"));
        employee.setSalaryStructureTemplate(template);

        Attendance present = attendance(employee, Attendance.AttendanceStatus.PRESENT, new BigDecimal("8"), BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance halfDay = attendance(employee, Attendance.AttendanceStatus.HALF_DAY, new BigDecimal("4"), BigDecimal.ZERO, BigDecimal.ZERO);
        Attendance absent = attendance(employee, Attendance.AttendanceStatus.ABSENT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
                company, Employee.EmployeeType.STAFF, "ACTIVE")).thenReturn(List.of(employee));
        when(attendanceRepository.findByEmployeeTypeAndStatusAndDateRange(
                company,
                Employee.EmployeeType.STAFF,
                "ACTIVE",
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28)))
                .thenReturn(List.of(present, halfDay, absent));

        PayrollService.MonthlyPaySummaryDto summary = service.getMonthlyPaySummary(2026, 2);

        assertThat(summary.totalGrossPay()).isEqualByComparingTo("28269.24");
        assertThat(summary.totalDeductions()).isEqualByComparingTo("2068.94");
        assertThat(summary.totalNetPay()).isEqualByComparingTo("26200.30");
        assertThat(summary.employees()).hasSize(1);

        PayrollService.EmployeeMonthlyPayDto employeeSummary = summary.employees().get(0);
        assertThat(employeeSummary.pfDeduction()).isEqualByComparingTo("1356.92");
        assertThat(employeeSummary.netPay()).isEqualByComparingTo("26200.30");
    }

    private Attendance attendance(Employee employee,
                                  Attendance.AttendanceStatus status,
                                  BigDecimal regularHours,
                                  BigDecimal overtimeHours,
                                  BigDecimal doubleOvertimeHours) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(employee);
        attendance.setStatus(status);
        attendance.setRegularHours(regularHours);
        attendance.setOvertimeHours(overtimeHours);
        attendance.setDoubleOvertimeHours(doubleOvertimeHours);
        return attendance;
    }
}
