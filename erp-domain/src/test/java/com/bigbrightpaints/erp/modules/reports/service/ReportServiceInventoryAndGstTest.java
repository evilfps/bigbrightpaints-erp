package com.bigbrightpaints.erp.modules.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.service.CompanyScopedFactoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryPhysicalCountService;
import com.bigbrightpaints.erp.modules.reports.dto.GstReturnReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryReconciliationItemDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryReconciliationReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationGroupDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationItemDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationDashboardDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class ReportServiceInventoryAndGstTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingPeriodRepository accountingPeriodRepository;
  @Mock private AccountingPeriodSnapshotRepository snapshotRepository;
  @Mock private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private DealerLedgerRepository dealerLedgerRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyScopedFactoryLookupService factoryLookupService;
  @Mock private CompanyClock companyClock;
  @Mock private InventoryValuationQueryService inventoryValuationService;
  @Mock private TrialBalanceReportQueryService trialBalanceReportQueryService;
  @Mock private ProfitLossReportQueryService profitLossReportQueryService;
  @Mock private BalanceSheetReportQueryService balanceSheetReportQueryService;
  @Mock private AgedDebtorsReportQueryService agedDebtorsReportQueryService;
  @Mock private TaxService taxService;
  @Mock private InventoryPhysicalCountService inventoryPhysicalCountService;

  private ReportService reportService;
  private Company company;

  @BeforeEach
  void setUp() {
    reportService =
        new ReportService(
            companyContextService,
            accountRepository,
            accountingPeriodRepository,
            snapshotRepository,
            snapshotLineRepository,
            dealerRepository,
            dealerLedgerService,
            dealerLedgerRepository,
            journalEntryRepository,
            journalLineRepository,
            productionLogRepository,
            packingRecordRepository,
            inventoryMovementRepository,
            rawMaterialMovementRepository,
            accountingLookupService,
            factoryLookupService,
            companyClock,
            inventoryValuationService,
            trialBalanceReportQueryService,
            profitLossReportQueryService,
            balanceSheetReportQueryService,
            agedDebtorsReportQueryService,
            taxService,
            inventoryPhysicalCountService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 901L);
    company.setStateCode("27");
    company.setTimezone("UTC");

    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient()
        .when(inventoryPhysicalCountService.latestFinishedGoodCounts(any(), any()))
        .thenReturn(Map.of());
    lenient()
        .when(inventoryPhysicalCountService.latestRawMaterialCounts(any(), any()))
        .thenReturn(Map.of());
  }

  private void stubToday() {
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 20));
  }

  @Test
  void inventoryValuation_mapsItemsAndGroupingFromSnapshot() {
    stubToday();
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 10L);
    period.setYear(2026);
    period.setMonth(3);
    period.setStatus(AccountingPeriodStatus.OPEN);

    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(period));

    InventoryValuationQueryService.InventoryItemSnapshot rawItem =
        new InventoryValuationQueryService.InventoryItemSnapshot(
            1L,
            InventoryValuationQueryService.InventoryTypeBucket.RAW_MATERIAL,
            "RM-001",
            "Titanium",
            "RAW_MATERIAL",
            "Raw Materials",
            new BigDecimal("8"),
            BigDecimal.ZERO,
            new BigDecimal("8"),
            new BigDecimal("10"),
            new BigDecimal("80"),
            true);
    InventoryValuationQueryService.InventoryItemSnapshot fgItem =
        new InventoryValuationQueryService.InventoryItemSnapshot(
            2L,
            InventoryValuationQueryService.InventoryTypeBucket.FINISHED_GOOD,
            "FG-100",
            "Primer",
            "PAINT",
            "Shield",
            new BigDecimal("5"),
            new BigDecimal("2"),
            new BigDecimal("3"),
            new BigDecimal("20"),
            new BigDecimal("100"),
            false);

    InventoryValuationQueryService.InventorySnapshot snapshot =
        new InventoryValuationQueryService.InventorySnapshot(
            new BigDecimal("180.00"), 1L, "FIFO", List.of(rawItem, fgItem));

    when(inventoryValuationService.currentSnapshot(company)).thenReturn(snapshot);

    InventoryValuationDto response = reportService.inventoryValuation();

    assertThat(response.totalValue()).isEqualByComparingTo("180.00");
    assertThat(response.costingMethod()).isEqualTo("FIFO");
    assertThat(response.items()).hasSize(2);
    assertThat(response.items())
        .extracting(InventoryValuationItemDto::inventoryType)
        .containsExactly("RAW_MATERIAL", "FINISHED_GOOD");

    Map<String, InventoryValuationGroupDto> byCategory =
        response.groupByCategory().stream()
            .collect(
                java.util.stream.Collectors.toMap(InventoryValuationGroupDto::groupKey, g -> g));
    assertThat(byCategory.get("RAW_MATERIAL").totalValue()).isEqualByComparingTo("80.00");
    assertThat(byCategory.get("PAINT").totalValue()).isEqualByComparingTo("100.00");

    Map<String, InventoryValuationGroupDto> byBrand =
        response.groupByBrand().stream()
            .collect(
                java.util.stream.Collectors.toMap(InventoryValuationGroupDto::groupKey, g -> g));
    assertThat(byBrand.get("Raw Materials").lowStockItems()).isEqualTo(1);
    assertThat(byBrand.get("Shield").itemCount()).isEqualTo(1);
    assertThat(response.metadata().source()).isEqualTo(ReportSource.LIVE);
  }

  @Test
  void inventoryValuation_defaultsToFifoWhenSnapshotCostingMethodIsMissing() {
    stubToday();
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 11L);
    period.setYear(2026);
    period.setMonth(3);
    period.setStatus(AccountingPeriodStatus.OPEN);

    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(period));
    when(inventoryValuationService.currentSnapshot(company))
        .thenReturn(
            new InventoryValuationQueryService.InventorySnapshot(
                BigDecimal.ZERO, 0L, null, List.of()));

    InventoryValuationDto response = reportService.inventoryValuation();

    assertThat(response.totalValue()).isEqualByComparingTo("0.00");
    assertThat(response.costingMethod()).isEqualTo("FIFO");
    assertThat(response.items()).isEmpty();
    assertThat(response.groupByCategory()).isEmpty();
    assertThat(response.groupByBrand()).isEmpty();
  }

  @Test
  void inventoryReconciliationReport_usesIndependentPhysicalCountSource() {
    company.setDefaultInventoryAccountId(321L);
    Account inventoryAccount = account(321L, "INV", "Inventory", AccountType.ASSET, "90");
    when(accountingLookupService.requireAccount(company, 321L)).thenReturn(inventoryAccount);

    InventoryValuationQueryService.InventoryItemSnapshot finishedGoodItem =
        new InventoryValuationQueryService.InventoryItemSnapshot(
            41L,
            InventoryValuationQueryService.InventoryTypeBucket.FINISHED_GOOD,
            "FG-041",
            "Acrylic Primer",
            "PAINT",
            "Shield",
            new BigDecimal("10"),
            new BigDecimal("2"),
            new BigDecimal("8"),
            new BigDecimal("12"),
            new BigDecimal("120"),
            false);
    when(inventoryValuationService.currentSnapshot(company))
        .thenReturn(
            new InventoryValuationQueryService.InventorySnapshot(
                new BigDecimal("120"), 0L, "FIFO", List.of(finishedGoodItem)));
    when(inventoryPhysicalCountService.latestFinishedGoodCounts(company, List.of(41L)))
        .thenReturn(Map.of(41L, new BigDecimal("8")));

    InventoryReconciliationReportDto report = reportService.inventoryReconciliationReport();

    assertThat(report.systemQuantityTotal()).isEqualByComparingTo("10.00");
    assertThat(report.physicalQuantityTotal()).isEqualByComparingTo("8.00");
    assertThat(report.quantityVarianceTotal()).isEqualByComparingTo("-2.00");
    assertThat(report.physicalInventoryValue()).isEqualByComparingTo("96.00");
    assertThat(report.valueVariance()).isEqualByComparingTo("6.00");
    assertThat(report.items())
        .extracting(InventoryReconciliationItemDto::physicalQty)
        .containsExactly(new BigDecimal("8.00"));
  }

  @Test
  void inventoryReconciliationReport_fallsBackToSystemQuantityWithoutPhysicalCountInput() {
    company.setDefaultInventoryAccountId(322L);
    Account inventoryAccount = account(322L, "INV", "Inventory", AccountType.ASSET, "35");
    when(accountingLookupService.requireAccount(company, 322L)).thenReturn(inventoryAccount);

    InventoryValuationQueryService.InventoryItemSnapshot rawMaterialItem =
        new InventoryValuationQueryService.InventoryItemSnapshot(
            12L,
            InventoryValuationQueryService.InventoryTypeBucket.RAW_MATERIAL,
            "RM-012",
            "Resin",
            "RAW_MATERIAL",
            "Raw Materials",
            new BigDecimal("5"),
            BigDecimal.ZERO,
            new BigDecimal("5"),
            new BigDecimal("7"),
            new BigDecimal("35"),
            false);
    when(inventoryValuationService.currentSnapshot(company))
        .thenReturn(
            new InventoryValuationQueryService.InventorySnapshot(
                new BigDecimal("35"), 0L, "FIFO", List.of(rawMaterialItem)));

    InventoryReconciliationReportDto report = reportService.inventoryReconciliationReport();

    assertThat(report.systemQuantityTotal()).isEqualByComparingTo("5.00");
    assertThat(report.physicalQuantityTotal()).isEqualByComparingTo("5.00");
    assertThat(report.quantityVarianceTotal()).isEqualByComparingTo("0.00");
    assertThat(report.items())
        .extracting(InventoryReconciliationItemDto::variance)
        .containsExactly(new BigDecimal("0.00"));
  }

  @Test
  void balanceSheet_requiresExplicitQueryRequest() {
    assertThatThrownBy(() -> reportService.balanceSheet((FinancialReportQueryRequest) null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Financial report query request is required");
  }

  @Test
  void profitLoss_requiresExplicitQueryRequest() {
    assertThatThrownBy(() -> reportService.profitLoss((FinancialReportQueryRequest) null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Financial report query request is required");
  }

  @Test
  void balanceWarnings_flagsUnexpectedSignsAcrossAccountTypes() {
    Account asset = account(1L, "1000", "Inventory Asset", AccountType.ASSET, "-10");
    Account liability = account(2L, "2000", "GST Liability", AccountType.LIABILITY, "5");
    Account revenue = account(3L, "3000", "Sales Revenue", AccountType.REVENUE, "7");
    Account expense = account(4L, "4000", "Factory Expense", AccountType.EXPENSE, "-3");
    when(accountRepository.findByCompanyOrderByCodeAsc(company))
        .thenReturn(List.of(asset, liability, revenue, expense));

    var warnings = reportService.balanceWarnings();

    assertThat(warnings).hasSize(4);
    assertThat(warnings)
        .extracting(warning -> warning.reason())
        .containsExactly(
            "Asset account has a credit balance",
            "Liability account has a debit balance",
            "Revenue account shows a debit balance",
            "Expense account shows a credit balance");
    assertThat(warnings)
        .extracting(warning -> warning.warningType())
        .containsExactly(
            "ASSET_CREDIT_BALANCE",
            "LIABILITY_DEBIT_BALANCE",
            "REVENUE_DEBIT_BALANCE",
            "EXPENSE_CREDIT_BALANCE");
    assertThat(warnings).allMatch(warning -> warning.threshold().compareTo(BigDecimal.ZERO) == 0);
  }

  @Test
  void reconciliationDashboard_usesProvidedStatementBalanceAndInventoryFallbackLedgerBalance() {
    Account bankAccount = account(10L, "BANK", "Main Bank", AccountType.ASSET, "1000");
    Account inventoryAccount = account(11L, "INV", "Inventory Control", AccountType.ASSET, "400");
    when(accountingLookupService.requireAccount(company, 10L)).thenReturn(bankAccount);
    when(accountRepository.findByCompanyOrderByCodeAsc(company))
        .thenReturn(List.of(inventoryAccount));
    when(inventoryValuationService.currentSnapshot(company))
        .thenReturn(
            new InventoryValuationQueryService.InventorySnapshot(
                new BigDecimal("450"), 1L, "FIFO", List.of()));

    ReconciliationDashboardDto dashboard =
        reportService.reconciliationDashboard(10L, new BigDecimal("940"));

    assertThat(dashboard.inventory().variance()).isEqualByComparingTo("50.00");
    assertThat(dashboard.bank().variance()).isEqualByComparingTo("60.00");
    assertThat(dashboard.inventory().balanced()).isFalse();
    assertThat(dashboard.bank().balanced()).isFalse();
    assertThat(dashboard.subledger().balanced()).isTrue();
    assertThat(dashboard.subledger().difference()).isEqualByComparingTo("0.00");
    assertThat(dashboard.balanceWarnings()).isEmpty();
  }

  private Account account(Long id, String code, String name, AccountType type, String balance) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    account.setBalance(new BigDecimal(balance));
    return account;
  }

  @Test
  void agedDebtors_requiresExplicitQueryRequest() {
    assertThatThrownBy(() -> reportService.agedDebtors(null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Financial report query request is required");
  }

  @Test
  void trialBalance_requiresExplicitQueryRequest() {
    assertThatThrownBy(() -> reportService.trialBalance((FinancialReportQueryRequest) null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Financial report query request is required");
  }

  @Test
  void balanceSheet_delegatesExplicitQueryRequestToQueryService() {
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(
            null,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null);
    var expected =
        new com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    when(balanceSheetReportQueryService.generate(request)).thenReturn(expected);

    assertThat(reportService.balanceSheet(request)).isEqualTo(expected);
    verify(balanceSheetReportQueryService).generate(request);
  }

  @Test
  void profitLoss_delegatesExplicitQueryRequestToQueryService() {
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(
            null,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null);
    var expected =
        new com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);
    when(profitLossReportQueryService.generate(request)).thenReturn(expected);

    assertThat(reportService.profitLoss(request)).isEqualTo(expected);
    verify(profitLossReportQueryService).generate(request);
  }

  @Test
  void agedDebtors_delegatesExplicitQueryRequestToQueryService() {
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(
            null,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null);
    when(agedDebtorsReportQueryService.generate(request)).thenReturn(List.of());

    assertThat(reportService.agedDebtors(request)).isEmpty();
    verify(agedDebtorsReportQueryService).generate(request);
  }

  @Test
  void trialBalance_delegatesExplicitQueryRequestToQueryService() {
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(
            null,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null);
    var expected =
        new com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto(
            List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, null, null);
    when(trialBalanceReportQueryService.generate(request)).thenReturn(expected);

    assertThat(reportService.trialBalance(request)).isEqualTo(expected);
    verify(trialBalanceReportQueryService).generate(request);
  }

  @Test
  void gstReturn_delegatesToCanonicalAccountingOwnerAndMapsComponentBreakdown() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 25L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.findByCompanyAndId(company, 25L))
        .thenReturn(Optional.of(period));

    GstReconciliationDto reconciliation = new GstReconciliationDto();
    reconciliation.setPeriodStart(LocalDate.of(2026, 2, 1));
    reconciliation.setPeriodEnd(LocalDate.of(2026, 2, 28));
    reconciliation.setCollected(
        new GstReconciliationDto.GstComponentSummary(
            new BigDecimal("9.00"),
            new BigDecimal("9.00"),
            BigDecimal.ZERO,
            new BigDecimal("18.00")));
    reconciliation.setInputTaxCredit(
        new GstReconciliationDto.GstComponentSummary(
            new BigDecimal("2.00"),
            new BigDecimal("2.00"),
            new BigDecimal("4.00"),
            new BigDecimal("8.00")));
    reconciliation.setNetLiability(
        new GstReconciliationDto.GstComponentSummary(
            new BigDecimal("7.00"),
            new BigDecimal("7.00"),
            new BigDecimal("-4.00"),
            new BigDecimal("10.00")));
    when(taxService.generateGstReconciliation(YearMonth.of(2026, 2))).thenReturn(reconciliation);

    GstReturnReportDto report = reportService.gstReturn(25L);

    assertThat(report.periodId()).isEqualTo(25L);
    assertThat(report.periodStart()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(report.periodEnd()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(report.outputTax().total()).isEqualByComparingTo("18.00");
    assertThat(report.inputTaxCredit().total()).isEqualByComparingTo("8.00");
    assertThat(report.netLiability().total()).isEqualByComparingTo("10.00");
    assertThat(report.rateSummaries()).isEmpty();
    assertThat(report.transactionDetails()).isEmpty();
    assertThat(report.metadata().source()).isEqualTo(ReportSource.LIVE);

    verify(taxService).generateGstReconciliation(YearMonth.of(2026, 2));
  }

  @Test
  void gstReturn_usesPeriodDatesWhenReconciliationDatesAreMissing() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 26L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.OPEN);
    when(accountingPeriodRepository.findByCompanyAndId(company, 26L))
        .thenReturn(Optional.of(period));

    when(taxService.generateGstReconciliation(YearMonth.of(2026, 2)))
        .thenReturn(new GstReconciliationDto());

    GstReturnReportDto report = reportService.gstReturn(26L);

    assertThat(report.periodStart()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(report.periodEnd()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(report.outputTax().total()).isEqualByComparingTo("0.00");
    assertThat(report.inputTaxCredit().total()).isEqualByComparingTo("0.00");
    assertThat(report.netLiability().total()).isEqualByComparingTo("0.00");
    verify(taxService).generateGstReconciliation(YearMonth.of(2026, 2));
  }

  @Test
  void gstReturn_withoutPeriodIdFallsBackToCurrentMonthPeriod() {
    stubToday();
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 31L);
    period.setYear(2026);
    period.setMonth(3);
    period.setStartDate(LocalDate.of(2026, 3, 1));
    period.setEndDate(LocalDate.of(2026, 3, 31));
    period.setStatus(AccountingPeriodStatus.OPEN);

    when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3))
        .thenReturn(Optional.of(period));
    when(taxService.generateGstReconciliation(YearMonth.of(2026, 3)))
        .thenReturn(new GstReconciliationDto());

    GstReturnReportDto report = reportService.gstReturn(null);

    assertThat(report.periodId()).isEqualTo(31L);
    assertThat(report.rateSummaries()).isEmpty();
    assertThat(report.transactionDetails()).isEmpty();
    assertThat(report.netLiability().total()).isEqualByComparingTo("0.00");
    verify(taxService).generateGstReconciliation(YearMonth.of(2026, 3));
  }
}
