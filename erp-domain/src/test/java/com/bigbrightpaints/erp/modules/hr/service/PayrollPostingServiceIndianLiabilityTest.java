package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
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

class PayrollPostingServiceIndianLiabilityTest {

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void postPayrollToAccounting_postsStatutoryLiabilityLinesAndSalaryResidual() {
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

    PayrollPostingService service =
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

    Company company = new Company();
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", 70L);
    run.setRunNumber("PR-2026-02");
    run.setRunType(PayrollRun.RunType.MONTHLY);
    run.setPeriodStart(LocalDate.of(2026, 2, 1));
    run.setPeriodEnd(LocalDate.of(2026, 2, 28));
    run.setStatus(PayrollRun.PayrollStatus.APPROVED);

    Employee employee = new Employee();
    ReflectionTestUtils.setField(employee, "id", 501L);
    PayrollRunLine line = new PayrollRunLine();
    line.setEmployee(employee);
    line.setGrossPay(new BigDecimal("26538.47"));
    line.setLoanDeduction(new BigDecimal("500.00"));
    line.setPfDeduction(new BigDecimal("1592.31"));
    line.setEsiDeduction(BigDecimal.ZERO);
    line.setTaxDeduction(new BigDecimal("156.54"));
    line.setProfessionalTaxDeduction(new BigDecimal("200.00"));
    line.setOtherDeductions(BigDecimal.ZERO);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyEntityLookup.lockPayrollRun(company, 70L)).thenReturn(run);
    when(payrollRunLineRepository.findByPayrollRun(run)).thenReturn(List.of(line));
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 28));

    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-EXP"))
        .thenReturn(Optional.of(account(1L, "SALARY-EXP", AccountType.EXPENSE)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "WAGE-EXP"))
        .thenReturn(Optional.of(account(2L, "WAGE-EXP", AccountType.EXPENSE)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE"))
        .thenReturn(Optional.of(account(3L, "SALARY-PAYABLE", AccountType.LIABILITY)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "EMP-ADV"))
        .thenReturn(Optional.of(account(4L, "EMP-ADV", AccountType.ASSET)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "PF-PAYABLE"))
        .thenReturn(Optional.of(account(5L, "PF-PAYABLE", AccountType.LIABILITY)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "ESI-PAYABLE"))
        .thenReturn(Optional.of(account(6L, "ESI-PAYABLE", AccountType.LIABILITY)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "TDS-PAYABLE"))
        .thenReturn(Optional.of(account(7L, "TDS-PAYABLE", AccountType.LIABILITY)));
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "PROFESSIONAL-TAX-PAYABLE"))
        .thenReturn(Optional.of(account(8L, "PROFESSIONAL-TAX-PAYABLE", AccountType.LIABILITY)));

    JournalEntryDto postedJournal =
        new JournalEntryDto(
            1001L,
            UUID.randomUUID(),
            "PAYROLL-PR-2026-02",
            LocalDate.of(2026, 2, 28),
            "Payroll - PR-2026-02",
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            Instant.now(),
            Instant.now(),
            Instant.now(),
            "system",
            "system",
            "system");
    when(accountingFacade.postPayrollRun(
            eq("PR-2026-02"), eq(70L), eq(LocalDate.of(2026, 2, 28)), any(), any()))
        .thenReturn(postedJournal);
    when(companyEntityLookup.requireJournalEntry(company, 1001L))
        .thenReturn(new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry());
    when(payrollRunRepository.save(any(PayrollRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.postPayrollToAccounting(70L);

    ArgumentCaptor<
            List<
                com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest
                    .JournalLineRequest>>
        linesCaptor = ArgumentCaptor.forClass(List.class);
    verify(accountingFacade)
        .postPayrollRun(
            eq("PR-2026-02"), eq(70L), eq(LocalDate.of(2026, 2, 28)), any(), linesCaptor.capture());

    List<com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest>
        postedLines = linesCaptor.getValue();
    assertThat(postedLines)
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(1L);
              assertThat(lineRequest.debit()).isEqualByComparingTo("26538.47");
              assertThat(lineRequest.credit()).isEqualByComparingTo("0.00");
            })
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(3L);
              assertThat(lineRequest.credit()).isEqualByComparingTo("24089.62");
            })
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(5L);
              assertThat(lineRequest.credit()).isEqualByComparingTo("1592.31");
            })
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(7L);
              assertThat(lineRequest.credit()).isEqualByComparingTo("156.54");
            })
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(8L);
              assertThat(lineRequest.credit()).isEqualByComparingTo("200.00");
            })
        .anySatisfy(
            lineRequest -> {
              assertThat(lineRequest.accountId()).isEqualTo(4L);
              assertThat(lineRequest.credit()).isEqualByComparingTo("500.00");
            });

    BigDecimal totalDebit =
        postedLines.stream()
            .map(l -> l.debit() == null ? BigDecimal.ZERO : l.debit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredit =
        postedLines.stream()
            .map(l -> l.credit() == null ? BigDecimal.ZERO : l.credit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    assertThat(totalDebit).isEqualByComparingTo("26538.47");
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCode(code);
    account.setType(type);
    account.setActive(true);
    account.setBalance(BigDecimal.ZERO);
    return account;
  }
}
