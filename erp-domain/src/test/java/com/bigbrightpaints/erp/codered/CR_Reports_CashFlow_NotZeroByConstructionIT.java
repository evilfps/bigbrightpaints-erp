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
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;

@Tag("critical")
@Tag("reconciliation")
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
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate entryDate = TestDateUtils.safeDate(company);
      Account cash = ensureAccount(company, "CASH-CF", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-CF", "Revenue", AccountType.REVENUE);

      postJournal(
          entryDate,
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

      CashFlowDto cashFlow = reportService.cashFlow();

      assertThat(cashFlow.metadata().source()).isEqualTo(ReportSource.LIVE);
      assertThat(cashFlow.netChange()).isEqualByComparingTo(new BigDecimal("100.00"));
      assertThat(cashFlow.operating()).isEqualByComparingTo(cashFlow.netChange());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void statements_useNaturalSigns_andCashFlowSplitsSections() {
    String companyCode = "CR-CF-SIGN-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate entryDate = TestDateUtils.safeDate(company);
      Account cash = ensureAccount(company, "CASH-SPLIT", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SPLIT", "Revenue", AccountType.REVENUE);
      Account expense =
          ensureAccount(company, "EXP-SPLIT", "Operating Expense", AccountType.EXPENSE);
      Account equipment = ensureAccount(company, "EQP-SPLIT", "Equipment", AccountType.ASSET);
      Account loan = ensureAccount(company, "LOAN-SPLIT", "Term Loan", AccountType.LIABILITY);

      postJournal(
          entryDate,
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));
      postJournal(
          entryDate,
          List.of(
              line(expense.getId(), new BigDecimal("40.00"), BigDecimal.ZERO),
              line(cash.getId(), BigDecimal.ZERO, new BigDecimal("40.00"))));
      postJournal(
          entryDate,
          List.of(
              line(equipment.getId(), new BigDecimal("60.00"), BigDecimal.ZERO),
              line(cash.getId(), BigDecimal.ZERO, new BigDecimal("60.00"))));
      postJournal(
          entryDate,
          List.of(
              line(cash.getId(), new BigDecimal("30.00"), BigDecimal.ZERO),
              line(loan.getId(), BigDecimal.ZERO, new BigDecimal("30.00"))));

      ProfitLossDto profitLoss = reportService.profitLoss(ReportQueryRequestBuilder.empty());
      BalanceSheetDto balanceSheet = reportService.balanceSheet(ReportQueryRequestBuilder.empty());
      CashFlowDto cashFlow = reportService.cashFlow();

      assertThat(profitLoss.revenue()).isEqualByComparingTo(new BigDecimal("100.00"));
      assertThat(profitLoss.operatingExpenses()).isEqualByComparingTo(new BigDecimal("40.00"));
      assertThat(profitLoss.netIncome()).isEqualByComparingTo(new BigDecimal("60.00"));

      assertThat(balanceSheet.totalAssets()).isEqualByComparingTo(new BigDecimal("90.00"));
      assertThat(balanceSheet.totalLiabilities()).isEqualByComparingTo(new BigDecimal("30.00"));
      assertThat(balanceSheet.totalEquity()).isEqualByComparingTo(new BigDecimal("60.00"));

      assertThat(cashFlow.operating()).isEqualByComparingTo(new BigDecimal("60.00"));
      assertThat(cashFlow.investing()).isEqualByComparingTo(new BigDecimal("-60.00"));
      assertThat(cashFlow.financing()).isEqualByComparingTo(new BigDecimal("30.00"));
      assertThat(cashFlow.netChange()).isEqualByComparingTo(new BigDecimal("30.00"));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void cashFlow_apportionsMixedCounterpartySections_withinSingleCashJournal() {
    String companyCode = "CR-CF-MIXED-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate entryDate = TestDateUtils.safeDate(company);
      Account cash = ensureAccount(company, "CASH-MIX", "Cash", AccountType.ASSET);
      Account expense = ensureAccount(company, "EXP-MIX", "Operating Expense", AccountType.EXPENSE);
      Account loan = ensureAccount(company, "LOAN-MIX", "Term Loan", AccountType.LIABILITY);

      postJournal(
          entryDate,
          List.of(
              line(expense.getId(), new BigDecimal("40.00"), BigDecimal.ZERO),
              line(loan.getId(), new BigDecimal("60.00"), BigDecimal.ZERO),
              line(cash.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

      CashFlowDto cashFlow = reportService.cashFlow();

      assertThat(cashFlow.operating()).isEqualByComparingTo(new BigDecimal("-40.00"));
      assertThat(cashFlow.investing()).isEqualByComparingTo(BigDecimal.ZERO.setScale(2));
      assertThat(cashFlow.financing()).isEqualByComparingTo(new BigDecimal("-60.00"));
      assertThat(cashFlow.netChange()).isEqualByComparingTo(new BigDecimal("-100.00"));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private Long postJournal(
      LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
    JournalEntryRequest request =
        new JournalEntryRequest(
            "CF-TEST-" + System.nanoTime(),
            entryDate,
            "CODE-RED cash flow",
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
