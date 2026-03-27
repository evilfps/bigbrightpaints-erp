package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;

@Tag("critical")
@Tag("reconciliation")
class CR_Reports_AsOfBalancesStable_AfterLatePostingIT extends AbstractIntegrationTest {

  @Autowired private AccountingService accountingService;
  @Autowired private ReportService reportService;
  @Autowired private AccountRepository accountRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void trialBalanceAsOf_isStableAfterLatePosting() {
    String companyCode = "CR-ASOF-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate asOfDate = TestDateUtils.safeDate(company);
      Account cash = ensureAccount(company, "CASH-ASOF", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-ASOF", "Revenue", AccountType.REVENUE);

      postJournal(
          asOfDate,
          List.of(
              line(cash.getId(), new BigDecimal("200.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("200.00"))));

      TrialBalanceDto before = reportService.trialBalance(asOfDate);

      postJournal(
          asOfDate.plusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))));

      TrialBalanceDto after = reportService.trialBalance(asOfDate);

      assertThat(before.metadata().source()).isEqualTo(ReportSource.AS_OF);
      assertThat(after.metadata().source()).isEqualTo(ReportSource.AS_OF);
      assertThat(after.totalDebit()).isEqualByComparingTo(before.totalDebit());
      assertThat(after.totalCredit()).isEqualByComparingTo(before.totalCredit());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private Long postJournal(
      LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
    JournalEntryRequest request =
        new JournalEntryRequest(
            "ASOF-TEST-" + System.nanoTime(),
            entryDate,
            "CODE-RED as-of",
            null,
            null,
            Boolean.FALSE,
            lines);
    return accountingService.createJournalEntry(request).id();
  }

  private JournalEntryRequest.JournalLineRequest line(
      Long accountId, BigDecimal debit, BigDecimal credit) {
    return new JournalEntryRequest.JournalLineRequest(accountId, "line", debit, credit);
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
              return accountRepository.save(account);
            });
  }
}
