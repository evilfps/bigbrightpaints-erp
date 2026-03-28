package com.bigbrightpaints.erp.modules.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
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
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.reports.dto.GstReturnReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationGroupDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationItemDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationDashboardDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
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
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private CompanyClock companyClock;
  @Mock private InventoryValuationService inventoryValuationService;
  @Mock private TrialBalanceReportQueryService trialBalanceReportQueryService;
  @Mock private ProfitLossReportQueryService profitLossReportQueryService;
  @Mock private BalanceSheetReportQueryService balanceSheetReportQueryService;
  @Mock private AgedDebtorsReportQueryService agedDebtorsReportQueryService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  private final GstService gstService = new GstService();

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
            companyEntityLookup,
            companyClock,
            inventoryValuationService,
            trialBalanceReportQueryService,
            profitLossReportQueryService,
            balanceSheetReportQueryService,
            agedDebtorsReportQueryService,
            invoiceRepository,
            rawMaterialPurchaseRepository,
            gstService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 901L);
    company.setStateCode("27");
    company.setTimezone("UTC");

    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
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

    InventoryValuationService.InventoryItemSnapshot rawItem =
        new InventoryValuationService.InventoryItemSnapshot(
            1L,
            InventoryValuationService.InventoryTypeBucket.RAW_MATERIAL,
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
    InventoryValuationService.InventoryItemSnapshot fgItem =
        new InventoryValuationService.InventoryItemSnapshot(
            2L,
            InventoryValuationService.InventoryTypeBucket.FINISHED_GOOD,
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

    InventoryValuationService.InventorySnapshot snapshot =
        new InventoryValuationService.InventorySnapshot(
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
            new InventoryValuationService.InventorySnapshot(BigDecimal.ZERO, 0L, null, List.of()));

    InventoryValuationDto response = reportService.inventoryValuation();

    assertThat(response.totalValue()).isEqualByComparingTo("0.00");
    assertThat(response.costingMethod()).isEqualTo("FIFO");
    assertThat(response.items()).isEmpty();
    assertThat(response.groupByCategory()).isEmpty();
    assertThat(response.groupByBrand()).isEmpty();
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
  }

  @Test
  void reconciliationDashboard_usesProvidedStatementBalanceAndInventoryFallbackLedgerBalance() {
    Account bankAccount = account(10L, "BANK", "Main Bank", AccountType.ASSET, "1000");
    Account inventoryAccount = account(11L, "INV", "Inventory Control", AccountType.ASSET, "400");
    when(companyEntityLookup.requireAccount(company, 10L)).thenReturn(bankAccount);
    when(accountRepository.findByCompanyOrderByCodeAsc(company))
        .thenReturn(List.of(inventoryAccount));
    when(inventoryValuationService.currentSnapshot(company))
        .thenReturn(
            new InventoryValuationService.InventorySnapshot(
                new BigDecimal("450"), 1L, "FIFO", List.of()));

    ReconciliationDashboardDto dashboard =
        reportService.reconciliationDashboard(10L, new BigDecimal("940"));

    assertThat(dashboard.inventoryVariance()).isEqualByComparingTo("50");
    assertThat(dashboard.bankVariance()).isEqualByComparingTo("60");
    assertThat(dashboard.inventoryBalanced()).isFalse();
    assertThat(dashboard.bankBalanced()).isFalse();
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
  void gstReturn_aggregatesRateSummaryComponentsAndTransactionDetails() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 25L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.findByCompanyAndId(company, 25L))
        .thenReturn(Optional.of(period));

    Dealer dealer = new Dealer();
    dealer.setName("Dealer One");
    dealer.setStateCode("27");

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 101L);
    invoice.setInvoiceNumber("INV-101");
    invoice.setIssueDate(LocalDate.of(2026, 2, 10));
    invoice.setStatus("POSTED");
    invoice.setDealer(dealer);

    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setTaxRate(new BigDecimal("18"));
    invoiceLine.setTaxableAmount(new BigDecimal("100"));
    invoiceLine.setTaxAmount(new BigDecimal("18"));
    invoiceLine.setCgstAmount(new BigDecimal("9"));
    invoiceLine.setSgstAmount(new BigDecimal("9"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    Supplier supplier = new Supplier();
    supplier.setName("Supplier One");
    supplier.setStateCode("29");

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    ReflectionTestUtils.setField(purchase, "id", 202L);
    purchase.setInvoiceNumber("PUR-202");
    purchase.setInvoiceDate(LocalDate.of(2026, 2, 12));
    purchase.setStatus("POSTED");
    purchase.setSupplier(supplier);

    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setTaxRate(new BigDecimal("18"));
    purchaseLine.setQuantity(new BigDecimal("10"));
    purchaseLine.setReturnedQuantity(new BigDecimal("2"));
    purchaseLine.setCostPerUnit(new BigDecimal("10"));
    purchaseLine.setLineTotal(new BigDecimal("118"));
    purchaseLine.setTaxAmount(new BigDecimal("18"));
    purchaseLine.setCgstAmount(BigDecimal.ZERO);
    purchaseLine.setSgstAmount(BigDecimal.ZERO);
    purchaseLine.setIgstAmount(new BigDecimal("18"));
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(invoice));

    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(purchase));

    GstReturnReportDto report = reportService.gstReturn(25L);

    assertThat(report.periodId()).isEqualTo(25L);
    assertThat(report.outputTax().total()).isEqualByComparingTo("18.00");
    assertThat(report.inputTaxCredit().total()).isEqualByComparingTo("14.40");
    assertThat(report.netLiability().total()).isEqualByComparingTo("3.60");
    assertThat(report.rateSummaries()).hasSize(1);

    GstReturnReportDto.GstRateSummary summary = report.rateSummaries().getFirst();
    assertThat(summary.taxRate()).isEqualByComparingTo("18.00");
    assertThat(summary.taxableAmount()).isEqualByComparingTo("180.00");
    assertThat(summary.outputTax()).isEqualByComparingTo("18.00");
    assertThat(summary.inputTaxCredit()).isEqualByComparingTo("14.40");

    assertThat(report.transactionDetails()).hasSize(2);
    assertThat(report.transactionDetails())
        .extracting(GstReturnReportDto.GstTransactionDetail::direction)
        .containsExactly("OUTPUT", "INPUT");
    assertThat(report.metadata().source()).isEqualTo(ReportSource.SNAPSHOT);
  }

  @Test
  void gstReturn_rejectsTaxedInvoiceLinesMissingCanonicalTaxableAmount() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 25L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.findByCompanyAndId(company, 25L))
        .thenReturn(Optional.of(period));

    Dealer dealer = new Dealer();
    dealer.setName("Dealer One");
    dealer.setStateCode("27");

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 101L);
    invoice.setInvoiceNumber("INV-101");
    invoice.setIssueDate(LocalDate.of(2026, 2, 10));
    invoice.setStatus("POSTED");
    invoice.setDealer(dealer);

    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setQuantity(BigDecimal.ONE);
    invoiceLine.setUnitPrice(new BigDecimal("100"));
    invoiceLine.setTaxRate(new BigDecimal("18"));
    invoiceLine.setLineTotal(new BigDecimal("118"));
    invoiceLine.setTaxAmount(new BigDecimal("18"));
    invoiceLine.setCgstAmount(new BigDecimal("9"));
    invoiceLine.setSgstAmount(new BigDecimal("9"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of());

    assertThatThrownBy(() -> reportService.gstReturn(25L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION);
              assertThat(applicationException.getMessage()).contains("INV-101");
              assertThat(applicationException.getMessage())
                  .contains("taxable amount is required and must be non-negative");
            });
  }

  @Test
  void gstReturn_rejectsTaxedInvoiceLinesWithNegativeTaxableAmount() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 26L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.findByCompanyAndId(company, 26L))
        .thenReturn(Optional.of(period));

    Dealer dealer = new Dealer();
    dealer.setName("Dealer Two");
    dealer.setStateCode("27");

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 102L);
    invoice.setInvoiceNumber("INV-102");
    invoice.setIssueDate(LocalDate.of(2026, 2, 11));
    invoice.setStatus("POSTED");
    invoice.setDealer(dealer);

    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setQuantity(BigDecimal.ONE);
    invoiceLine.setUnitPrice(new BigDecimal("100"));
    invoiceLine.setTaxRate(new BigDecimal("18"));
    invoiceLine.setTaxableAmount(new BigDecimal("-1"));
    invoiceLine.setLineTotal(new BigDecimal("118"));
    invoiceLine.setTaxAmount(new BigDecimal("18"));
    invoiceLine.setCgstAmount(new BigDecimal("9"));
    invoiceLine.setSgstAmount(new BigDecimal("9"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of());

    assertThatThrownBy(() -> reportService.gstReturn(26L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION);
              assertThat(applicationException.getMessage()).contains("INV-102");
              assertThat(applicationException.getMessage())
                  .contains("taxable amount is required and must be non-negative");
            });
  }

  @Test
  void gstReturn_rejectsTaxedInvoiceLinesMissingTaxableAmountWithoutInvoiceNumber() {
    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 27L);
    period.setYear(2026);
    period.setMonth(2);
    period.setStartDate(LocalDate.of(2026, 2, 1));
    period.setEndDate(LocalDate.of(2026, 2, 28));
    period.setStatus(AccountingPeriodStatus.CLOSED);

    when(accountingPeriodRepository.findByCompanyAndId(company, 27L))
        .thenReturn(Optional.of(period));

    Dealer dealer = new Dealer();
    dealer.setName("Dealer Three");
    dealer.setStateCode("27");

    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", 103L);
    invoice.setIssueDate(LocalDate.of(2026, 2, 12));
    invoice.setStatus("POSTED");
    invoice.setDealer(dealer);

    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setQuantity(BigDecimal.ONE);
    invoiceLine.setUnitPrice(new BigDecimal("100"));
    invoiceLine.setTaxRate(new BigDecimal("18"));
    invoiceLine.setLineTotal(new BigDecimal("118"));
    invoiceLine.setTaxAmount(new BigDecimal("18"));
    invoiceLine.setCgstAmount(new BigDecimal("9"));
    invoiceLine.setSgstAmount(new BigDecimal("9"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of());

    assertThatThrownBy(() -> reportService.gstReturn(27L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION);
              assertThat(applicationException.getMessage()).contains("unknown");
              assertThat(applicationException.getMessage())
                  .contains("taxable amount is required and must be non-negative");
            });
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
    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(any(), any(), any()))
        .thenReturn(List.of());
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            any(), any(), any()))
        .thenReturn(List.of());

    GstReturnReportDto report = reportService.gstReturn(null);

    assertThat(report.periodId()).isEqualTo(31L);
    assertThat(report.rateSummaries()).isEmpty();
    assertThat(report.transactionDetails()).isEmpty();
    assertThat(report.netLiability().total()).isEqualByComparingTo("0.00");
  }
}
