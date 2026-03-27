package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.benchmark.override-date=2030-01-15")
class CR_TimeDateSafetyTest extends AbstractIntegrationTest {

  private static final LocalDate OVERRIDE_DATE = LocalDate.of(2030, 1, 15);

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountingFacade accountingFacade;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private PayrollService payrollService;
  @Autowired private PayrollRunRepository payrollRunRepository;
  @Autowired private PayrollRunLineRepository payrollRunLineRepository;
  @Autowired private EmployeeRepository employeeRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void businessDates_comeFromCompanyClock_andAlignAcrossModules() {
    String companyCode = "CR-DATE-" + shortId();
    Company company = bootstrapCompany(companyCode, "Asia/Kolkata");

    Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
    Account expense = ensureAccount(company, "MISC-EXP", "Misc Expense", AccountType.EXPENSE);
    ensureAccount(company, "SALARY-EXP", "Salary Expense", AccountType.EXPENSE);
    ensureAccount(company, "WAGE-EXP", "Wage Expense", AccountType.EXPENSE);
    ensureAccount(company, "SALARY-PAYABLE", "Salary Payable", AccountType.LIABILITY);
    ensureAccount(company, "EMP-ADV", "Employee Advances", AccountType.ASSET);
    ensureAccount(company, "PF-PAYABLE", "PF Payable", AccountType.LIABILITY);
    ensureAccount(company, "ESI-PAYABLE", "ESI Payable", AccountType.LIABILITY);
    ensureAccount(company, "TDS-PAYABLE", "TDS Payable", AccountType.LIABILITY);
    ensureAccount(
        company, "PROFESSIONAL-TAX-PAYABLE", "Professional Tax Payable", AccountType.LIABILITY);

    CompanyContextHolder.setCompanyCode(companyCode);

    // Accounting: entryDate defaults to CompanyClock.today(company)
    var manual =
        accountingFacade.postSimpleJournal(
            "MANUAL-" + shortId(),
            null,
            "CODE-RED fixed-date journal",
            bank.getId(),
            expense.getId(),
            new BigDecimal("100.00"),
            false);
    JournalEntry je = journalEntryRepository.findById(manual.id()).orElseThrow();
    assertThat(je.getEntryDate())
        .as("Accounting uses CompanyClock override date")
        .isEqualTo(OVERRIDE_DATE);

    // Inventory: manufacturedAt defaults to CompanyTime.now(company), derived from CompanyClock
    FinishedGood fg =
        ensureFinishedGood(
            company, bank.getId(), bank.getId(), expense.getId(), expense.getId(), expense.getId());
    var batchDto =
        finishedGoodsService.registerBatch(
            new FinishedGoodBatchRequest(
                fg.getId(),
                "BATCH-" + shortId(),
                new BigDecimal("1"),
                new BigDecimal("1.00"),
                null,
                null));
    FinishedGoodBatch batch = finishedGoodBatchRepository.findById(batchDto.id()).orElseThrow();
    LocalDate manufactured =
        LocalDate.ofInstant(batch.getManufacturedAt(), ZoneId.of(company.getTimezone()));
    assertThat(manufactured)
        .as("Inventory uses CompanyClock override date")
        .isEqualTo(OVERRIDE_DATE);

    // Payroll: posting date clamps to CompanyClock.today(company) when period end is in the future
    var runDto =
        payrollService.createPayrollRun(
            new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.MONTHLY,
                OVERRIDE_DATE.minusDays(30),
                OVERRIDE_DATE.plusDays(10), // future vs override-date
                "CODE-RED payroll date clamp"));
    PayrollRun run = payrollRunRepository.findByCompanyAndId(company, runDto.id()).orElseThrow();
    run.setStatus(PayrollRun.PayrollStatus.APPROVED);
    payrollRunRepository.save(run);
    seedPayrollLine(company, run);

    var posted = payrollService.postPayrollToAccounting(run.getId());
    PayrollRun postedRun =
        payrollRunRepository.findByCompanyAndId(company, posted.id()).orElseThrow();
    JournalEntry payrollJe =
        journalEntryRepository.findById(postedRun.getJournalEntryId()).orElseThrow();
    assertThat(payrollJe.getEntryDate())
        .as("Payroll uses CompanyClock override date")
        .isEqualTo(OVERRIDE_DATE);
  }

  private Company bootstrapCompany(String companyCode, String timezone) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone(timezone);
    company.setBaseCurrency("INR");
    return companyRepository.save(company);
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
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

  private FinishedGood ensureFinishedGood(
      Company company,
      Long valuationAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long taxAccountId) {
    String sku = "CR-FG-" + shortId();
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            sku,
            sku,
            "UNIT",
            "FIFO",
            valuationAccountId,
            cogsAccountId,
            revenueAccountId,
            discountAccountId,
            taxAccountId);
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, sku)
        .orElseGet(
            () -> {
              var dto = finishedGoodsService.createFinishedGood(req);
              return finishedGoodRepository.findById(dto.id()).orElseThrow();
            });
  }

  private void seedPayrollLine(Company company, PayrollRun run) {
    String email = "codered-" + company.getCode().toLowerCase() + "@example.com";
    Employee employee =
        employeeRepository
            .findByCompanyAndEmail(company, email)
            .orElseGet(
                () -> {
                  Employee e = new Employee();
                  e.setCompany(company);
                  e.setFirstName("CR");
                  e.setLastName("EMP");
                  e.setEmail(email);
                  e.setEmployeeType(Employee.EmployeeType.STAFF);
                  e.setPaymentSchedule(Employee.PaymentSchedule.MONTHLY);
                  e.setMonthlySalary(new BigDecimal("1000.00"));
                  e.setStatus("ACTIVE");
                  return employeeRepository.save(e);
                });

    PayrollRunLine line = new PayrollRunLine();
    line.setPayrollRun(run);
    line.setEmployee(employee);
    line.setGrossPay(new BigDecimal("1000.00"));
    line.setNetPay(new BigDecimal("1000.00"));
    line.setLineTotal(new BigDecimal("1000.00"));
    line.setName(employee.getFullName());
    line.setDaysWorked(1);
    line.setDailyWage(BigDecimal.ZERO);
    line.setAdvances(BigDecimal.ZERO);
    payrollRunLineRepository.save(line);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
