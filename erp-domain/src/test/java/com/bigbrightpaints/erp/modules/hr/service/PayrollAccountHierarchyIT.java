package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PayrollAccountHierarchyIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "PAY-HIER";

    @Autowired
    private PayrollService payrollService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private PayrollRunLineRepository payrollRunLineRepository;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void postPayrollParentsExistingAccounts() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Payroll Hierarchy Co");
        CompanyContextHolder.setCompanyId(company.getCode());

        Account payrollExpenses = createAccount(company, "PAYROLL-EXPENSES", "Payroll Expenses", AccountType.EXPENSE);
        Account payrollLiabilities = createAccount(company, "PAYROLL-LIABILITIES", "Payroll Liabilities", AccountType.LIABILITY);
        Account payrollAdvances = createAccount(company, "PAYROLL-ADVANCES", "Payroll Advances", AccountType.ASSET);

        createAccount(company, "SALARY-EXP", "Salary Expense", AccountType.EXPENSE);
        createAccount(company, "WAGE-EXP", "Wage Expense", AccountType.EXPENSE);
        createAccount(company, "SALARY-PAYABLE", "Salary Payable", AccountType.LIABILITY);
        createAccount(company, "EMP-ADV", "Employee Advances", AccountType.ASSET);
        createAccount(company, "PF-PAYABLE", "Provident Fund Payable", AccountType.LIABILITY);

        Employee employee = new Employee();
        employee.setCompany(company);
        employee.setFirstName("Priya");
        employee.setLastName("Menon");
        employee.setEmail("priya.menon@pay-hier.test");
        employee.setEmployeeType(Employee.EmployeeType.STAFF);
        employee.setMonthlySalary(new BigDecimal("20000.00"));
        employee = employeeRepository.save(employee);

        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunNumber("PR-M-2026-01");
        run.setRunType(PayrollRun.RunType.MONTHLY);
        run.setPeriodStart(LocalDate.of(2026, 1, 1));
        run.setPeriodEnd(LocalDate.of(2026, 1, 31));
        run.setRunDate(LocalDate.of(2026, 1, 31));
        run.setStatus(PayrollRun.PayrollStatus.APPROVED);
        payrollRunRepository.save(run);

        PayrollRunLine line = new PayrollRunLine();
        line.setPayrollRun(run);
        line.setEmployee(employee);
        line.setName(employee.getFullName());
        line.setDaysWorked(26);
        line.setDailyWage(employee.getDailyRate());
        line.setAdvances(new BigDecimal("500.00"));
        line.setPresentDays(new BigDecimal("26"));
        line.setGrossPay(new BigDecimal("20000.00"));
        line.setAdvanceDeduction(new BigDecimal("500.00"));
        line.setPfDeduction(new BigDecimal("2400.00"));
        line.setTotalDeductions(new BigDecimal("2900.00"));
        line.setLineTotal(new BigDecimal("17100.00"));
        payrollRunLineRepository.saveAll(List.of(line));

        payrollService.postPayrollToAccounting(run.getId());

        Account salaryExpense = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-EXP").orElseThrow();
        Account wageExpense = accountRepository.findByCompanyAndCodeIgnoreCase(company, "WAGE-EXP").orElseThrow();
        Account salaryPayable = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE").orElseThrow();
        Account advanceAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "EMP-ADV").orElseThrow();
        Account pfPayable = accountRepository.findByCompanyAndCodeIgnoreCase(company, "PF-PAYABLE").orElseThrow();

        assertThat(salaryExpense.getParent()).isNotNull();
        assertThat(salaryExpense.getParent().getId()).isEqualTo(payrollExpenses.getId());
        assertThat(wageExpense.getParent()).isNotNull();
        assertThat(wageExpense.getParent().getId()).isEqualTo(payrollExpenses.getId());
        assertThat(salaryPayable.getParent()).isNotNull();
        assertThat(salaryPayable.getParent().getId()).isEqualTo(payrollLiabilities.getId());
        assertThat(advanceAccount.getParent()).isNotNull();
        assertThat(advanceAccount.getParent().getId()).isEqualTo(payrollAdvances.getId());
        assertThat(pfPayable.getParent()).isNotNull();
        assertThat(pfPayable.getParent().getId()).isEqualTo(payrollLiabilities.getId());
    }

    private Account createAccount(Company company, String code, String name, AccountType type) {
        Account account = new Account();
        account.setCompany(company);
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        return accountRepository.save(account);
    }
}
