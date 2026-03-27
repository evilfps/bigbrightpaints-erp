package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentResponse;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Accounting: Payroll batch payment")
public class PayrollBatchPaymentIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "PAY-BATCH";

  @Autowired private AccountingService accountingService;

  @Autowired private AccountRepository accountRepository;

  @Autowired private PayrollRunRepository payrollRunRepository;

  @Autowired private PayrollRunLineRepository payrollRunLineRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void should_create_payroll_run_lines_and_post_journal() {
    Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Payroll Batch Co");
    CompanyContextHolder.setCompanyCode(company.getCode());

    Account cash = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account expense =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "EXP")
            .orElseGet(
                () ->
                    accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV").orElseThrow());

    PayrollBatchPaymentRequest request =
        new PayrollBatchPaymentRequest(
            LocalDate.now(),
            cash.getId(),
            expense.getId(),
            null, // taxPayableAccountId
            null, // pfPayableAccountId
            null, // employerTaxExpenseAccountId
            null, // employerPfExpenseAccountId
            null, // defaultTaxRate
            null, // defaultPfRate
            null, // employerTaxRate
            null, // employerPfRate
            "PAY-" + System.currentTimeMillis(),
            "Weekly payroll batch",
            List.of(
                new PayrollBatchPaymentRequest.PayrollLine(
                    "Labour A",
                    5,
                    new BigDecimal("1200"),
                    new BigDecimal("500"),
                    null,
                    null,
                    "Advances deducted"),
                new PayrollBatchPaymentRequest.PayrollLine(
                    "Labour B",
                    6,
                    new BigDecimal("950"),
                    BigDecimal.ZERO,
                    null,
                    null,
                    "Overtime included"),
                new PayrollBatchPaymentRequest.PayrollLine(
                    "Accountant Claude",
                    1,
                    new BigDecimal("10000"),
                    BigDecimal.ZERO,
                    null,
                    null,
                    "Monthly stub")));

    PayrollBatchPaymentResponse response = accountingService.processPayrollBatchPayment(request);

    // Gross = (5*1200) + (6*950) + (1*10000) = 6000 + 5700 + 10000 = 21700
    // Net = Gross - Advances = 21700 - 500 = 21200
    assertThat(response.grossAmount()).isEqualByComparingTo(new BigDecimal("21700.00"));
    assertThat(response.netPayAmount()).isEqualByComparingTo(new BigDecimal("21200.00"));
    assertThat(response.lines()).hasSize(3);

    PayrollRun run = payrollRunRepository.findById(response.payrollRunId()).orElseThrow();
    assertThat(run.getJournalEntry()).isNotNull();
    assertThat(run.getJournalEntryId()).isNotNull();
    assertThat(run.getTotalAmount())
        .isEqualByComparingTo(new BigDecimal("21700.00")); // Gross is stored

    // Integration tests share a single DB container across the suite; scope assertions to this run
    // only.
    List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
    assertThat(lines).hasSize(3);
    assertThat(
            lines.stream()
                .map(PayrollRunLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(new BigDecimal("21200.00")); // Net pay stored in line totals
  }
}
