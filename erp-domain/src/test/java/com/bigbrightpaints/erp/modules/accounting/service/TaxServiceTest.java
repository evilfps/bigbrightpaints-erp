package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReportBreakdownDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class TaxServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyAccountingSettingsService companyAccountingSettingsService;
  @Mock private CompanyClock companyClock;
  @Mock private JournalLineRepository journalLineRepository;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  private TaxService taxService;
  private Company company;
  private final GstService gstService = new GstService();

  @BeforeEach
  void setup() {
    taxService =
        new TaxService(
            companyContextService,
            companyAccountingSettingsService,
            companyClock,
            journalLineRepository,
            gstService,
            invoiceRepository,
            rawMaterialPurchaseRepository);
    company = new Company();
    company.setCode("BBP");
    company.setStateCode("27");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(companyClock.today(company)).thenReturn(LocalDate.of(2024, 12, 15));
  }

  @Test
  void generateGstReturn_sumsOutputAndInputTax() {
    YearMonth period = YearMonth.of(2024, 1);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(List.of(line(null, new BigDecimal("100.00")))); // output tax: credit - debit

    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(
            List.of(line(new BigDecimal("60.00"), BigDecimal.ZERO))); // input tax: debit - credit

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getPeriod()).isEqualTo(period);
    assertThat(dto.getPeriodStart()).isEqualTo(start);
    assertThat(dto.getPeriodEnd()).isEqualTo(end);
    assertThat(dto.getOutputTax()).isEqualByComparingTo("100.00");
    assertThat(dto.getInputTax()).isEqualByComparingTo("60.00");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("40.00");
  }

  @Test
  void generateGstReturn_roundsToCurrencyScale() {
    YearMonth period = YearMonth.of(2024, 2);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(List.of(line(BigDecimal.ZERO, new BigDecimal("10.005"))));
    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(List.of(line(new BigDecimal("2.005"), BigDecimal.ZERO)));

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getOutputTax()).isEqualByComparingTo("10.01");
    assertThat(dto.getInputTax()).isEqualByComparingTo("2.01");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("8.00");
  }

  @Test
  void generateGstReturn_treatsNullRepositoryResultsAsZero() {
    LocalDate today = LocalDate.of(2024, 3, 18);
    YearMonth period = YearMonth.from(today);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyClock.today(company)).thenReturn(today);
    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));
    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(null);
    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(null);

    GstReturnDto dto = taxService.generateGstReturn(null);

    assertThat(dto.getPeriod()).isEqualTo(period);
    assertThat(dto.getOutputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("0.00");
  }

  @Test
  void generateGstReturn_routesLiabilitySignalWithoutDoubleRoundingAcrossAccounts() {
    YearMonth period = YearMonth.of(2024, 6);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

    // Output net: +0.005 liability signal (credit-heavy).
    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(List.of(line(BigDecimal.ZERO, new BigDecimal("0.005"))));
    // Input net: -0.005 contra signal to liability (credit-heavy for input account).
    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(List.of(line(BigDecimal.ZERO, new BigDecimal("0.005"))));

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getOutputTax()).isEqualByComparingTo("0.01");
    assertThat(dto.getInputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("0.01");
  }

  @Test
  void generateGstReturn_routesClaimabilitySignalWithoutDoubleRoundingAcrossAccounts() {
    YearMonth period = YearMonth.of(2024, 7);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

    // Output net: -0.005 contra signal to claimability (debit-heavy for output account).
    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(List.of(line(new BigDecimal("0.005"), BigDecimal.ZERO)));
    // Input net: +0.005 claimability signal (debit-heavy).
    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(List.of(line(new BigDecimal("0.005"), BigDecimal.ZERO)));

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getOutputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTax()).isEqualByComparingTo("0.01");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("-0.01");
  }

  @Test
  void generateGstReturn_routesContraBalancesToLiabilityAndClaimabilitySignals() {
    YearMonth period = YearMonth.of(2024, 5);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    when(companyAccountingSettingsService.requireTaxAccounts())
        .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

    // Output account net is debit-heavy (-20.00), which should be reflected as claimability.
    when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
        .thenReturn(List.of(line(new BigDecimal("120.00"), new BigDecimal("100.00"))));
    // Input account net is credit-heavy (-15.00), which should be reflected as liability.
    when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
        .thenReturn(List.of(line(new BigDecimal("30.00"), new BigDecimal("45.00"))));

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getOutputTax()).isEqualByComparingTo("15.00");
    assertThat(dto.getInputTax()).isEqualByComparingTo("20.00");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("-5.00");
  }

  @Test
  void generateGstReturn_nonGstModeWithNoGstAccounts_returnsZeroes() {
    YearMonth period = YearMonth.of(2024, 3);
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);

    GstReturnDto dto = taxService.generateGstReturn(period);

    assertThat(dto.getOutputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTax()).isEqualByComparingTo("0.00");
    assertThat(dto.getNetPayable()).isEqualByComparingTo("0.00");
    verifyNoInteractions(companyAccountingSettingsService, journalLineRepository);
  }

  @Test
  void generateGstReturn_nonGstModeWithConfiguredGstAccounts_failsClosed() {
    YearMonth period = YearMonth.of(2024, 3);
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(1L);

    assertThatThrownBy(() -> taxService.generateGstReturn(period))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Non-GST mode company cannot have GST tax accounts configured");
  }

  @Test
  void generateGstReturn_rejectsFuturePeriod() {
    when(companyClock.today(company)).thenReturn(LocalDate.of(2024, 4, 15));

    assertThatThrownBy(() -> taxService.generateGstReturn(YearMonth.of(2024, 5)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("GST return period cannot be in the future");
  }

  @Test
  void generateGstReconciliation_summarizesCollectedInputAndNetByComponent() {
    YearMonth period = YearMonth.of(2024, 8);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    Invoice invoice = new Invoice();
    invoice.setStatus("ISSUED");
    Dealer dealer = new Dealer();
    dealer.setStateCode("27");
    invoice.setDealer(dealer);
    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setTaxAmount(new BigDecimal("18.00"));
    invoiceLine.setTaxableAmount(new BigDecimal("100.00"));
    invoiceLine.setCgstAmount(new BigDecimal("9.00"));
    invoiceLine.setSgstAmount(new BigDecimal("9.00"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setStatus("POSTED");
    Supplier supplier = new Supplier();
    supplier.setStateCode("29");
    purchase.setSupplier(supplier);
    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setLineTotal(new BigDecimal("118.00"));
    purchaseLine.setTaxAmount(new BigDecimal("18.00"));
    purchaseLine.setIgstAmount(new BigDecimal("18.00"));
    purchaseLine.setCgstAmount(BigDecimal.ZERO);
    purchaseLine.setSgstAmount(BigDecimal.ZERO);
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(purchase));

    GstReconciliationDto dto = taxService.generateGstReconciliation(period);

    assertThat(dto.getCollected().getCgst()).isEqualByComparingTo("9.00");
    assertThat(dto.getCollected().getSgst()).isEqualByComparingTo("9.00");
    assertThat(dto.getCollected().getIgst()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTaxCredit().getIgst()).isEqualByComparingTo("18.00");
    assertThat(dto.getNetLiability().getCgst()).isEqualByComparingTo("9.00");
    assertThat(dto.getNetLiability().getSgst()).isEqualByComparingTo("9.00");
    assertThat(dto.getNetLiability().getIgst()).isEqualByComparingTo("-18.00");
    assertThat(dto.getNetLiability().getTotal()).isEqualByComparingTo("0.00");
  }

  @Test
  void generateGstReconciliation_ignoresDraftDocuments_withoutMutatingImmutableLineCollections() {
    YearMonth period = YearMonth.of(2024, 8);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    Invoice postedInvoice = new Invoice();
    postedInvoice.setStatus("POSTED");
    Dealer postedDealer = new Dealer();
    postedDealer.setStateCode("27");
    postedInvoice.setDealer(postedDealer);
    InvoiceLine postedInvoiceLine = new InvoiceLine();
    postedInvoiceLine.setTaxAmount(new BigDecimal("18.00"));
    postedInvoiceLine.setTaxableAmount(new BigDecimal("100.00"));
    postedInvoiceLine.setCgstAmount(new BigDecimal("9.00"));
    postedInvoiceLine.setSgstAmount(new BigDecimal("9.00"));
    postedInvoiceLine.setIgstAmount(BigDecimal.ZERO);
    ReflectionFieldAccess.setField(postedInvoice, "lines", List.of(postedInvoiceLine));

    Invoice draftInvoice = new Invoice();
    draftInvoice.setStatus("DRAFT");
    Dealer draftDealer = new Dealer();
    draftDealer.setStateCode("27");
    draftInvoice.setDealer(draftDealer);
    InvoiceLine draftInvoiceLine = new InvoiceLine();
    draftInvoiceLine.setTaxAmount(new BigDecimal("180.00"));
    draftInvoiceLine.setTaxableAmount(new BigDecimal("1000.00"));
    draftInvoiceLine.setCgstAmount(new BigDecimal("90.00"));
    draftInvoiceLine.setSgstAmount(new BigDecimal("90.00"));
    draftInvoiceLine.setIgstAmount(BigDecimal.ZERO);
    ReflectionFieldAccess.setField(draftInvoice, "lines", List.of(draftInvoiceLine));

    RawMaterialPurchase postedPurchase = new RawMaterialPurchase();
    postedPurchase.setStatus("POSTED");
    Supplier postedSupplier = new Supplier();
    postedSupplier.setStateCode("29");
    postedPurchase.setSupplier(postedSupplier);
    RawMaterialPurchaseLine postedPurchaseLine = new RawMaterialPurchaseLine();
    postedPurchaseLine.setQuantity(new BigDecimal("10.00"));
    postedPurchaseLine.setReturnedQuantity(BigDecimal.ZERO);
    postedPurchaseLine.setLineTotal(new BigDecimal("118.00"));
    postedPurchaseLine.setTaxAmount(new BigDecimal("18.00"));
    postedPurchaseLine.setIgstAmount(new BigDecimal("18.00"));
    postedPurchaseLine.setCgstAmount(BigDecimal.ZERO);
    postedPurchaseLine.setSgstAmount(BigDecimal.ZERO);
    postedPurchase.setLines(List.of(postedPurchaseLine));

    RawMaterialPurchase draftPurchase = new RawMaterialPurchase();
    draftPurchase.setStatus("DRAFT");
    Supplier draftSupplier = new Supplier();
    draftSupplier.setStateCode("29");
    draftPurchase.setSupplier(draftSupplier);
    RawMaterialPurchaseLine draftPurchaseLine = new RawMaterialPurchaseLine();
    draftPurchaseLine.setQuantity(new BigDecimal("10.00"));
    draftPurchaseLine.setReturnedQuantity(BigDecimal.ZERO);
    draftPurchaseLine.setLineTotal(new BigDecimal("1180.00"));
    draftPurchaseLine.setTaxAmount(new BigDecimal("180.00"));
    draftPurchaseLine.setIgstAmount(new BigDecimal("180.00"));
    draftPurchaseLine.setCgstAmount(BigDecimal.ZERO);
    draftPurchaseLine.setSgstAmount(BigDecimal.ZERO);
    draftPurchase.setLines(List.of(draftPurchaseLine));

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of(postedInvoice, draftInvoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(postedPurchase, draftPurchase));

    GstReconciliationDto dto = taxService.generateGstReconciliation(period);

    assertThat(dto.getCollected().getTotal()).isEqualByComparingTo("18.00");
    assertThat(dto.getInputTaxCredit().getTotal()).isEqualByComparingTo("18.00");
    assertThat(dto.getNetLiability().getTotal()).isEqualByComparingTo("0.00");
  }

  @Test
  void generateGstReconciliation_fallsBackToStateBasedSplitWhenComponentColumnsAreEmpty() {
    YearMonth period = YearMonth.of(2024, 9);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    Invoice invoice = new Invoice();
    invoice.setStatus("ISSUED");
    Dealer dealer = new Dealer();
    dealer.setStateCode("29");
    invoice.setDealer(dealer);
    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setTaxAmount(new BigDecimal("18.00"));
    invoiceLine.setTaxableAmount(new BigDecimal("100.00"));
    invoiceLine.setLineTotal(new BigDecimal("118.00"));
    invoice.getLines().add(invoiceLine);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setStatus("POSTED");
    Supplier supplier = new Supplier();
    supplier.setStateCode("27");
    purchase.setSupplier(supplier);
    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setTaxAmount(new BigDecimal("9.00"));
    purchaseLine.setLineTotal(new BigDecimal("59.00"));
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(purchase));

    GstReconciliationDto dto = taxService.generateGstReconciliation(period);

    assertThat(dto.getCollected().getIgst()).isEqualByComparingTo("18.00");
    assertThat(dto.getCollected().getCgst()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTaxCredit().getCgst()).isEqualByComparingTo("4.50");
    assertThat(dto.getInputTaxCredit().getSgst()).isEqualByComparingTo("4.50");
    assertThat(dto.getInputTaxCredit().getIgst()).isEqualByComparingTo("0.00");
  }

  @Test
  void generateGstReconciliation_netsPurchaseReturnQuantityFromInputTaxCredit() {
    YearMonth period = YearMonth.of(2024, 10);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setStatus("POSTED");
    Supplier supplier = new Supplier();
    supplier.setStateCode("27");
    purchase.setSupplier(supplier);

    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setQuantity(new BigDecimal("10.00"));
    purchaseLine.setReturnedQuantity(new BigDecimal("4.00"));
    purchaseLine.setLineTotal(new BigDecimal("118.00"));
    purchaseLine.setTaxAmount(new BigDecimal("18.00"));
    purchaseLine.setCgstAmount(new BigDecimal("9.00"));
    purchaseLine.setSgstAmount(new BigDecimal("9.00"));
    purchaseLine.setIgstAmount(BigDecimal.ZERO);
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of());
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(purchase));

    GstReconciliationDto dto = taxService.generateGstReconciliation(period);

    assertThat(dto.getInputTaxCredit().getCgst()).isEqualByComparingTo("5.40");
    assertThat(dto.getInputTaxCredit().getSgst()).isEqualByComparingTo("5.40");
    assertThat(dto.getInputTaxCredit().getIgst()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTaxCredit().getTotal()).isEqualByComparingTo("10.80");
  }

  @Test
  void generateGstReconciliation_requiresStateCodesForTaxableFlows() {
    YearMonth period = YearMonth.of(2024, 11);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    company.setStateCode(null);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setStatus("POSTED");
    Supplier supplier = new Supplier();
    supplier.setStateCode("29");
    purchase.setSupplier(supplier);

    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setQuantity(new BigDecimal("5.00"));
    purchaseLine.setLineTotal(new BigDecimal("118.00"));
    purchaseLine.setTaxAmount(new BigDecimal("18.00"));
    purchaseLine.setIgstAmount(new BigDecimal("18.00"));
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of());
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(purchase));

    assertThatThrownBy(() -> taxService.generateGstReconciliation(period))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("State codes are required for GST decisioning");
  }

  @Test
  void generateGstReportBreakdown_includesRateSummariesAndTransactionDetails() {
    YearMonth period = YearMonth.of(2024, 12);
    LocalDate start = period.atDay(1);
    LocalDate end = period.atEndOfMonth();

    Invoice invoice = new Invoice();
    invoice.setStatus("POSTED");
    invoice.setIssueDate(LocalDate.of(2024, 12, 10));
    invoice.setInvoiceNumber("INV-101");
    ReflectionFieldAccess.setField(invoice, "id", 101L);
    Dealer dealer = new Dealer();
    dealer.setName("Dealer One");
    dealer.setStateCode("27");
    invoice.setDealer(dealer);
    InvoiceLine invoiceLine = new InvoiceLine();
    invoiceLine.setTaxRate(new BigDecimal("18.00"));
    invoiceLine.setTaxAmount(new BigDecimal("18.00"));
    invoiceLine.setTaxableAmount(new BigDecimal("100.00"));
    invoiceLine.setCgstAmount(new BigDecimal("9.00"));
    invoiceLine.setSgstAmount(new BigDecimal("9.00"));
    invoiceLine.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(invoiceLine);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setStatus("POSTED");
    purchase.setInvoiceDate(LocalDate.of(2024, 12, 11));
    purchase.setInvoiceNumber("PUR-202");
    ReflectionFieldAccess.setField(purchase, "id", 202L);
    Supplier supplier = new Supplier();
    supplier.setName("Supplier One");
    supplier.setStateCode("29");
    purchase.setSupplier(supplier);
    RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
    purchaseLine.setTaxRate(new BigDecimal("18.00"));
    purchaseLine.setQuantity(new BigDecimal("10.00"));
    purchaseLine.setReturnedQuantity(BigDecimal.ZERO);
    purchaseLine.setLineTotal(new BigDecimal("118.00"));
    purchaseLine.setTaxAmount(new BigDecimal("18.00"));
    purchaseLine.setCgstAmount(BigDecimal.ZERO);
    purchaseLine.setSgstAmount(BigDecimal.ZERO);
    purchaseLine.setIgstAmount(new BigDecimal("18.00"));
    purchase.getLines().add(purchaseLine);

    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end))
        .thenReturn(List.of(invoice));
    when(rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end))
        .thenReturn(List.of(purchase));

    GstReportBreakdownDto dto = taxService.generateGstReportBreakdown(period);

    assertThat(dto.getCollected().getTotal()).isEqualByComparingTo("18.00");
    assertThat(dto.getInputTaxCredit().getTotal()).isEqualByComparingTo("18.00");
    assertThat(dto.getNetLiability().getTotal()).isEqualByComparingTo("0.00");
    assertThat(dto.getRateSummaries()).hasSize(1);
    assertThat(dto.getRateSummaries().getFirst().getTaxRate()).isEqualByComparingTo("18.00");
    assertThat(dto.getRateSummaries().getFirst().getTaxableAmount()).isEqualByComparingTo("200.00");
    assertThat(dto.getTransactionDetails()).hasSize(2);
    assertThat(dto.getTransactionDetails())
        .extracting(GstReportBreakdownDto.GstTransactionDetail::getDirection)
        .containsExactly("OUTPUT", "INPUT");
  }

  @Test
  void generateGstReportBreakdown_nonGstModeWithNoGstAccounts_returnsZeroAndEmptyDetails() {
    YearMonth period = YearMonth.of(2024, 12);
    company.setDefaultGstRate(BigDecimal.ZERO);
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);

    GstReportBreakdownDto dto = taxService.generateGstReportBreakdown(period);

    assertThat(dto.getCollected().getTotal()).isEqualByComparingTo("0.00");
    assertThat(dto.getInputTaxCredit().getTotal()).isEqualByComparingTo("0.00");
    assertThat(dto.getNetLiability().getTotal()).isEqualByComparingTo("0.00");
    assertThat(dto.getRateSummaries()).isEmpty();
    assertThat(dto.getTransactionDetails()).isEmpty();
    verifyNoInteractions(invoiceRepository, rawMaterialPurchaseRepository);
  }

  private JournalLine line(BigDecimal debit, BigDecimal credit) {
    JournalLine jl = new JournalLine();
    jl.setDebit(debit == null ? BigDecimal.ZERO : debit);
    jl.setCredit(credit == null ? BigDecimal.ZERO : credit);
    return jl;
  }
}
