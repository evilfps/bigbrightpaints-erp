package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("reconciliation")
@TestPropertySource(properties = "erp.benchmark.override-date=2025-03-29")
class CR_PeriodCloseSnapshotsTest extends AbstractIntegrationTest {

  @Autowired private AccountingPeriodService accountingPeriodService;
  @Autowired private AccountingService accountingService;
  @Autowired private TemporalBalanceService temporalBalanceService;
  @Autowired private ReportService reportService;
  @Autowired private AccountRepository accountRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void trialBalanceAsOf_usesSnapshotForClosedPeriod() {
    String companyCode = "CR-SNAP-GL-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      TemporalBalanceService.TrialBalanceSnapshot before =
          temporalBalanceService.getTrialBalanceAsOf(period.getEndDate());

      postJournal(
          period.getEndDate().plusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))));

      TemporalBalanceService.TrialBalanceSnapshot after =
          temporalBalanceService.getTrialBalanceAsOf(period.getEndDate());

      assertThat(after.totalDebits()).isEqualByComparingTo(before.totalDebits());
      assertThat(after.totalCredits()).isEqualByComparingTo(before.totalCredits());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void accountBalanceAsOf_closedSnapshot_normalizesCreditNaturalSign() {
    String companyCode = "CR-SNAP-BAL-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-BAL", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-BAL", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      BigDecimal cashBalance =
          temporalBalanceService.getBalanceAsOfDate(cash.getId(), period.getEndDate());
      BigDecimal revenueBalance =
          temporalBalanceService.getBalanceAsOfDate(revenue.getId(), period.getEndDate());

      assertThat(cashBalance).isEqualByComparingTo("100.00");
      assertThat(revenueBalance).isEqualByComparingTo("100.00");
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void accountBalanceAsOf_creditNormal_isStableAcrossClosedOpenBoundary() {
    String companyCode = "CR-SNAP-BND-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-BND", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-BND", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));
      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      LocalDate closedDate = period.getEndDate();
      LocalDate nextOpenDate = closedDate.plusDays(1);
      BigDecimal closedRevenueBalance =
          temporalBalanceService.getBalanceAsOfDate(revenue.getId(), closedDate);
      BigDecimal openRevenueBalance =
          temporalBalanceService.getBalanceAsOfDate(revenue.getId(), nextOpenDate);
      Map<Long, BigDecimal> openBalances =
          temporalBalanceService.getBalancesAsOfDate(List.of(revenue.getId()), nextOpenDate);

      assertThat(closedRevenueBalance).isEqualByComparingTo("100.00");
      assertThat(openRevenueBalance).isEqualByComparingTo(closedRevenueBalance);
      assertThat(openBalances.get(revenue.getId())).isEqualByComparingTo(closedRevenueBalance);
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void balanceSheetAsOf_usesSnapshotForClosedPeriod() {
    String companyCode = "CR-SNAP-BS-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-BS", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-BS", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("120.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("120.00"))));

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      BalanceSheetDto before = reportService.balanceSheet(period.getEndDate());

      postJournal(
          period.getEndDate().plusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("30.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("30.00"))));

      BalanceSheetDto after = reportService.balanceSheet(period.getEndDate());
      BalanceSheetDto live = reportService.balanceSheet(ReportQueryRequestBuilder.empty());

      assertThat(after.totalAssets()).isEqualByComparingTo(before.totalAssets());
      assertThat(after.metadata().source()).isEqualTo(ReportSource.SNAPSHOT);
      assertThat(after.metadata().snapshotId()).isNotNull();
      assertThat(live.metadata().source()).isEqualTo(ReportSource.LIVE);
      assertThat(live.totalAssets()).isEqualByComparingTo(new BigDecimal("30.00"));
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void profitLossClosedPeriod_remainsLiveAndPreservesPreCloseNetIncome() {
    String companyCode = "CR-SNAP-PL-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-PL", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-PL", "Revenue", AccountType.REVENUE);
      Account expense = ensureAccount(company, "EXP-SNAP-PL", "Expense", AccountType.EXPENSE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));
      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(expense.getId(), new BigDecimal("40.00"), BigDecimal.ZERO),
              line(cash.getId(), BigDecimal.ZERO, new BigDecimal("40.00"))));

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      ProfitLossDto closedPeriodProfitLoss =
          reportService.profitLoss(
              ReportQueryRequestBuilder.fromPeriodAndRange(
                  period.getId(), null, null, null, null, null, null));

      assertThat(closedPeriodProfitLoss.netIncome()).isEqualByComparingTo(new BigDecimal("60.00"));
      assertThat(closedPeriodProfitLoss.metadata().source()).isEqualTo(ReportSource.LIVE);
      assertThat(closedPeriodProfitLoss.metadata().snapshotId()).isNull();
      assertThat(closedPeriodProfitLoss.metadata().accountingPeriodId()).isEqualTo(period.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void trialBalanceReportAsOf_includesSnapshotMetadata() {
    String companyCode = "CR-SNAP-TB-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-TB", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-TB", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("80.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("80.00"))));

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto report =
          reportService.trialBalance(period.getEndDate());

      assertThat(report.metadata().source()).isEqualTo(ReportSource.SNAPSHOT);
      assertThat(report.metadata().snapshotId()).isNotNull();
      assertThat(report.metadata().accountingPeriodId()).isEqualTo(period.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void inventoryValuationAsOf_usesSnapshotForClosedPeriod() {
    String companyCode = "CR-SNAP-INV-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = CompanyTime.today(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);

      RawMaterial material = new RawMaterial();
      material.setCompany(company);
      material.setName("Resin");
      material.setSku("RM-SNAP-" + System.nanoTime());
      material.setUnitType("KG");
      material.setReorderLevel(new BigDecimal("5"));
      material.setCurrentStock(new BigDecimal("10"));
      material = rawMaterialRepository.save(material);

      RawMaterialBatch batch = new RawMaterialBatch();
      batch.setRawMaterial(material);
      batch.setBatchCode("BATCH-1");
      batch.setQuantity(new BigDecimal("10"));
      batch.setUnit("KG");
      batch.setCostPerUnit(new BigDecimal("5"));
      batch.setReceivedAt(CompanyTime.now(company));
      rawMaterialBatchRepository.save(batch);

      forceClosePeriod(period.getId(), "snapshot close request", "snapshot close approval");

      InventoryValuationDto before = reportService.inventoryValuationAsOf(period.getEndDate());

      material.setCurrentStock(new BigDecimal("20"));
      material = rawMaterialRepository.save(material);
      RawMaterialBatch batch2 = new RawMaterialBatch();
      batch2.setRawMaterial(material);
      batch2.setBatchCode("BATCH-2");
      batch2.setQuantity(new BigDecimal("10"));
      batch2.setUnit("KG");
      batch2.setCostPerUnit(new BigDecimal("8"));
      batch2.setReceivedAt(CompanyTime.now(company));
      rawMaterialBatchRepository.save(batch2);

      InventoryValuationDto after = reportService.inventoryValuationAsOf(period.getEndDate());

      assertThat(after.totalValue()).isEqualByComparingTo(before.totalValue());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private Long postJournal(
      LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
    JournalEntryRequest request =
        new JournalEntryRequest(
            "SNAP-TEST-" + System.nanoTime(),
            entryDate,
            "CODE-RED snapshot",
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

  private void forceClosePeriod(Long periodId, String requestNote, String approvalNote) {
    authenticate("maker.user", "ROLE_ACCOUNTING");
    accountingPeriodService.requestPeriodClose(
        periodId, new PeriodCloseRequestActionRequest(requestNote, true));
    authenticate("checker.user", "ROLE_ADMIN");
    accountingPeriodService.approvePeriodClose(
        periodId, new PeriodCloseRequestActionRequest(approvalNote, true));
  }

  private void authenticate(String username, String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                java.util.Arrays.stream(roles)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                    .toList()));
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
