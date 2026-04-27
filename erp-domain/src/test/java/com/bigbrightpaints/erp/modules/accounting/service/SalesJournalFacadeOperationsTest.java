package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class SalesJournalFacadeOperationsTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountingService accountingService;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyScopedSalesLookupService salesLookupService;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;

  private SalesJournalFacadeOperations operations;
  private Company company;

  @BeforeEach
  void setUp() {
    operations =
        new SalesJournalFacadeOperations(
            companyContextService,
            accountingService,
            companyClock,
            salesLookupService,
            new AccountingFacadeTaxSupport(null),
            journalReferenceResolver,
            journalEntryRepository,
            journalReferenceMappingRepository,
            accountingLookupService);
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 1L);
    company.setCode("BBP");
    lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 31));
  }

  @Test
  void upsertJournalReferenceMapping_updatesExistingReservationWithoutEntityId() {
    JournalReferenceMapping mapping = journalReferenceMapping("LEGACY-100", null, null);
    JournalEntry entry = journalEntry(1001L, "SALES-100");
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("LEGACY-100")))
        .thenReturn(Optional.of(mapping));

    operations.upsertJournalReferenceMapping(company, "LEGACY-100", "SALES-100", entry);

    assertThat(mapping.getCanonicalReference()).isEqualTo("SALES-100");
    assertThat(mapping.getEntityType()).isEqualTo("JOURNAL_ENTRY");
    assertThat(mapping.getEntityId()).isEqualTo(1001L);
    verify(journalReferenceMappingRepository).save(mapping);
  }

  @Test
  void upsertJournalReferenceMapping_skipsConflictingExistingMapping() {
    JournalReferenceMapping mapping = journalReferenceMapping("LEGACY-101", "OTHER-REF", 44L);
    JournalEntry entry = journalEntry(1002L, "SALES-101");
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("LEGACY-101")))
        .thenReturn(Optional.of(mapping));

    operations.upsertJournalReferenceMapping(company, "LEGACY-101", "SALES-101", entry);

    assertThat(mapping.getCanonicalReference()).isEqualTo("OTHER-REF");
    assertThat(mapping.getEntityId()).isEqualTo(44L);
    verify(journalReferenceMappingRepository, never()).save(mapping);
  }

  @Test
  void upsertJournalReferenceMapping_ignoresConcurrentInsertViolation() {
    JournalEntry entry = journalEntry(1003L, "SALES-102");
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("LEGACY-102")))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.save(any(JournalReferenceMapping.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    operations.upsertJournalReferenceMapping(company, "LEGACY-102", "SALES-102", entry);

    verify(journalReferenceMappingRepository).save(any(JournalReferenceMapping.class));
  }

  @Test
  void reserveSalesJournalReference_returnsFalseWhenCanonicalReservationExists() {
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("SO-200")))
        .thenReturn(Optional.of(journalReferenceMapping("SO-200", "SO-200", 200L)));

    assertThat(operations.reserveSalesJournalReference(company, " SO-200 ")).isFalse();
    verify(journalReferenceMappingRepository, never())
        .reserveReferenceMapping(any(), any(), any(), any(), any());
  }

  @Test
  void reserveSalesJournalReference_throwsWhenReservationCannotBeResolved() {
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("SO-201")))
        .thenReturn(Optional.empty(), Optional.empty());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()), eq("SO-201"), eq("SO-201"), eq("SALES_JOURNAL"), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> operations.reserveSalesJournalReference(company, "SO-201"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Sales journal reference already reserved but mapping not found");
  }

  @Test
  void resolveReservedSalesJournalEntry_returnsMappedEntryByEntityId() {
    JournalReferenceMapping mapping = journalReferenceMapping("SO-202", "SO-202", 2020L);
    JournalEntry entry = journalEntry(2020L, "SO-202");
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SO-202")))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("SO-202")))
        .thenReturn(Optional.of(mapping));
    when(journalEntryRepository.findByCompanyAndId(eq(company), eq(2020L)))
        .thenReturn(Optional.of(entry));

    assertThat(operations.resolveReservedSalesJournalEntry(company, "SO-202")).contains(entry);
  }

  @Test
  void resolveReservedSalesJournalEntry_fallsBackToMappedCanonicalReference() {
    JournalReferenceMapping mapping = journalReferenceMapping("SO-203", "SALES-203", 2030L);
    JournalEntry entry = journalEntry(2031L, "SALES-203");
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SO-203")))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            eq(company), eq("SO-203")))
        .thenReturn(Optional.of(mapping));
    when(journalEntryRepository.findByCompanyAndId(eq(company), eq(2030L)))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(eq(company), eq("SALES-203")))
        .thenReturn(Optional.of(entry));

    assertThat(operations.resolveReservedSalesJournalEntry(company, "SO-203")).contains(entry);
  }

  @Test
  void postSalesJournal_doesNotTreatCanonicalAliasAsSeparateLookup() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
        .thenReturn(Optional.empty());

    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 7L);
    dealer.setName("Dealer Seven");
    Account receivable = new Account();
    ReflectionFieldAccess.setField(receivable, "id", 77L);
    dealer.setReceivableAccount(receivable);
    when(salesLookupService.requireDealer(company, 7L)).thenReturn(dealer);

    String canonicalReference = SalesOrderReference.invoiceReference("SO-777");
    JournalEntry existing = journalEntry(3001L, canonicalReference);
    when(journalReferenceResolver.findExistingEntry(company, canonicalReference))
        .thenReturn(Optional.of(existing));
    when(accountingService.createStandardJournal(any())).thenReturn(null);

    operations.postSalesJournal(
        7L,
        "SO-777",
        LocalDate.of(2026, 4, 10),
        "Replay sales journal",
        Map.of(88L, new BigDecimal("100.00")),
        null,
        new BigDecimal("100.00"),
        "  " + canonicalReference + "  ");

    verify(journalReferenceResolver).findExistingEntry(company, canonicalReference);
    verify(journalReferenceResolver, never())
        .findExistingEntry(company, "  " + canonicalReference + "  ");
  }

  @Test
  void postSalesJournal_skipsAliasLookupWhenReferenceNumberIsBlank() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
        .thenReturn(Optional.empty());

    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 8L);
    dealer.setName("Dealer Eight");
    Account receivable = new Account();
    ReflectionFieldAccess.setField(receivable, "id", 78L);
    dealer.setReceivableAccount(receivable);
    when(salesLookupService.requireDealer(company, 8L)).thenReturn(dealer);

    String canonicalReference = SalesOrderReference.invoiceReference("SO-778");
    when(journalReferenceResolver.findExistingEntry(company, canonicalReference))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveReferenceMapping(
            eq(company.getId()),
            eq(canonicalReference),
            eq(canonicalReference),
            eq("SALES_JOURNAL"),
            any()))
        .thenReturn(1);
    when(accountingService.createStandardJournal(any())).thenReturn(null);

    operations.postSalesJournal(
        8L,
        "SO-778",
        LocalDate.of(2026, 4, 10),
        "Blank alias journal",
        Map.of(88L, new BigDecimal("100.00")),
        null,
        new BigDecimal("100.00"),
        "   ");

    verify(journalReferenceResolver).findExistingEntry(company, canonicalReference);
    verify(journalReferenceResolver, never()).findExistingEntry(company, "");
  }

  @Test
  void postSalesJournal_looksUpDistinctTrimmedAliasReference() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
        .thenReturn(Optional.empty());

    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 9L);
    dealer.setName("Dealer Nine");
    Account receivable = new Account();
    ReflectionFieldAccess.setField(receivable, "id", 79L);
    dealer.setReceivableAccount(receivable);
    when(salesLookupService.requireDealer(company, 9L)).thenReturn(dealer);

    String canonicalReference = SalesOrderReference.invoiceReference("SO-779");
    JournalEntry existing = journalEntry(3002L, "LEG-779");
    when(journalReferenceResolver.findExistingEntry(company, canonicalReference))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "LEG-779"))
        .thenReturn(Optional.of(existing));
    when(accountingService.createStandardJournal(any())).thenReturn(null);

    operations.postSalesJournal(
        9L,
        "SO-779",
        LocalDate.of(2026, 4, 10),
        "Distinct alias journal",
        Map.of(89L, new BigDecimal("100.00")),
        null,
        new BigDecimal("100.00"),
        "  LEG-779  ");

    verify(journalReferenceResolver).findExistingEntry(company, canonicalReference);
    verify(journalReferenceResolver).findExistingEntry(company, "LEG-779");
  }

  private JournalEntry journalEntry(Long id, String referenceNumber) {
    JournalEntry entry = new JournalEntry();
    ReflectionFieldAccess.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(referenceNumber);
    return entry;
  }

  private JournalReferenceMapping journalReferenceMapping(
      String legacyReference, String canonicalReference, Long entityId) {
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference(legacyReference);
    mapping.setCanonicalReference(canonicalReference);
    mapping.setEntityType("JOURNAL_ENTRY");
    mapping.setEntityId(entityId);
    return mapping;
  }
}
