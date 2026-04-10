package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchaseJournalFacadeOperationsTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountingService accountingService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private SupplierRepository supplierRepository;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;

  private PurchaseJournalFacadeOperations operations;
  private Company company;
  private Supplier supplier;

  @BeforeEach
  void setUp() {
    operations =
        new PurchaseJournalFacadeOperations(
            companyContextService,
            accountingService,
            journalEntryRepository,
            referenceNumberService,
            supplierRepository,
            companyClock,
            accountingLookupService,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            new AccountingFacadeTaxSupport(null));
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 1L);
    company.setCode("BBP");
    supplier = new Supplier();
    ReflectionFieldAccess.setField(supplier, "id", 7L);
    supplier.setCompany(company);
    supplier.setCode("SUP-7");
    supplier.setName("Supplier Seven");
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setPayableAccount(account(70L));
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(company, 7L))
        .thenReturn(Optional.of(supplier));
  }

  @Test
  void postPurchaseJournal_usesGeneratedReferenceWhenExplicitReferenceIsBlank() {
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-1"))
        .thenReturn("PUR-KEY-1");
    when(journalReferenceResolver.findExistingEntry(company, "PUR-KEY-1"))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(company, supplier, "INV-1"))
        .thenReturn("  PUR-REF-1  ");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PUR-REF-1"))
        .thenReturn(Optional.empty());
    when(accountingService.createStandardJournal(any()))
        .thenReturn(journalEntryDto(11L, "PUR-REF-1"));
    when(accountingLookupService.requireJournalEntry(company, 11L))
        .thenReturn(journalEntry(11L, "PUR-REF-1"));

    operations.postPurchaseJournal(
        7L,
        "INV-1",
        LocalDate.of(2026, 4, 10),
        "Purchase invoice",
        Map.of(88L, new BigDecimal("100.00")),
        null,
        null,
        new BigDecimal("100.00"),
        "   ");

    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    verify(accountingService).createStandardJournal(requestCaptor.capture());
    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo("PUR-REF-1");
  }

  @Test
  void postPurchaseJournal_rejectsBlankGeneratedReference() {
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-2"))
        .thenReturn("PUR-KEY-2");
    when(journalReferenceResolver.findExistingEntry(company, "PUR-KEY-2"))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(company, supplier, "INV-2")).thenReturn("   ");

    assertThatThrownBy(
            () ->
                operations.postPurchaseJournal(
                    7L,
                    "INV-2",
                    LocalDate.of(2026, 4, 10),
                    "Purchase invoice",
                    Map.of(88L, new BigDecimal("100.00")),
                    null,
                    null,
                    new BigDecimal("100.00"),
                    "   "))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE));
  }

  @Test
  void postPurchaseJournal_returnsExistingEntryForGeneratedReference() {
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-3"))
        .thenReturn("PUR-KEY-3");
    when(journalReferenceResolver.findExistingEntry(company, "PUR-KEY-3"))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(company, supplier, "INV-3"))
        .thenReturn("  PUR-EXIST  ");
    JournalEntry existing = journalEntry(14L, "PUR-EXIST");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PUR-EXIST"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postPurchaseJournal(
            7L,
            "INV-3",
            LocalDate.of(2026, 4, 10),
            "Purchase invoice",
            Map.of(88L, new BigDecimal("100.00")),
            null,
            null,
            new BigDecimal("100.00"),
            "   ");

    assertThat(result.id()).isEqualTo(14L);
    assertThat(result.referenceNumber()).isEqualTo("PUR-EXIST");
  }

  @Test
  void postPurchaseReturn_usesGeneratedReferenceWhenExplicitReferenceIsBlank() {
    when(referenceNumberService.purchaseReturnReference(company, supplier)).thenReturn("  RET-1  ");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "RET-1"))
        .thenReturn(Optional.empty());
    when(accountingService.createStandardJournal(any()))
        .thenReturn(journalEntryDto(12L, "RET-1"));

    operations.postPurchaseReturn(
        7L,
        "   ",
        LocalDate.of(2026, 4, 10),
        "Purchase return",
        Map.of(88L, new BigDecimal("100.00")),
        new BigDecimal("100.00"));

    ArgumentCaptor<JournalCreationRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalCreationRequest.class);
    verify(accountingService).createStandardJournal(requestCaptor.capture());
    assertThat(requestCaptor.getValue().sourceReference()).isEqualTo("RET-1");
  }

  @Test
  void postPurchaseReturn_returnsExistingEntryForTrimmedReference() {
    JournalEntry existing = journalEntry(13L, "RET-EXIST");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "RET-EXIST"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postPurchaseReturn(
            7L,
            "  RET-EXIST  ",
            LocalDate.of(2026, 4, 10),
            "Purchase return",
            Map.of(88L, new BigDecimal("100.00")),
            new BigDecimal("100.00"));

    assertThat(result.id()).isEqualTo(13L);
    assertThat(result.referenceNumber()).isEqualTo("RET-EXIST");
  }

  @Test
  void postPurchaseReturn_rejectsBlankGeneratedReference() {
    when(referenceNumberService.purchaseReturnReference(company, supplier)).thenReturn("   ");

    assertThatThrownBy(
            () ->
                operations.postPurchaseReturn(
                    7L,
                    null,
                    LocalDate.of(2026, 4, 10),
                    "Purchase return",
                    Map.of(88L, new BigDecimal("100.00")),
                    new BigDecimal("100.00")))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex ->
                assertThat(((ApplicationException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE));
  }

  private Account account(Long id) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    return account;
  }

  private JournalEntry journalEntry(Long id, String reference) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", id);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(LocalDate.of(2026, 4, 10));
    entry.setStatus("POSTED");
    return entry;
  }

  private JournalEntryDto journalEntryDto(Long id, String reference) {
    return new JournalEntryDto(
        id,
        UUID.randomUUID(),
        reference,
        LocalDate.of(2026, 4, 10),
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
        java.util.List.of(),
        Instant.parse("2026-04-10T00:00:00Z"),
        Instant.parse("2026-04-10T00:00:00Z"),
        null,
        null,
        null,
        null);
  }
}
