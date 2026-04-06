package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
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
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;

@Tag("critical")
class TS_RuntimePayrollServiceGuardrailsTest {

  @Test
  void markAsPaid_persistsProvidedPaymentReferenceOnPayrollRun() {
    PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
    PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
    CompanyClock companyClock = mock(CompanyClock.class);
    AuditService auditService = mock(AuditService.class);

    PayrollService service =
        new PayrollService(
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

    Company company = new Company();
    PayrollRun run = new PayrollRun();
    run.setRunType(PayrollRun.RunType.WEEKLY);
    run.setPeriodStart(LocalDate.of(2026, 2, 1));
    run.setPeriodEnd(LocalDate.of(2026, 2, 7));
    run.setStatus(PayrollRun.PayrollStatus.POSTED);
    run.setPaymentJournalEntryId(701L);
    PayrollRunLine line = new PayrollRunLine();
    JournalEntry paymentJournal = new JournalEntry();
    paymentJournal.setReferenceNumber("PAY-2026-001");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyEntityLookup.lockPayrollRun(company, 91L)).thenReturn(run);
    when(companyEntityLookup.requireJournalEntry(company, 701L)).thenReturn(paymentJournal);
    when(payrollRunLineRepository.findByPayrollRun(run)).thenReturn(List.of(line));

    service.markAsPaid(91L, " ext-ref-001 ");

    assertThat(ReflectionTestUtils.getField(run, "paymentReference")).isEqualTo("ext-ref-001");
  }

  @Test
  void markAsPaid_usesLoanDeductionWhenAdvancesMirrorFieldIsMissing() {
    PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
    PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
    CompanyClock companyClock = mock(CompanyClock.class);
    AuditService auditService = mock(AuditService.class);

    PayrollService service =
        new PayrollService(
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

    Company company = new Company();
    PayrollRun run = new PayrollRun();
    run.setRunType(PayrollRun.RunType.MONTHLY);
    run.setStatus(PayrollRun.PayrollStatus.POSTED);
    run.setPaymentJournalEntryId(811L);

    Employee employee = new Employee();
    employee.setAdvanceBalance(new BigDecimal("500"));

    PayrollRunLine line = new PayrollRunLine();
    line.setEmployee(employee);
    line.setLoanDeduction(new BigDecimal("100"));
    line.setAdvances(null);

    JournalEntry paymentJournal = new JournalEntry();
    paymentJournal.setReferenceNumber("PAY-811");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyEntityLookup.lockPayrollRun(company, 191L)).thenReturn(run);
    when(companyEntityLookup.requireJournalEntry(company, 811L)).thenReturn(paymentJournal);
    when(payrollRunLineRepository.findByPayrollRun(run)).thenReturn(List.of(line));

    service.markAsPaid(191L, null);

    ArgumentCaptor<Employee> employeeCaptor = ArgumentCaptor.forClass(Employee.class);
    verify(employeeRepository).save(employeeCaptor.capture());
    assertThat(employeeCaptor.getValue().getAdvanceBalance()).isEqualByComparingTo("400");
  }

  @Test
  void calculatePayroll_rejectsEmployeeWithNullStandardHoursPerDay() {
    PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
    PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
    CompanyClock companyClock = mock(CompanyClock.class);
    AuditService auditService = mock(AuditService.class);

    PayrollService service =
        new PayrollService(
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

    Company company = new Company();
    PayrollRun run = new PayrollRun();
    run.setRunType(PayrollRun.RunType.WEEKLY);
    run.setStatus(PayrollRun.PayrollStatus.DRAFT);
    run.setPeriodStart(LocalDate.of(2026, 2, 1));
    run.setPeriodEnd(LocalDate.of(2026, 2, 7));

    Employee employee = new Employee();
    employee.setEmployeeType(Employee.EmployeeType.LABOUR);
    employee.setDailyWage(new BigDecimal("600"));
    employee.setStandardHoursPerDay(null);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(payrollRunRepository.findByCompanyAndId(company, 88L)).thenReturn(Optional.of(run));
    when(employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
            company, Employee.EmployeeType.LABOUR, "ACTIVE"))
        .thenReturn(List.of(employee));
    when(attendanceRepository.findByEmployeeAndAttendanceDateBetween(
            employee, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 7)))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.calculatePayroll(88L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
  }

  @Test
  void calculatePayroll_rejectsEmployeeWithZeroStandardHoursPerDay() {
    PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
    PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    AccountingFacade accountingFacade = mock(AccountingFacade.class);
    AccountRepository accountRepository = mock(AccountRepository.class);
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
    CompanyClock companyClock = mock(CompanyClock.class);
    AuditService auditService = mock(AuditService.class);

    PayrollService service =
        new PayrollService(
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

    Company company = new Company();
    PayrollRun run = new PayrollRun();
    run.setRunType(PayrollRun.RunType.WEEKLY);
    run.setStatus(PayrollRun.PayrollStatus.DRAFT);
    run.setPeriodStart(LocalDate.of(2026, 2, 1));
    run.setPeriodEnd(LocalDate.of(2026, 2, 7));

    Employee employee = new Employee();
    employee.setEmployeeType(Employee.EmployeeType.LABOUR);
    employee.setDailyWage(new BigDecimal("600"));
    employee.setStandardHoursPerDay(BigDecimal.ZERO);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(payrollRunRepository.findByCompanyAndId(company, 89L)).thenReturn(Optional.of(run));
    when(employeeRepository.findByCompanyAndEmployeeTypeAndStatus(
            company, Employee.EmployeeType.LABOUR, "ACTIVE"))
        .thenReturn(List.of(employee));
    when(attendanceRepository.findByEmployeeAndAttendanceDateBetween(
            employee, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 7)))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.calculatePayroll(89L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
  }
}
