package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CR_Reports_CashFlow_NotZeroByConstructionIT extends AbstractIntegrationTest {

    @Autowired private AccountingService accountingService;
    @Autowired private ReportService reportService;
    @Autowired private AccountRepository accountRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void cashFlow_netChangeReflectsCashMovement() {
        String companyCode = "CR-CF-" + System.nanoTime();
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        try {
            LocalDate entryDate = TestDateUtils.safeDate(company);
            Account cash = ensureAccount(company, "CASH-CF", "Cash", AccountType.ASSET);
            Account revenue = ensureAccount(company, "REV-CF", "Revenue", AccountType.REVENUE);

            postJournal(entryDate, List.of(
                    line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                    line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
            ));

            CashFlowDto cashFlow = reportService.cashFlow();

            assertThat(cashFlow.metadata().source()).isEqualTo(ReportSource.LIVE);
            assertThat(cashFlow.netChange()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(cashFlow.operating()).isEqualByComparingTo(cashFlow.netChange());
        } finally {
            CompanyContextHolder.clear();
        }
    }

    private Long postJournal(LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
        JournalEntryRequest request = new JournalEntryRequest(
                "CF-TEST-" + System.nanoTime(),
                entryDate,
                "CODE-RED cash flow",
                null,
                null,
                Boolean.FALSE,
                lines
        );
        return accountingService.createJournalEntry(request).id();
    }

    private JournalEntryRequest.JournalLineRequest line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return new JournalEntryRequest.JournalLineRequest(accountId, "line", debit, credit);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }
}
