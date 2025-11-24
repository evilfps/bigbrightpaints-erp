package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentResponse;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Accounting: Payroll batch payment")
public class PayrollBatchPaymentIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "PAY-BATCH";

    @Autowired
    private AccountingService accountingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @Autowired
    private PayrollRunLineRepository payrollRunLineRepository;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void should_create_payroll_run_lines_and_post_journal() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Payroll Batch Co");
        CompanyContextHolder.setCompanyId(company.getCode());

        Account cash = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account expense = accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXP")
                .orElseGet(() -> accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV")
                        .orElseThrow());

        PayrollBatchPaymentRequest request = new PayrollBatchPaymentRequest(
                LocalDate.now(),
                cash.getId(),
                expense.getId(),
                "PAY-" + System.currentTimeMillis(),
                "Weekly payroll batch",
                List.of(
                        new PayrollBatchPaymentRequest.PayrollLine("Labour A", 5, new BigDecimal("1200"), new BigDecimal("500"), "Advances deducted"),
                        new PayrollBatchPaymentRequest.PayrollLine("Labour B", 6, new BigDecimal("950"), BigDecimal.ZERO, "Overtime included"),
                        new PayrollBatchPaymentRequest.PayrollLine("Accountant Claude", 1, new BigDecimal("10000"), BigDecimal.ZERO, "Monthly stub")
                )
        );

        PayrollBatchPaymentResponse response = accountingService.processPayrollBatchPayment(request);

        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("20500.00"));
        assertThat(response.lines()).hasSize(3);

        PayrollRun run = payrollRunRepository.findById(response.payrollRunId()).orElseThrow();
        assertThat(run.getJournalEntry()).isNotNull();
        assertThat(run.getTotalAmount()).isEqualByComparingTo(new BigDecimal("20500.00"));

        List<PayrollRunLine> lines = payrollRunLineRepository.findAll();
        assertThat(lines).hasSize(3);
        assertThat(lines.stream().map(PayrollRunLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("20500.00"));
    }
}
