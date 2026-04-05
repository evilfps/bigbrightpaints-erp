package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private JournalEntryService journalEntryService;
  @Mock private DealerReceiptService dealerReceiptService;

  private SettlementService settlementService;
  private Company company;

  @BeforeEach
  void setUp() {
    settlementService =
        org.mockito.Mockito.spy(
            new SettlementService(
                companyContextService,
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService
                        .class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService
                        .class),
                org.mockito.Mockito.mock(
                    org.springframework.context.ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.util.CompanyClock.class),
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository
                        .class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository
                        .class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository
                        .class),
                dealerRepository,
                supplierRepository,
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver
                        .class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.domain
                        .JournalReferenceMappingRepository.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.core.config.SystemSettingsService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.audit.AuditService.class),
                org.mockito.Mockito.mock(
                    com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class),
                journalEntryService,
                dealerReceiptService));
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 55L);
    company.setCode("BBP");
    company.setBaseCurrency("INR");
    org.mockito.Mockito.lenient()
        .when(companyContextService.requireCurrentCompany())
        .thenReturn(company);
  }

  @Test
  void recordSupplierPayment_normalizesTrimmedFieldsBeforeDelegating() {
    SettlementAllocationRequest allocation =
        new SettlementAllocationRequest(
            null,
            77L,
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            SettlementAllocationApplication.DOCUMENT,
            " memo ");
    doReturn(null).when(settlementService).recordSupplierPaymentInternal(any());

    settlementService.recordSupplierPayment(
        new SupplierPaymentRequest(
            88L,
            99L,
            new BigDecimal("10.00"),
            "  SUP-REF-1  ",
            "  supplier memo  ",
            "  supplier-key  ",
            List.of(allocation)));

    ArgumentCaptor<SupplierPaymentRequest> captor =
        ArgumentCaptor.forClass(SupplierPaymentRequest.class);
    verify(settlementService).recordSupplierPaymentInternal(captor.capture());
    assertThat(captor.getValue().referenceNumber()).isEqualTo("SUP-REF-1");
    assertThat(captor.getValue().memo()).isEqualTo("supplier memo");
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("supplier-key");
    assertThat(captor.getValue().amount()).isEqualByComparingTo("10.00");
  }

  @Test
  void settlementRequests_normalizeAmountFlagsAndUnappliedApplication() {
    doReturn(null).when(settlementService).settleDealerInvoicesInternal(any());
    doReturn(null).when(settlementService).settleSupplierInvoicesInternal(any());

    settlementService.settleDealerInvoices(
        new DealerSettlementRequest(
            71L,
            200L,
            null,
            null,
            null,
            null,
            new BigDecimal("25.00"),
            SettlementAllocationApplication.DOCUMENT,
            LocalDate.of(2026, 3, 1),
            "  DEALER-SET-1  ",
            "  dealer memo  ",
            "  dealer-key  ",
            null,
            List.of(),
            List.of()));
    settlementService.settleSupplierInvoices(
        new SupplierSettlementRequest(
            72L,
            300L,
            null,
            null,
            null,
            null,
            new BigDecimal("15.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION,
            LocalDate.of(2026, 3, 2),
            "  SUP-SET-1  ",
            "  supplier memo  ",
            "  supplier-key  ",
            Boolean.TRUE,
            List.of()));

    ArgumentCaptor<DealerSettlementRequest> dealerCaptor =
        ArgumentCaptor.forClass(DealerSettlementRequest.class);
    ArgumentCaptor<SupplierSettlementRequest> supplierCaptor =
        ArgumentCaptor.forClass(SupplierSettlementRequest.class);
    verify(settlementService).settleDealerInvoicesInternal(dealerCaptor.capture());
    verify(settlementService).settleSupplierInvoicesInternal(supplierCaptor.capture());

    assertThat(dealerCaptor.getValue().amount()).isEqualByComparingTo("25.00");
    assertThat(dealerCaptor.getValue().unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.DOCUMENT);
    assertThat(dealerCaptor.getValue().adminOverride()).isFalse();
    assertThat(dealerCaptor.getValue().referenceNumber()).isEqualTo("DEALER-SET-1");
    assertThat(supplierCaptor.getValue().unappliedAmountApplication())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
    assertThat(supplierCaptor.getValue().adminOverride()).isTrue();
    assertThat(supplierCaptor.getValue().referenceNumber()).isEqualTo("SUP-SET-1");
  }

  @Test
  void settlementRequests_allowNullAmountAndNullUnappliedApplication() {
    doReturn(null).when(settlementService).settleDealerInvoicesInternal(any());
    doReturn(null).when(settlementService).settleSupplierInvoicesInternal(any());

    settlementService.settleDealerInvoices(
        new DealerSettlementRequest(
            81L,
            210L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 3),
            " DEALER-SET-NULL ",
            " dealer memo ",
            " dealer-null-key ",
            Boolean.TRUE,
            List.of(),
            List.of()));
    settlementService.settleSupplierInvoices(
        new SupplierSettlementRequest(
            82L,
            310L,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 4),
            " SUP-SET-NULL ",
            " supplier memo ",
            " supplier-null-key ",
            null,
            List.of()));

    ArgumentCaptor<DealerSettlementRequest> dealerCaptor =
        ArgumentCaptor.forClass(DealerSettlementRequest.class);
    ArgumentCaptor<SupplierSettlementRequest> supplierCaptor =
        ArgumentCaptor.forClass(SupplierSettlementRequest.class);
    verify(settlementService).settleDealerInvoicesInternal(dealerCaptor.capture());
    verify(settlementService).settleSupplierInvoicesInternal(supplierCaptor.capture());

    DealerSettlementRequest dealerRequest = dealerCaptor.getValue();
    SupplierSettlementRequest supplierRequest = supplierCaptor.getValue();
    assertThat(dealerRequest.amount()).isNull();
    assertThat(dealerRequest.unappliedAmountApplication()).isNull();
    assertThat(dealerRequest.adminOverride()).isTrue();
    assertThat(supplierRequest.amount()).isNull();
    assertThat(supplierRequest.unappliedAmountApplication()).isNull();
    assertThat(supplierRequest.adminOverride()).isFalse();
  }

  @Test
  void settlementRequests_rejectNonPositiveAmountWhenProvided() {
    assertThatThrownBy(
            () ->
                settlementService.settleDealerInvoices(
                    new DealerSettlementRequest(
                        83L,
                        210L,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        null,
                        LocalDate.of(2026, 3, 5),
                        "DEALER-SET-ZERO",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void autoSettlement_generatesDeterministicReferencesAndNormalizesExplicitValues() {
    doReturn(null).when(settlementService).autoSettleDealerInternal(anyLong(), any());
    doReturn(null).when(settlementService).autoSettleSupplierInternal(anyLong(), any());

    settlementService.autoSettleDealer(
        91L,
        new AutoSettlementRequest(200L, new BigDecimal("50.00"), "   ", "  dealer auto  ", null));
    settlementService.autoSettleSupplier(
        92L,
        new AutoSettlementRequest(
            300L,
            new BigDecimal("60.00"),
            "  SUP-CUSTOM-1  ",
            "  supplier auto  ",
            "  supplier-auto-key  "));

    ArgumentCaptor<AutoSettlementRequest> dealerCaptor =
        ArgumentCaptor.forClass(AutoSettlementRequest.class);
    ArgumentCaptor<AutoSettlementRequest> supplierCaptor =
        ArgumentCaptor.forClass(AutoSettlementRequest.class);
    verify(settlementService)
        .autoSettleDealerInternal(org.mockito.ArgumentMatchers.eq(91L), dealerCaptor.capture());
    verify(settlementService)
        .autoSettleSupplierInternal(org.mockito.ArgumentMatchers.eq(92L), supplierCaptor.capture());

    assertThat(dealerCaptor.getValue().referenceNumber()).isNull();
    assertThat(dealerCaptor.getValue().idempotencyKey()).isNull();
    assertThat(dealerCaptor.getValue().memo()).isEqualTo("dealer auto");

    assertThat(supplierCaptor.getValue().referenceNumber()).isEqualTo("SUP-CUSTOM-1");
    assertThat(supplierCaptor.getValue().idempotencyKey()).isEqualTo("supplier-auto-key");
    assertThat(supplierCaptor.getValue().memo()).isEqualTo("supplier auto");
  }

  @Test
  void resolveDealerHeaderSettlementAmount_usesPaymentTotalWhenHeaderAmountIsOmitted() {
    DealerSettlementRequest request =
        new DealerSettlementRequest(
            91L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 10),
            "DEALER-HDR-1",
            null,
            null,
            null,
            List.of(),
            List.of(
                new SettlementPaymentRequest(11L, new BigDecimal("40.00"), "BANK"),
                new SettlementPaymentRequest(12L, new BigDecimal("15.00"), "CASH")));

    BigDecimal resolved =
        ReflectionTestUtils.invokeMethod(
            settlementService, "resolveDealerHeaderSettlementAmount", request);

    assertThat(resolved).isEqualByComparingTo("55.00");
  }

  @Test
  void resolveDealerHeaderSettlementAmount_rejectsMismatchedHeaderAmountAndPayments() {
    DealerSettlementRequest request =
        new DealerSettlementRequest(
            92L,
            null,
            null,
            null,
            null,
            null,
            new BigDecimal("60.00"),
            null,
            LocalDate.of(2026, 3, 11),
            "DEALER-HDR-2",
            null,
            null,
            null,
            List.of(),
            List.of(new SettlementPaymentRequest(11L, new BigDecimal("45.00"), "BANK")));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    settlementService, "resolveDealerHeaderSettlementAmount", request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Dealer settlement amount must match the total payment amount");
  }

  @Test
  void buildDealerHeaderSettlementAllocations_appendsOnAccountRemainder() {
    Dealer dealer = dealer(501L, "Acme Dealer");
    when(invoiceRepository.lockOpenInvoicesForSettlement(eq(company), eq(dealer)))
        .thenReturn(List.of(invoice(701L, dealer, new BigDecimal("40.00"))));

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> allocations =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "buildDealerHeaderSettlementAllocations",
            company,
            dealer,
            new BigDecimal("55.00"),
            SettlementAllocationApplication.ON_ACCOUNT);

    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).invoiceId()).isEqualTo(701L);
    assertThat(allocations.get(0).appliedAmount()).isEqualByComparingTo("40.00");
    assertThat(allocations.get(1).invoiceId()).isNull();
    assertThat(allocations.get(1).appliedAmount()).isEqualByComparingTo("15.00");
    assertThat(allocations.get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
  }

  @Test
  void buildSupplierHeaderSettlementAllocations_appendsFutureApplicationRemainder() {
    Supplier supplier = supplier(601L, "Acme Supplier");
    when(rawMaterialPurchaseRepository.lockOpenPurchasesForSettlement(eq(company), eq(supplier)))
        .thenReturn(List.of(purchase(801L, supplier, new BigDecimal("30.00"))));

    @SuppressWarnings("unchecked")
    List<SettlementAllocationRequest> allocations =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "buildSupplierHeaderSettlementAllocations",
            company,
            supplier,
            new BigDecimal("45.00"),
            SettlementAllocationApplication.FUTURE_APPLICATION);

    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(0).purchaseId()).isEqualTo(801L);
    assertThat(allocations.get(0).appliedAmount()).isEqualByComparingTo("30.00");
    assertThat(allocations.get(1).purchaseId()).isNull();
    assertThat(allocations.get(1).appliedAmount()).isEqualByComparingTo("15.00");
    assertThat(allocations.get(1).applicationType())
        .isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
  }

  @Test
  void validateSupplierSettlementAllocations_rejectsDuplicatePurchaseAllocations() {
    List<SettlementAllocationRequest> allocations =
        List.of(
            new SettlementAllocationRequest(
                null,
                900L,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                "first"),
            new SettlementAllocationRequest(
                null,
                900L,
                new BigDecimal("5.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SettlementAllocationApplication.DOCUMENT,
                "second"));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    settlementService, "validateSupplierSettlementAllocations", allocations))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("duplicate purchase allocations");
  }

  @Test
  void buildDealerSettlementLines_supportsPaymentLinesDiscountsWriteOffsAndFxAdjustments() {
    Account receivable = account(100L, "AR", AccountType.ASSET);
    Account bankA = account(101L, "BANK-A", AccountType.ASSET);
    Account bankB = account(102L, "BANK-B", AccountType.ASSET);
    Account discount = account(103L, "DISC", AccountType.EXPENSE);
    Account writeOff = account(104L, "WO", AccountType.EXPENSE);
    Account fxGain = account(105L, "FXG", AccountType.REVENUE);
    Account fxLoss = account(106L, "FXL", AccountType.EXPENSE);
    stubAccounts(bankA, bankB, discount, writeOff, fxGain, fxLoss);
    DealerSettlementRequest request =
        new DealerSettlementRequest(
            501L,
            null,
            discount.getId(),
            writeOff.getId(),
            fxGain.getId(),
            fxLoss.getId(),
            null,
            null,
            LocalDate.of(2026, 3, 12),
            "DEALER-LINES",
            "Dealer settlement",
            "dealer-lines-key",
            null,
            List.of(),
            List.of(
                new SettlementPaymentRequest(bankA.getId(), new BigDecimal("60.00"), "BANK"),
                new SettlementPaymentRequest(bankB.getId(), new BigDecimal("70.00"), "CHEQUE")));
    Object totals =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "computeSettlementTotals",
            List.of(
                new SettlementAllocationRequest(
                    1L,
                    null,
                    new BigDecimal("100.00"),
                    new BigDecimal("10.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("15.00"),
                    SettlementAllocationApplication.DOCUMENT,
                    "primary"),
                new SettlementAllocationRequest(
                    2L,
                    null,
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("-20.00"),
                    SettlementAllocationApplication.DOCUMENT,
                    "secondary")));

    Object draft =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "buildDealerSettlementLines",
            company,
            request,
            receivable,
            totals,
            "Dealer settlement",
            true);

    assertThat(draftCashAmount(draft)).isEqualByComparingTo("130.00");
    assertThat(draftLines(draft)).hasSize(7);
    assertThat(draftLines(draft).get(5).accountId()).isEqualTo(receivable.getId());
    assertThat(draftLines(draft).get(5).credit()).isEqualByComparingTo("150.00");
    assertThat(draftLines(draft).get(6).accountId()).isEqualTo(fxGain.getId());
    assertThat(draftLines(draft).get(6).credit()).isEqualByComparingTo("15.00");
  }

  @Test
  void buildSupplierSettlementLines_supportsCashAccountDiscountsWriteOffsAndFxAdjustments() {
    Account payable = account(200L, "AP", AccountType.LIABILITY);
    Account cash = account(201L, "BANK", AccountType.ASSET);
    Account discount = account(202L, "DISC-REC", AccountType.REVENUE);
    Account writeOff = account(203L, "WO", AccountType.EXPENSE);
    Account fxGain = account(204L, "FXG", AccountType.REVENUE);
    Account fxLoss = account(205L, "FXL", AccountType.EXPENSE);
    stubAccounts(cash, discount, writeOff, fxGain, fxLoss);
    SupplierSettlementRequest request =
        new SupplierSettlementRequest(
            601L,
            cash.getId(),
            discount.getId(),
            writeOff.getId(),
            fxGain.getId(),
            fxLoss.getId(),
            null,
            null,
            LocalDate.of(2026, 3, 13),
            "SUP-LINES",
            "Supplier settlement",
            "supplier-lines-key",
            null,
            List.of());
    Object totals =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "computeSettlementTotals",
            List.of(
                new SettlementAllocationRequest(
                    null,
                    10L,
                    new BigDecimal("125.00"),
                    new BigDecimal("10.00"),
                    new BigDecimal("5.00"),
                    new BigDecimal("20.00"),
                    SettlementAllocationApplication.DOCUMENT,
                    "primary"),
                new SettlementAllocationRequest(
                    null,
                    11L,
                    new BigDecimal("75.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("-15.00"),
                    SettlementAllocationApplication.DOCUMENT,
                    "secondary")));

    Object draft =
        ReflectionTestUtils.invokeMethod(
            settlementService,
            "buildSupplierSettlementLines",
            company,
            request,
            payable,
            totals,
            "Supplier settlement",
            true);

    assertThat(draftCashAmount(draft)).isEqualByComparingTo("180.00");
    assertThat(draftLines(draft)).hasSize(6);
    assertThat(draftLines(draft).get(0).accountId()).isEqualTo(payable.getId());
    assertThat(draftLines(draft).get(0).debit()).isEqualByComparingTo("200.00");
    assertThat(draftLines(draft).get(2).accountId()).isEqualTo(cash.getId());
    assertThat(draftLines(draft).get(2).credit()).isEqualByComparingTo("180.00");
    assertThat(draftLines(draft).get(5).accountId()).isEqualTo(fxGain.getId());
    assertThat(draftLines(draft).get(5).credit()).isEqualByComparingTo("20.00");
  }

  private void stubAccounts(Account... accounts) {
    for (Account account : accounts) {
      when(companyEntityLookup.requireAccount(eq(company), eq(account.getId())))
          .thenReturn(account);
    }
  }

  private List<JournalEntryRequest.JournalLineRequest> draftLines(Object draft) {
    @SuppressWarnings("unchecked")
    List<JournalEntryRequest.JournalLineRequest> lines =
        ReflectionTestUtils.invokeMethod(draft, "lines");
    return lines;
  }

  private BigDecimal draftCashAmount(Object draft) {
    return ReflectionTestUtils.invokeMethod(draft, "cashAmount");
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setType(type);
    account.setActive(true);
    return account;
  }

  private Dealer dealer(Long id, String name) {
    Dealer dealer = new Dealer();
    ReflectionTestUtils.setField(dealer, "id", id);
    dealer.setCompany(company);
    dealer.setName(name);
    return dealer;
  }

  private Supplier supplier(Long id, String name) {
    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", id);
    supplier.setCompany(company);
    supplier.setName(name);
    return supplier;
  }

  private Invoice invoice(Long id, Dealer dealer, BigDecimal outstandingAmount) {
    Invoice invoice = new Invoice();
    ReflectionTestUtils.setField(invoice, "id", id);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setOutstandingAmount(outstandingAmount);
    invoice.setCurrency("INR");
    return invoice;
  }

  private RawMaterialPurchase purchase(Long id, Supplier supplier, BigDecimal outstandingAmount) {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    ReflectionTestUtils.setField(purchase, "id", id);
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setOutstandingAmount(outstandingAmount);
    return purchase;
  }
}
