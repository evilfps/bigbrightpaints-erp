package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimePayrollMarkPaidExecutableCoverageTest {

    @Test
    void markAsPaid_rejectsWhenPaymentJournalIsMissing() {
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository =
                mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        com.bigbrightpaints.erp.core.audit.AuditService auditService =
                mock(com.bigbrightpaints.erp.core.audit.AuditService.class);

        PayrollService service = new PayrollService(
                payrollRunRepository,
                payrollRunLineRepository,
                employeeRepository,
                attendanceRepository,
                accountingFacade,
                accountRepository,
                companyContextService,
                companyEntityLookup,
                companyClock,
                auditService
        );

        Company company = new Company();
        PayrollRun run = new PayrollRun();
        run.setStatus(PayrollRun.PayrollStatus.POSTED);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.lockPayrollRun(company, 77L)).thenReturn(run);

        assertThatThrownBy(() -> service.markAsPaid(77L, "ignored"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Payroll payment journal is required before marking payroll as PAID");
    }

    @Test
    void markAsPaid_rejectsWhenCanonicalReferenceIsBlank() {
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository =
                mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        com.bigbrightpaints.erp.core.audit.AuditService auditService =
                mock(com.bigbrightpaints.erp.core.audit.AuditService.class);

        PayrollService service = new PayrollService(
                payrollRunRepository,
                payrollRunLineRepository,
                employeeRepository,
                attendanceRepository,
                accountingFacade,
                accountRepository,
                companyContextService,
                companyEntityLookup,
                companyClock,
                auditService
        );

        Company company = new Company();
        PayrollRun run = new PayrollRun();
        run.setStatus(PayrollRun.PayrollStatus.POSTED);
        run.setPaymentJournalEntryId(990L);
        JournalEntry paymentJournal = new JournalEntry();
        paymentJournal.setReferenceNumber("   ");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.lockPayrollRun(company, 77L)).thenReturn(run);
        when(companyEntityLookup.requireJournalEntry(company, 990L)).thenReturn(paymentJournal);

        assertThatThrownBy(() -> service.markAsPaid(77L, "legacy-ref"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Payroll payment journal reference is missing");
    }

    @Test
    void markAsPaid_usesTrimmedCanonicalReferenceOnPayrollLines() {
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        PayrollRunLineRepository payrollRunLineRepository = mock(PayrollRunLineRepository.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        AccountingFacade accountingFacade = mock(AccountingFacade.class);
        com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository =
                mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        com.bigbrightpaints.erp.core.audit.AuditService auditService =
                mock(com.bigbrightpaints.erp.core.audit.AuditService.class);

        PayrollService service = new PayrollService(
                payrollRunRepository,
                payrollRunLineRepository,
                employeeRepository,
                attendanceRepository,
                accountingFacade,
                accountRepository,
                companyContextService,
                companyEntityLookup,
                companyClock,
                auditService
        );

        Company company = new Company();
        PayrollRun run = new PayrollRun();
        run.setRunType(PayrollRun.RunType.WEEKLY);
        run.setStatus(PayrollRun.PayrollStatus.POSTED);
        run.setPaymentJournalEntryId(991L);

        PayrollRunLine line = new PayrollRunLine();
        JournalEntry paymentJournal = new JournalEntry();
        paymentJournal.setReferenceNumber(" PAYROLL-PAY-2026-001 ");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.lockPayrollRun(company, 77L)).thenReturn(run);
        when(companyEntityLookup.requireJournalEntry(company, 991L)).thenReturn(paymentJournal);
        when(payrollRunLineRepository.findByPayrollRun(run)).thenReturn(List.of(line));
        when(payrollRunRepository.save(any(PayrollRun.class))).thenReturn(run);

        service.markAsPaid(77L, "legacy-ref");

        assertThat(line.getPaymentStatus()).isEqualTo(PayrollRunLine.PaymentStatus.PAID);
        assertThat(line.getPaymentReference()).isEqualTo("PAYROLL-PAY-2026-001");
        assertThat(run.getStatus()).isEqualTo(PayrollRun.PayrollStatus.PAID);

        verify(payrollRunLineRepository).saveAll(any(List.class));
        verify(payrollRunRepository).save(run);
    }
}
