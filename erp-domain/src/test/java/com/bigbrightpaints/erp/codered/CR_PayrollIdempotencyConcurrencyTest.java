package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CR_PayrollIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PayrollService payrollService;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private PayrollRunLineRepository payrollRunLineRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private AccountingService accountingService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void payrollRun_idempotentForSamePeriodAndType_andConcurrencySafe() {
        String companyCode = "CR-PAY-" + shortId();
        Company company = bootstrapCompany(companyCode);
        ensurePayrollAccounts(company);

        LocalDate anchor = TestDateUtils.safeDate(company);
        LocalDate start = anchor.minusDays(14);
        LocalDate end = anchor.minusDays(1);
        PayrollService.CreatePayrollRunRequest request = new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.WEEKLY,
                start,
                end,
                "CODE-RED weekly payroll"
        );

        CompanyContextHolder.setCompanyId(companyCode);
        var first = payrollService.createPayrollRun(request);
        var second = payrollService.createPayrollRun(request);
        CompanyContextHolder.clear();

        assertThat(second.id()).isEqualTo(first.id());
        CoderedDbAssertions.assertSinglePayrollRun(jdbcTemplate, company.getId(), PayrollRun.RunType.WEEKLY, start, end);

        var concurrent = CoderedConcurrencyHarness.run(
                2,
                3,
                Duration.ofSeconds(30),
                threadIndex -> () -> {
                    CompanyContextHolder.setCompanyId(companyCode);
                    try {
                        return payrollService.createPayrollRun(request);
                    } finally {
                        CompanyContextHolder.clear();
                    }
                },
                CoderedRetry::isRetryable
        );

        assertThat(concurrent.outcomes())
                .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);
        List<Long> runIds = concurrent.outcomes().stream()
                .map(outcome -> ((CoderedConcurrencyHarness.Outcome.Success<PayrollService.PayrollRunDto>) outcome).value())
                .map(PayrollService.PayrollRunDto::id)
                .distinct()
                .toList();
        assertThat(runIds).as("Concurrent callers converge on one run").hasSize(1);
        CoderedDbAssertions.assertSinglePayrollRun(jdbcTemplate, company.getId(), PayrollRun.RunType.WEEKLY, start, end);
    }

    @Test
    void payrollPosting_isIdempotent_andCreatesExpensePayableLines() {
        String companyCode = "CR-PAY-POST-" + shortId();
        Company company = bootstrapCompany(companyCode);
        ensurePayrollAccounts(company);

        LocalDate anchor = TestDateUtils.safeDate(company);
        LocalDate start = anchor.minusDays(30);
        LocalDate end = anchor.minusDays(1);

        CompanyContextHolder.setCompanyId(companyCode);
        var runDto = payrollService.createPayrollRun(new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.MONTHLY,
                start,
                end,
                "CODE-RED monthly payroll"
        ));
        CompanyContextHolder.clear();

        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, runDto.id()).orElseThrow();
        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        payrollRunRepository.save(run);
        seedMinimalPayrollLines(company, run, BigDecimal.valueOf(1000), BigDecimal.valueOf(100));

        CompanyContextHolder.setCompanyId(companyCode);
        var posted = payrollService.postPayrollToAccounting(run.getId());
        CompanyContextHolder.clear();

        PayrollRun postedRun = payrollRunRepository.findByCompanyAndId(company, posted.id()).orElseThrow();
        assertThat(postedRun.getStatus()).isEqualTo(PayrollRun.PayrollStatus.POSTED);
        assertThat(postedRun.getJournalEntryId()).isNotNull();

        JournalEntry journal = journalEntryRepository.findById(postedRun.getJournalEntryId()).orElseThrow();
        assertThat(journal.getReferenceNumber())
                .as("Payroll posting uses AccountingFacade PAYROLL-* reference namespace")
                .startsWith("PAYROLL-");
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journal.getId());

        CompanyContextHolder.setCompanyId(companyCode);
        var reposted = payrollService.postPayrollToAccounting(run.getId());
        CompanyContextHolder.clear();

        PayrollRun repostedRun = payrollRunRepository.findByCompanyAndId(company, reposted.id()).orElseThrow();
        assertThat(repostedRun.getJournalEntryId()).isEqualTo(postedRun.getJournalEntryId());

        JournalEntry reloaded = journalEntryRepository.findById(postedRun.getJournalEntryId()).orElseThrow();
        assertThat(reloaded.getLines()).anySatisfy(line -> {
            assertThat(line.getAccount().getCode()).isEqualTo("SALARY-EXP");
            assertThat(line.getDebit()).isEqualByComparingTo("2000.00");
        });
        assertThat(reloaded.getLines()).anySatisfy(line -> {
            assertThat(line.getAccount().getCode()).isEqualTo("SALARY-PAYABLE");
            assertThat(line.getCredit()).isEqualByComparingTo("1800.00");
        });
        assertThat(reloaded.getLines()).anySatisfy(line -> {
            assertThat(line.getAccount().getCode()).isEqualTo("EMP-ADV");
            assertThat(line.getCredit()).isEqualByComparingTo("200.00");
        });
    }

    @Test
    void payrollPosting_mismatchConflictOnReplay() {
        String companyCode = "CR-PAY-POST-MISMATCH-" + shortId();
        Company company = bootstrapCompany(companyCode);
        ensurePayrollAccounts(company);

        LocalDate anchor = TestDateUtils.safeDate(company);
        LocalDate start = anchor.minusDays(30);
        LocalDate end = anchor.minusDays(1);

        CompanyContextHolder.setCompanyId(companyCode);
        var runDto = payrollService.createPayrollRun(new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.MONTHLY,
                start,
                end,
                "CODE-RED monthly payroll"
        ));
        CompanyContextHolder.clear();

        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, runDto.id()).orElseThrow();
        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        payrollRunRepository.save(run);
        seedMinimalPayrollLines(company, run, BigDecimal.valueOf(1000));

        CompanyContextHolder.setCompanyId(companyCode);
        payrollService.postPayrollToAccounting(run.getId());
        CompanyContextHolder.clear();

        List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
        PayrollRunLine first = lines.get(0);
        first.setGrossPay(first.getGrossPay().add(BigDecimal.valueOf(250)));
        payrollRunLineRepository.save(first);

        CompanyContextHolder.setCompanyId(companyCode);
        assertThatThrownBy(() -> payrollService.postPayrollToAccounting(run.getId()))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
        CompanyContextHolder.clear();
    }

    @Test
    void legacyPayrollRun_postsDeterministically_toPayrollLegacyReference() {
        String companyCode = "CR-PAY-LEGACY-" + shortId();
        Company company = bootstrapCompany(companyCode);
        ensurePayrollAccounts(company);

        LocalDate anchor = TestDateUtils.safeDate(company);
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunType(PayrollRun.RunType.MONTHLY);
        run.setPeriodStart(anchor.minusDays(30));
        run.setPeriodEnd(anchor.minusDays(1));
        run.setRunDate(run.getPeriodEnd());
        run.setRunNumber(""); // legacy blank-but-not-null
        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        PayrollRun saved = payrollRunRepository.save(run);
        seedMinimalPayrollLines(company, saved, BigDecimal.valueOf(500));

        CompanyContextHolder.setCompanyId(companyCode);
        var posted = payrollService.postPayrollToAccounting(saved.getId());
        CompanyContextHolder.clear();

        PayrollRun postedRun = payrollRunRepository.findByCompanyAndId(company, posted.id()).orElseThrow();
        JournalEntry journal = journalEntryRepository.findById(postedRun.getJournalEntryId()).orElseThrow();
        assertThat(journal.getReferenceNumber())
                .isEqualTo("PAYROLL-LEGACY-" + saved.getId());
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journal.getId());
    }

    @Test
    void payrollPayment_clearsSalaryPayable_andRequiresPaymentJournal_beforeMarkPaid() {
        String companyCode = "CR-PAY-PMT-" + shortId();
        Company company = bootstrapCompany(companyCode);
        ensurePayrollAccounts(company);

        Account cash = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
        Account payable = ensureAccount(company, "SALARY-PAYABLE", "Salary Payable", AccountType.LIABILITY);

        LocalDate anchor = TestDateUtils.safeDate(company);
        LocalDate start = anchor.minusDays(30);
        LocalDate end = anchor.minusDays(1);

        CompanyContextHolder.setCompanyId(companyCode);
        var runDto = payrollService.createPayrollRun(new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.MONTHLY,
                start,
                end,
                "CODE-RED payroll payment"
        ));
        CompanyContextHolder.clear();

        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, runDto.id()).orElseThrow();
        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        payrollRunRepository.save(run);
        seedMinimalPayrollLines(company, run, BigDecimal.valueOf(1000));

        CompanyContextHolder.setCompanyId(companyCode);
        var postedDto = payrollService.postPayrollToAccounting(run.getId());
        CompanyContextHolder.clear();

        PayrollRun posted = payrollRunRepository.findByCompanyAndId(company, postedDto.id()).orElseThrow();
        assertThat(posted.getStatus()).isEqualTo(PayrollRun.PayrollStatus.POSTED);
        assertThat(posted.getJournalEntryId()).as("posting journal stored").isNotNull();
        assertThat(posted.getPaymentJournalEntryId()).as("payment journal not yet recorded").isNull();

        BigDecimal expectedPayable = new BigDecimal("2000");
        CompanyContextHolder.setCompanyId(companyCode);
        var payment = accountingService.recordPayrollPayment(new PayrollPaymentRequest(
                posted.getId(),
                cash.getId(),
                cash.getId(), // legacy field; ignored for posted runs
                expectedPayable,
                "PAYTEST-" + System.nanoTime(),
                "CODE-RED payroll payment"
        ));
        CompanyContextHolder.clear();

        PayrollRun afterPayment = payrollRunRepository.findByCompanyAndId(company, posted.getId()).orElseThrow();
        assertThat(afterPayment.getPaymentJournalEntryId()).isNotNull();
        assertThat(afterPayment.getStatus()).as("status remains POSTED until HR marks paid").isEqualTo(PayrollRun.PayrollStatus.POSTED);

        JournalEntry paymentJournal = journalEntryRepository.findById(afterPayment.getPaymentJournalEntryId()).orElseThrow();
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, paymentJournal.getId());

        BigDecimal payableDebit = paymentJournal.getLines().stream()
                .filter(line -> line.getAccount() != null && payable.getId().equals(line.getAccount().getId()))
                .map(line -> line.getDebit() != null ? line.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payableCredit = paymentJournal.getLines().stream()
                .filter(line -> line.getAccount() != null && payable.getId().equals(line.getAccount().getId()))
                .map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(payableDebit).as("Dr salary payable").isEqualByComparingTo(expectedPayable);
        assertThat(payableCredit).as("Salary payable has no credit in payment journal").isZero();

        BigDecimal cashDebit = paymentJournal.getLines().stream()
                .filter(line -> line.getAccount() != null && cash.getId().equals(line.getAccount().getId()))
                .map(line -> line.getDebit() != null ? line.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashCredit = paymentJournal.getLines().stream()
                .filter(line -> line.getAccount() != null && cash.getId().equals(line.getAccount().getId()))
                .map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cashDebit).as("Cash has no debit in payment journal").isZero();
        assertThat(cashCredit).as("Cr cash").isEqualByComparingTo(expectedPayable);

        CompanyContextHolder.setCompanyId(companyCode);
        payrollService.markAsPaid(afterPayment.getId(), payment.referenceNumber());
        CompanyContextHolder.clear();

        PayrollRun paid = payrollRunRepository.findByCompanyAndId(company, afterPayment.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PayrollRun.PayrollStatus.PAID);
        assertThat(payrollRunLineRepository.findByPayrollRun(paid))
                .allSatisfy(line -> assertThat(line.getPaymentStatus()).isEqualTo(PayrollRunLine.PaymentStatus.PAID));
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        return companyRepository.save(company);
    }

    private void ensurePayrollAccounts(Company company) {
        ensureAccount(company, "SALARY-EXP", "Salary Expense", AccountType.EXPENSE);
        ensureAccount(company, "WAGE-EXP", "Wage Expense", AccountType.EXPENSE);
        ensureAccount(company, "SALARY-PAYABLE", "Salary Payable", AccountType.LIABILITY);
        ensureAccount(company, "EMP-ADV", "Employee Advances", AccountType.ASSET);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setActive(true);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private void seedMinimalPayrollLines(Company company, PayrollRun run, BigDecimal perEmployeeGross) {
        seedMinimalPayrollLines(company, run, perEmployeeGross, BigDecimal.ZERO);
    }

    private void seedMinimalPayrollLines(Company company, PayrollRun run, BigDecimal perEmployeeGross, BigDecimal advanceDeduction) {
        Employee a = ensureEmployee(company, "A", perEmployeeGross);
        Employee b = ensureEmployee(company, "B", perEmployeeGross);

        BigDecimal netPay = perEmployeeGross.subtract(advanceDeduction);

        PayrollRunLine lineA = new PayrollRunLine();
        lineA.setPayrollRun(run);
        lineA.setEmployee(a);
        lineA.setGrossPay(perEmployeeGross);
        lineA.setAdvanceDeduction(advanceDeduction);
        lineA.setTotalDeductions(advanceDeduction);
        lineA.setNetPay(netPay);
        lineA.setLineTotal(netPay);
        lineA.setName(a.getFullName());
        lineA.setDaysWorked(1);
        lineA.setDailyWage(BigDecimal.ZERO);
        lineA.setAdvances(advanceDeduction);

        PayrollRunLine lineB = new PayrollRunLine();
        lineB.setPayrollRun(run);
        lineB.setEmployee(b);
        lineB.setGrossPay(perEmployeeGross);
        lineB.setAdvanceDeduction(advanceDeduction);
        lineB.setTotalDeductions(advanceDeduction);
        lineB.setNetPay(netPay);
        lineB.setLineTotal(netPay);
        lineB.setName(b.getFullName());
        lineB.setDaysWorked(1);
        lineB.setDailyWage(BigDecimal.ZERO);
        lineB.setAdvances(advanceDeduction);

        payrollRunLineRepository.saveAll(List.of(lineA, lineB));
    }

    private Employee ensureEmployee(Company company, String suffix, BigDecimal monthlySalary) {
        String email = "codered-" + suffix.toLowerCase() + "-" + company.getCode().toLowerCase() + "@example.com";
        return employeeRepository.findByCompanyAndEmail(company, email)
                .orElseGet(() -> {
                    Employee employee = new Employee();
                    employee.setCompany(company);
                    employee.setFirstName("CR");
                    employee.setLastName("EMP-" + suffix);
                    employee.setEmail(email);
                    employee.setEmployeeType(Employee.EmployeeType.STAFF);
                    employee.setPaymentSchedule(Employee.PaymentSchedule.MONTHLY);
                    employee.setMonthlySalary(monthlySalary);
                    employee.setStatus("ACTIVE");
                    return employeeRepository.save(employee);
                });
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
