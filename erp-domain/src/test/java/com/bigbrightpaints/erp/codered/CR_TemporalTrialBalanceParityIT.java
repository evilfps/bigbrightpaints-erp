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
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;

@Tag("critical")
@Tag("reconciliation")
class CR_TemporalTrialBalanceParityIT extends AbstractIntegrationTest {

  @Autowired private AccountingService accountingService;
  @Autowired private TemporalBalanceService temporalBalanceService;
  @Autowired private ReportService reportService;
  @Autowired private AccountRepository accountRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void openPeriodTemporalTrialBalance_matchesReportSignConventions() {
    String companyCode = "CR-OPEN-TB-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate asOfDate = TestDateUtils.safeDate(company);
      Account cash =
          ensureAccount(company, "CASH-OPEN-TB-" + System.nanoTime(), "Cash", AccountType.ASSET);
      Account revenue =
          ensureAccount(
              company, "REV-OPEN-TB-" + System.nanoTime(), "Revenue", AccountType.REVENUE);

      postJournal(
          asOfDate,
          List.of(
              line(cash.getId(), new BigDecimal("125.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("125.00"))));

      TemporalBalanceService.TrialBalanceSnapshot temporal =
          temporalBalanceService.getTrialBalanceAsOf(asOfDate);
      TrialBalanceDto report = reportService.trialBalance(asOfDate);

      TemporalBalanceService.TrialBalanceEntry temporalRevenue =
          temporal.entries().stream()
              .filter(entry -> revenue.getId().equals(entry.accountId()))
              .findFirst()
              .orElseThrow();
      TrialBalanceDto.Row reportRevenue =
          report.rows().stream()
              .filter(row -> revenue.getId().equals(row.accountId()))
              .findFirst()
              .orElseThrow();

      assertThat(temporalRevenue.debit()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(temporalRevenue.credit()).isEqualByComparingTo("125.00");
      assertThat(reportRevenue.debit()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(reportRevenue.credit()).isEqualByComparingTo("125.00");
      assertThat(temporal.totalDebits()).isEqualByComparingTo(report.totalDebit());
      assertThat(temporal.totalCredits()).isEqualByComparingTo(report.totalCredit());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private Long postJournal(
      LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
    JournalEntryRequest request =
        new JournalEntryRequest(
            "OPEN-TB-TEST-" + System.nanoTime(),
            entryDate,
            "CODE-RED open period parity",
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
