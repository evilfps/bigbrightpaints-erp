package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class AccountingFacadeTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingService accountingService;
  @Mock private DealerReceiptService dealerReceiptService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private CompanyScopedSalesLookupService salesLookupService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyAccountingSettingsService companyAccountingSettingsService;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Mock private PayrollAccountingService payrollAccountingService;

  private AccountingFacade accountingFacade;
  private Company company;

  @BeforeEach
  void setup() {
    accountingFacade =
        AccountingFacadeTestFactory.create(
            companyContextService,
            accountRepository,
            accountingService,
            payrollAccountingService,
            dealerReceiptService,
            journalEntryRepository,
            referenceNumberService,
            dealerRepository,
            supplierRepository,
            companyClock,
            salesLookupService,
            accountingLookupService,
            companyAccountingSettingsService,
            journalReferenceResolver,
            journalReferenceMappingRepository);
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 1L);
    company.setBaseCurrency("INR");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2024, 4, 9));
    lenient()
        .when(salesLookupService.requireDealer(any(), any()))
        .thenAnswer(
            invocation ->
                companyEntityLookup.requireDealer(
                    invocation.getArgument(0), invocation.getArgument(1)));
    lenient()
        .when(salesLookupService.requireSalesOrder(any(), any()))
        .thenAnswer(
            invocation ->
                companyEntityLookup.requireSalesOrder(
                    invocation.getArgument(0), invocation.getArgument(1)));
    lenient()
        .when(accountingLookupService.requireJournalEntry(any(), any()))
        .thenAnswer(
            invocation ->
                companyEntityLookup.requireJournalEntry(
                    invocation.getArgument(0), invocation.getArgument(1)));
    lenient()
        .when(accountingLookupService.requireAccount(any(), any()))
        .thenAnswer(
            invocation ->
                companyEntityLookup.requireAccount(
                    invocation.getArgument(0), invocation.getArgument(1)));
  }

  @Test
  void postSalesReturn_usesHashReferenceWhenBaseExists() {
    Dealer dealer = new Dealer();
    Account receivable = new Account();
    receivable.setCode("AR");
    dealer.setReceivableAccount(receivable);
    Long dealerId = 10L;
    ReflectionFieldAccess.setField(dealer, "id", dealerId);

    when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

    String invoiceNumber = "INV-100";
    Map<Long, BigDecimal> returnLines = Map.of(101L, new BigDecimal("100.00"));
    BigDecimal total = new BigDecimal("100.00");
    String reason = "Damaged";
    String baseReference = "CRN-" + invoiceNumber;
    String hashReference = buildExpectedHash(baseReference, dealerId, returnLines, total, reason);

    JournalEntry existing = new JournalEntry();
    existing.setReferenceNumber(baseReference);
    when(journalReferenceResolver.findExistingEntry(any(), eq(hashReference)))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq(baseReference)))
        .thenReturn(Optional.of(existing));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq(hashReference)))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
        .thenReturn(Optional.empty());

    JournalEntryDto stub =
        new JournalEntryDto(
            50L,
            null,
            hashReference,
            LocalDate.of(2024, 4, 9),
            null,
            "POSTED",
            dealerId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    doReturn(stub).when(accountingService).createStandardJournal(requestCaptor.capture());

    accountingFacade.postSalesReturn(dealerId, invoiceNumber, returnLines, total, reason);

    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo(hashReference);
    assertThat(requestCaptor.getValue().sourceModule()).isEqualTo("SALES_RETURN");
  }

  @Test
  void postPackingJournal_delegatesToCanonicalCreateStandardJournal() {
    String reference = " PACK-2026-001 ";
    LocalDate entryDate = LocalDate.of(2026, 2, 10);
    String memo = "Packing journal";
    List<JournalEntryRequest.JournalLineRequest> lines =
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                1001L, "FG inventory", new BigDecimal("250.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                2002L, "WIP release", BigDecimal.ZERO, new BigDecimal("250.00")));

    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), eq("PACK-2026-001")))
        .thenReturn(Optional.empty());

    JournalEntryDto expected =
        new JournalEntryDto(
            91L,
            null,
            "PACK-2026-001",
            entryDate,
            null,
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);

    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    when(accountingService.createStandardJournal(requestCaptor.capture())).thenReturn(expected);

    JournalEntryDto actual = accountingFacade.postPackingJournal(reference, entryDate, memo, lines);

    assertThat(actual).isSameAs(expected);
    JournalCreationRequest forwarded = requestCaptor.getValue();
    assertThat(forwarded.amount()).isEqualByComparingTo("250.00");
    assertThat(forwarded.debitAccount()).isEqualTo(1001L);
    assertThat(forwarded.creditAccount()).isEqualTo(2002L);
    assertThat(forwarded.narration()).isEqualTo(memo);
    assertThat(forwarded.sourceModule()).isEqualTo("FACTORY_PACKING");
    assertThat(forwarded.sourceReference()).isEqualTo("PACK-2026-001");
    assertThat(forwarded.lines()).hasSize(2);
    assertThat(forwarded.entryDate()).isEqualTo(entryDate);
    verify(accountingService).createStandardJournal(any());
    verify(accountingService, never()).createJournalEntry(any());
  }

  @Test
  void postPurchaseJournal_ignoresLegacyPrefixedEntriesWithoutBaseReplay() {
    Long supplierId = 88L;
    Supplier supplier = new Supplier();
    Account payable = new Account();
    payable.setCode("AP");
    payable.setName("Accounts Payable");
    ReflectionFieldAccess.setField(payable, "id", 301L);
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(eq(company), eq(supplierId)))
        .thenReturn(Optional.of(supplier));

    Long inventoryAccountId = 201L;
    String baseReference = "RMP-ACME-SUP-INV100";
    String canonicalReference = baseReference + "-0005";
    when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-100")))
        .thenReturn(baseReference);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference)))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(eq(company), eq(supplier), eq("INV-100")))
        .thenReturn(canonicalReference);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq(canonicalReference)))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq(baseReference)))
        .thenReturn(Optional.empty());
    JournalEntryDto created =
        new JournalEntryDto(
            915L,
            null,
            canonicalReference,
            LocalDate.of(2026, 1, 10),
            "created",
            "POSTED",
            null,
            null,
            supplierId,
            supplier.getName(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingService.createStandardJournal(any())).thenReturn(created);
    JournalEntry saved = new JournalEntry();
    ReflectionFieldAccess.setField(saved, "id", 915L);
    saved.setReferenceNumber(canonicalReference);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(915L))).thenReturn(saved);

    JournalEntryDto dto =
        accountingFacade.postPurchaseJournal(
            supplierId,
            "INV-100",
            LocalDate.of(2026, 1, 10),
            "legacy replay",
            Map.of(inventoryAccountId, new BigDecimal("100.00")),
            null,
            new BigDecimal("100.00"),
            null);

    assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
    verify(journalEntryRepository, never())
        .findByCompanyAndReferenceNumberStartingWith(eq(company), eq(baseReference + "-"));
    verify(accountingService).createStandardJournal(any());
  }

  @Test
  void postPurchaseJournal_existingBaseReference_shortCircuitsBeforeSupplierLifecycleCheck() {
    Long supplierId = 89L;
    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.SUSPENDED);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(eq(company), eq(supplierId)))
        .thenReturn(Optional.of(supplier));

    String baseReference = "RMP-ACME-SUP-INV101";
    when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-101")))
        .thenReturn(baseReference);

    JournalEntry existing = new JournalEntry();
    ReflectionFieldAccess.setField(existing, "id", 777L);
    existing.setReferenceNumber(baseReference);
    existing.setEntryDate(LocalDate.of(2026, 1, 14));
    existing.setStatus("POSTED");
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference)))
        .thenReturn(Optional.of(existing));

    JournalEntryDto dto =
        accountingFacade.postPurchaseJournal(
            supplierId,
            "INV-101",
            LocalDate.of(2026, 1, 14),
            "replay",
            Map.of(201L, new BigDecimal("50.00")),
            null,
            new BigDecimal("50.00"),
            null);

    assertThat(dto.id()).isEqualTo(777L);
    assertThat(dto.referenceNumber()).isEqualTo(baseReference);
    verify(accountingService, never()).createJournalEntry(any());
  }

  @Test
  void postPurchaseJournal_missingSupplierFailsWithCanonicalLookupError() {
    Long supplierId = 92L;
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(eq(company), eq(supplierId)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                accountingFacade.postPurchaseJournal(
                    supplierId,
                    "INV-104",
                    LocalDate.of(2026, 1, 17),
                    "missing supplier",
                    Map.of(204L, new BigDecimal("10.00")),
                    null,
                    new BigDecimal("10.00"),
                    null))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getMessage()).isEqualTo("Supplier not found");
              assertThat(ex.getDetails()).containsEntry("supplierId", supplierId);
            });

    verify(accountingService, never()).createStandardJournal(any());
  }

  @Test
  void postPurchaseJournal_rejectsReferenceOnlySupplierWhenCreatingNewJournal() {
    Long supplierId = 90L;
    Supplier supplier = new Supplier();
    supplier.setName("Blocked Supplier");
    supplier.setStatus(SupplierStatus.SUSPENDED);
    Account payable = new Account();
    ReflectionFieldAccess.setField(payable, "id", 302L);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(eq(company), eq(supplierId)))
        .thenReturn(Optional.of(supplier));

    Long inventoryAccountId = 202L;

    String baseReference = "RMP-ACME-SUP-INV102";
    when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-102")))
        .thenReturn(baseReference);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                accountingFacade.postPurchaseJournal(
                    supplierId,
                    "INV-102",
                    LocalDate.of(2026, 1, 15),
                    "blocked",
                    Map.of(inventoryAccountId, new BigDecimal("25.00")),
                    null,
                    new BigDecimal("25.00"),
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reference only")
        .hasMessageContaining("post purchase journals");

    verify(accountingService, never()).createJournalEntry(any());
  }

  @Test
  void postPurchaseJournal_createsNewJournalWhenNoReplayExists() {
    Long supplierId = 91L;
    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setName("Active Supplier");
    Account payable = new Account();
    payable.setCode("AP");
    payable.setName("Accounts Payable");
    ReflectionFieldAccess.setField(payable, "id", 303L);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(eq(company), eq(supplierId)))
        .thenReturn(Optional.of(supplier));

    Long inventoryAccountId = 203L;
    String baseReference = "RMP-ACME-SUP-INV103";
    String canonicalReference = baseReference + "-0001";
    when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-103")))
        .thenReturn(baseReference);
    when(referenceNumberService.purchaseReference(eq(company), eq(supplier), eq("INV-103")))
        .thenReturn(canonicalReference);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference)))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(
            eq(company), eq(canonicalReference)))
        .thenReturn(Optional.empty());

    JournalEntryDto created =
        new JournalEntryDto(
            910L,
            null,
            canonicalReference,
            LocalDate.of(2026, 1, 16),
            "created",
            "POSTED",
            null,
            null,
            supplierId,
            supplier.getName(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingService.createStandardJournal(any())).thenReturn(created);
    JournalEntry saved = new JournalEntry();
    ReflectionFieldAccess.setField(saved, "id", 910L);
    saved.setReferenceNumber(canonicalReference);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(910L))).thenReturn(saved);
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq(baseReference)))
        .thenReturn(Optional.empty());

    JournalEntryDto dto =
        accountingFacade.postPurchaseJournal(
            supplierId,
            "INV-103",
            LocalDate.of(2026, 1, 16),
            "new journal",
            Map.of(inventoryAccountId, new BigDecimal("30.00")),
            null,
            new BigDecimal("30.00"),
            null);

    assertThat(dto.id()).isEqualTo(910L);
    assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
    verify(accountingService).createStandardJournal(any());
  }

  @Test
  void postSalesJournal_idempotentHitDelegatesToAccountingServiceForDuplicateValidation() {
    Long dealerId = 77L;
    Dealer dealer = new Dealer();
    Account receivable = new Account();
    receivable.setCode("AR");
    receivable.setName("Accounts Receivable");
    ReflectionFieldAccess.setField(receivable, "id", 701L);
    dealer.setReceivableAccount(receivable);
    when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

    String orderNumber = "SO-1001";
    String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
    JournalEntry existing = new JournalEntry();
    ReflectionFieldAccess.setField(existing, "id", 777L);
    existing.setReferenceNumber(canonicalReference);
    existing.setEntryDate(LocalDate.of(2026, 1, 5));
    existing.setStatus("POSTED");
    existing.setDealer(dealer);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
        .thenReturn(Optional.of(existing));

    JournalEntryDto replay =
        new JournalEntryDto(
            777L,
            null,
            canonicalReference,
            LocalDate.of(2026, 1, 5),
            null,
            "POSTED",
            dealerId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    when(accountingService.createStandardJournal(requestCaptor.capture())).thenReturn(replay);
    when(companyEntityLookup.requireJournalEntry(eq(company), eq(777L))).thenReturn(existing);

    JournalEntryDto dto =
        accountingFacade.postSalesJournal(
            dealerId,
            orderNumber,
            null,
            "sales replay",
            Map.of(9001L, new BigDecimal("120.00")),
            Map.of(9002L, new BigDecimal("21.60")),
            new BigDecimal("141.60"),
            null);

    assertThat(dto.id()).isEqualTo(777L);
    assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo(canonicalReference);
    verify(accountingService).createStandardJournal(any());
  }

  @Test
  void postSalesJournal_idempotentReplayWithoutId_keepsExistingEntryForReferenceMapping() {
    Long dealerId = 88L;
    Dealer dealer = new Dealer();
    Account receivable = new Account();
    receivable.setCode("AR");
    receivable.setName("Accounts Receivable");
    ReflectionFieldAccess.setField(receivable, "id", 702L);
    dealer.setReceivableAccount(receivable);
    when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

    String orderNumber = "SO-1002";
    String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
    JournalEntry existing = new JournalEntry();
    ReflectionFieldAccess.setField(existing, "id", 888L);
    existing.setReferenceNumber(canonicalReference);
    existing.setEntryDate(LocalDate.of(2026, 1, 6));
    existing.setStatus("POSTED");
    existing.setDealer(dealer);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
        .thenReturn(Optional.of(existing));

    JournalEntryDto replayWithoutId =
        new JournalEntryDto(
            null,
            null,
            canonicalReference,
            LocalDate.of(2026, 1, 6),
            null,
            "POSTED",
            dealerId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingService.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(replayWithoutId);

    JournalEntryDto dto =
        accountingFacade.postSalesJournal(
            dealerId,
            orderNumber,
            null,
            "sales replay without id",
            Map.of(9001L, new BigDecimal("100.00")),
            Map.of(9002L, new BigDecimal("18.00")),
            new BigDecimal("118.00"),
            null);

    assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
    verify(accountingService).createStandardJournal(any());
    verify(companyEntityLookup, never()).requireJournalEntry(eq(company), any(Long.class));
  }

  @Test
  void postSalesJournal_idempotentReplayNull_returnsNullAndSkipsEntryLookup() {
    Long dealerId = 99L;
    Dealer dealer = new Dealer();
    Account receivable = new Account();
    receivable.setCode("AR");
    receivable.setName("Accounts Receivable");
    ReflectionFieldAccess.setField(receivable, "id", 703L);
    dealer.setReceivableAccount(receivable);
    when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

    String orderNumber = "SO-1003";
    String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
    JournalEntry existing = new JournalEntry();
    ReflectionFieldAccess.setField(existing, "id", 889L);
    existing.setReferenceNumber(canonicalReference);
    existing.setEntryDate(LocalDate.of(2026, 1, 7));
    existing.setStatus("POSTED");
    existing.setDealer(dealer);
    when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
        .thenReturn(Optional.of(existing));
    when(accountingService.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(null);

    JournalEntryDto replay =
        accountingFacade.postSalesJournal(
            dealerId,
            orderNumber,
            null,
            "sales replay null",
            Map.of(9001L, new BigDecimal("100.00")),
            Map.of(9002L, new BigDecimal("18.00")),
            new BigDecimal("118.00"),
            null);

    assertThat(replay).isNull();
    verify(accountingService).createStandardJournal(any());
    verify(companyEntityLookup, never()).requireJournalEntry(eq(company), any(Long.class));
  }

  @Test
  void createManualJournal_routesThroughStandardJournalWithManualSource() {
    ManualJournalRequest request =
        new ManualJournalRequest(
            LocalDate.of(2026, 2, 28),
            "Manual adjustment",
            "manual-100",
            false,
            List.of(
                new ManualJournalRequest.LineRequest(
                    11L, new BigDecimal("50.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                new ManualJournalRequest.LineRequest(
                    22L,
                    new BigDecimal("50.00"),
                    "Credit",
                    ManualJournalRequest.EntryType.CREDIT)));
    JournalEntryDto expected =
        new JournalEntryDto(
            600L,
            null,
            "JRN-600",
            LocalDate.of(2026, 2, 28),
            "Manual adjustment",
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingService.createStandardJournal(any(JournalCreationRequest.class)))
        .thenReturn(expected);

    JournalEntryDto actual = accountingFacade.createManualJournal(request);

    assertThat(actual).isSameAs(expected);
    verify(accountingService)
        .createStandardJournal(
            argThat(
                journalRequest ->
                    journalRequest != null
                        && "MANUAL".equals(journalRequest.sourceModule())
                        && "manual-100".equals(journalRequest.sourceReference())
                        && journalRequest.lines() != null
                        && journalRequest.lines().size() == 2
                        && journalRequest.amount().compareTo(new BigDecimal("50.00")) == 0));
  }

  @Test
  void createManualJournalEntry_routesThroughAccountingServiceManualEntryPath() {
    JournalEntryRequest request =
        new JournalEntryRequest(
            null,
            LocalDate.of(2026, 2, 28),
            "Manual single",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Dr", new BigDecimal("75.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Cr", BigDecimal.ZERO, new BigDecimal("75.00"))),
            null,
            null,
            null,
            null,
            null);
    JournalEntryDto expected =
        new JournalEntryDto(
            601L,
            null,
            "JRN-601",
            LocalDate.of(2026, 2, 28),
            "Manual single",
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(accountingService.createManualJournalEntry(
            any(JournalEntryRequest.class), eq("MANUAL-XYZ")))
        .thenReturn(expected);

    JournalEntryDto actual = accountingFacade.createManualJournalEntry(request, "MANUAL-XYZ");

    assertThat(actual).isSameAs(expected);
    verify(accountingService)
        .createManualJournalEntry(
            argThat(
                entryRequest ->
                    entryRequest != null
                        && entryRequest.referenceNumber() == null
                        && LocalDate.of(2026, 2, 28).equals(entryRequest.entryDate())
                        && "Manual single".equals(entryRequest.memo())
                        && entryRequest.sourceModule() == null
                        && entryRequest.sourceReference() == null
                        && entryRequest.journalType() == null
                        && entryRequest.lines() != null
                        && entryRequest.lines().size() == 2),
            eq("MANUAL-XYZ"));
  }

  @Test
  void
      createManualJournalEntry_defaultsNullEntryDateFromCurrentCompanyClockForGeneratedManualReference() {
    new CompanyTime(companyClock);
    company.setTimezone("Pacific/Auckland");
    LocalDate tenantToday = LocalDate.of(2026, 3, 1);
    when(companyClock.today(company)).thenReturn(tenantToday);
    lenient().when(companyClock.today((Company) null)).thenReturn(tenantToday.minusDays(1));

    JournalEntryRequest request =
        new JournalEntryRequest(
            null,
            null,
            "Tenant-aware manual",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Dr", new BigDecimal("30.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Cr", BigDecimal.ZERO, new BigDecimal("30.00"))),
            null,
            null,
            null,
            null,
            null);

    accountingFacade.createManualJournalEntry(request, null);

    verify(accountingService)
        .createManualJournalEntry(
            argThat(
                entryRequest ->
                    entryRequest != null
                        && tenantToday.equals(entryRequest.entryDate())
                        && entryRequest.referenceNumber() == null
                        && entryRequest.sourceModule() == null
                        && entryRequest.sourceReference() == null
                        && entryRequest.journalType() == null),
            eq(null));
  }

  @Test
  void createManualJournalEntry_keepsExplicitEntryDateForGeneratedManualReference() {
    LocalDate explicitDate = LocalDate.of(2026, 3, 6);
    JournalEntryRequest request =
        new JournalEntryRequest(
            null,
            explicitDate,
            "Explicit manual",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Dr", new BigDecimal("30.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Cr", BigDecimal.ZERO, new BigDecimal("30.00"))),
            null,
            null,
            null,
            null,
            null);

    accountingFacade.createManualJournalEntry(request, null);

    verify(accountingService)
        .createManualJournalEntry(
            argThat(
                entryRequest ->
                    entryRequest != null
                        && explicitDate.equals(entryRequest.entryDate())
                        && entryRequest.referenceNumber() == null
                        && entryRequest.sourceModule() == null
                        && entryRequest.sourceReference() == null
                        && entryRequest.journalType() == null),
            eq(null));
  }

  @Test
  void recordPayrollPayment_delegatesToPayrollAccountingService() {
    PayrollPaymentRequest request =
        new PayrollPaymentRequest(
            9L, 2L, 1L, new BigDecimal("800.00"), "PAYROLL-PAY-9", "Payroll clear");
    JournalEntryDto expected =
        new JournalEntryDto(
            88L,
            null,
            "PAYROLL-PAY-9",
            LocalDate.of(2026, 2, 9),
            null,
            "POSTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.<JournalLineDto>of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(payrollAccountingService.recordPayrollPayment(request)).thenReturn(expected);

    JournalEntryDto actual = accountingFacade.recordPayrollPayment(request);

    assertThat(actual).isSameAs(expected);
    verify(payrollAccountingService).recordPayrollPayment(request);
  }

  @Test
  void postPayrollRun_delegatesToPayrollAccountingService() {
    List<JournalEntryRequest.JournalLineRequest> lines =
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                11L, "Payroll expense", new BigDecimal("800.00"), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                12L, "Payroll payable", BigDecimal.ZERO, new BigDecimal("800.00")));
    JournalEntryDto expected = expectedJournal(89L, "PAYROLL-PR-2026-03");
    when(payrollAccountingService.postPayrollRun(
            "PR-2026-03", 19L, LocalDate.of(2026, 3, 31), "Payroll - PR-2026-03", lines))
        .thenReturn(expected);

    JournalEntryDto actual =
        accountingFacade.postPayrollRun(
            "PR-2026-03", 19L, LocalDate.of(2026, 3, 31), "Payroll - PR-2026-03", lines);

    assertThat(actual).isSameAs(expected);
    verify(payrollAccountingService)
        .postPayrollRun(
            "PR-2026-03", 19L, LocalDate.of(2026, 3, 31), "Payroll - PR-2026-03", lines);
  }

  @Test
  void settlementAndReceiptFlows_delegateToFocusedReceiptAndAccountingServices() {
    DealerReceiptRequest dealerReceiptRequest =
        new DealerReceiptRequest(
            7L, 3L, new BigDecimal("125.00"), "RCPT-7", "Dealer receipt", "receipt-key", List.of());
    DealerReceiptSplitRequest splitRequest =
        new DealerReceiptSplitRequest(
            7L,
            List.of(new DealerReceiptSplitRequest.IncomingLine(3L, new BigDecimal("125.00"))),
            "RCPT-SPLIT-7",
            "Dealer split receipt",
            "split-key");
    SupplierPaymentRequest supplierPaymentRequest =
        new SupplierPaymentRequest(
            8L,
            4L,
            new BigDecimal("90.00"),
            "SUP-8",
            "Supplier payment",
            "supplier-key",
            List.of());
    PartnerSettlementRequest dealerSettlementRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            7L,
            3L,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 31),
            "SET-DEALER-7",
            "Dealer settlement",
            "dealer-settlement-key",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    70L,
                    null,
                    new BigDecimal("125.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "allocation")));
    PartnerSettlementRequest supplierSettlementRequest =
        new PartnerSettlementRequest(
            PartnerType.SUPPLIER,
            8L,
            4L,
            null,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 31),
            "SET-SUP-8",
            "Supplier settlement",
            "supplier-settlement-key",
            Boolean.FALSE,
            List.of(
                new SettlementAllocationRequest(
                    null,
                    80L,
                    new BigDecimal("90.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    SettlementAllocationApplication.DOCUMENT,
                    "allocation")));
    AutoSettlementRequest autoSettlementRequest =
        new AutoSettlementRequest(
            3L, new BigDecimal("150.00"), "AUTO-SET", "Auto settlement", "auto-key");
    JournalEntryDto receiptJournal = expectedJournal(90L, "RCPT-7");
    JournalEntryDto splitJournal = expectedJournal(91L, "RCPT-SPLIT-7");
    JournalEntryDto supplierJournal = expectedJournal(92L, "SUP-8");
    PartnerSettlementResponse dealerResponse =
        new PartnerSettlementResponse(
            receiptJournal,
            new BigDecimal("125.00"),
            new BigDecimal("125.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    PartnerSettlementResponse supplierResponse =
        new PartnerSettlementResponse(
            supplierJournal,
            new BigDecimal("90.00"),
            new BigDecimal("90.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());

    when(dealerReceiptService.recordDealerReceipt(dealerReceiptRequest)).thenReturn(receiptJournal);
    when(dealerReceiptService.recordDealerReceiptSplit(splitRequest)).thenReturn(splitJournal);
    when(accountingService.recordSupplierPayment(supplierPaymentRequest))
        .thenReturn(supplierJournal);
    when(accountingService.settleDealerInvoices(dealerSettlementRequest))
        .thenReturn(dealerResponse);
    when(accountingService.settleSupplierInvoices(supplierSettlementRequest))
        .thenReturn(supplierResponse);
    when(accountingService.autoSettleDealer(7L, autoSettlementRequest)).thenReturn(dealerResponse);
    when(accountingService.autoSettleSupplier(8L, autoSettlementRequest))
        .thenReturn(supplierResponse);

    assertThat(accountingFacade.recordDealerReceipt(dealerReceiptRequest)).isSameAs(receiptJournal);
    assertThat(accountingFacade.recordDealerReceiptSplit(splitRequest)).isSameAs(splitJournal);
    assertThat(accountingFacade.recordSupplierPayment(supplierPaymentRequest))
        .isSameAs(supplierJournal);
    assertThat(accountingFacade.settleDealerInvoices(dealerSettlementRequest))
        .isSameAs(dealerResponse);
    assertThat(accountingFacade.settleSupplierInvoices(supplierSettlementRequest))
        .isSameAs(supplierResponse);
    assertThat(accountingFacade.autoSettleDealer(7L, autoSettlementRequest))
        .isSameAs(dealerResponse);
    assertThat(accountingFacade.autoSettleSupplier(8L, autoSettlementRequest))
        .isSameAs(supplierResponse);

    verify(dealerReceiptService).recordDealerReceipt(dealerReceiptRequest);
    verify(dealerReceiptService).recordDealerReceiptSplit(splitRequest);
    verify(accountingService).recordSupplierPayment(supplierPaymentRequest);
    verify(accountingService).settleDealerInvoices(dealerSettlementRequest);
    verify(accountingService).settleSupplierInvoices(supplierSettlementRequest);
    verify(accountingService).autoSettleDealer(7L, autoSettlementRequest);
    verify(accountingService).autoSettleSupplier(8L, autoSettlementRequest);
  }

  private String buildExpectedHash(
      String base,
      Long dealerId,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    StringBuilder fingerprint = new StringBuilder();
    fingerprint
        .append(base)
        .append("|dealer=")
        .append(dealerId != null ? dealerId : "NA")
        .append("|total=")
        .append(normalizeDecimal(totalAmount))
        .append("|reason=")
        .append(reason != null ? reason.trim() : "");
    returnLines.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                fingerprint
                    .append("|acc=")
                    .append(entry.getKey())
                    .append(":")
                    .append(normalizeDecimal(entry.getValue())));
    String hash = DigestUtils.sha256Hex(fingerprint.toString());
    return base + "-H" + hash.substring(0, 12);
  }

  private String normalizeDecimal(BigDecimal value) {
    if (value == null) {
      return "0";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private JournalEntryDto expectedJournal(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 3, 31),
        "memo",
        "POSTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.<JournalLineDto>of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private JournalEntry journalEntry(Long id, String referenceNumber) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(referenceNumber);
    return entry;
  }
}
