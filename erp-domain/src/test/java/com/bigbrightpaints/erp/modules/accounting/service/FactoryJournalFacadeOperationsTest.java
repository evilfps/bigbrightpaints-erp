package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class FactoryJournalFacadeOperationsTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountingService accountingService;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private CompanyClock companyClock;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;

  private FactoryJournalFacadeOperations operations;
  private Company company;

  @BeforeEach
  void setUp() {
    operations =
        new FactoryJournalFacadeOperations(
            companyContextService,
            accountingService,
            journalEntryRepository,
            companyClock,
            journalReferenceResolver,
            new AccountingFacadeAccountResolver(accountingLookupService));
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 1L);
    company.setCode("BBP");
  }

  @Test
  void postPackingJournal_returnsExistingEntryWhenReferenceAlreadyExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    JournalEntry existing = journalEntry(1001L, "PACK-42");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PACK-42"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postPackingJournal(
            " PACK-42 ",
            LocalDate.of(2026, 4, 10),
            "packing",
            List.of(line(11L, "Packing", "10.00", "0.00")));

    assertThat(result.id()).isEqualTo(1001L);
    assertThat(result.referenceNumber()).isEqualTo("PACK-42");
  }

  @Test
  void postCostAllocation_returnsNullWhenTotalAmountIsZero() {
    JournalEntryDto result =
        operations.postCostAllocation( //
            "BATCH-1",
            11L,
            12L,
            13L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "allocation");

    assertThat(result).isNull();
  }

  @Test
  void postCostAllocation_returnsExistingEntryWhenReferenceAlreadyExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 11L)).thenReturn(account(11L));
    when(accountingLookupService.requireAccount(company, 12L)).thenReturn(account(12L));
    when(accountingLookupService.requireAccount(company, 13L)).thenReturn(account(13L));
    JournalEntry existing = journalEntry(1002L, "CAL-BATCH-1");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "CAL-BATCH-1"))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postCostAllocation(
            "BATCH-1",
            11L,
            12L,
            13L,
            new BigDecimal("25.00"),
            new BigDecimal("5.00"),
            "allocation");

    assertThat(result.id()).isEqualTo(1002L);
    assertThat(result.referenceNumber()).isEqualTo("CAL-BATCH-1");
  }

  @Test
  void postCostAllocation_postsStandardJournalWhenNoEntryExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 11L)).thenReturn(account(11L));
    when(accountingLookupService.requireAccount(company, 12L)).thenReturn(account(12L));
    when(accountingLookupService.requireAccount(company, 13L)).thenReturn(account(13L));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "CAL-BATCH-3"))
        .thenReturn(Optional.empty());
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 4, 10));
    when(accountingService.createStandardJournal(org.mockito.ArgumentMatchers.any()))
        .thenReturn(journalEntryDto(2001L, "CAL-BATCH-3"));

    JournalEntryDto result =
        operations.postCostAllocation(
            "BATCH-3",
            11L,
            12L,
            13L,
            new BigDecimal("20.00"),
            new BigDecimal("5.00"),
            "allocation");

    assertThat(result.referenceNumber()).isEqualTo("CAL-BATCH-3");
  }

  @Test
  void postCogs_returnsNullWhenCostIsZero() {
    JournalEntryDto result =
        operations.postCOGS("SO-42", 7L, 21L, 22L, BigDecimal.ZERO, "COGS");

    assertThat(result).isNull();
  }

  @Test
  void postCogs_returnsExistingEntryWhenReferenceAlreadyExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 21L)).thenReturn(account(21L));
    when(accountingLookupService.requireAccount(company, 22L)).thenReturn(account(22L));
    String reference = SalesOrderReference.cogsReference("SO-88");
    JournalEntry existing = journalEntry(1004L, reference);
    when(journalReferenceResolver.findExistingEntry(company, reference))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postCOGS("SO-88", 7L, 21L, 22L, new BigDecimal("10.00"), "COGS");

    assertThat(result.id()).isEqualTo(1004L);
    assertThat(result.referenceNumber()).isEqualTo(reference);
  }

  @Test
  void postCogs_postsStandardJournalWhenNoEntryExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 21L)).thenReturn(account(21L));
    when(accountingLookupService.requireAccount(company, 22L)).thenReturn(account(22L));
    String reference = SalesOrderReference.cogsReference("SO-89");
    when(journalReferenceResolver.findExistingEntry(company, reference)).thenReturn(Optional.empty());
    when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 4, 10));
    when(accountingService.createStandardJournal(org.mockito.ArgumentMatchers.any()))
        .thenReturn(journalEntryDto(2002L, reference));

    JournalEntryDto result =
        operations.postCOGS("SO-89", 7L, 21L, 22L, new BigDecimal("10.00"), "COGS");

    assertThat(result.referenceNumber()).isEqualTo(reference);
  }

  @Test
  void postCogsJournal_returnsExistingEntryWhenReferenceAlreadyExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    String reference = SalesOrderReference.cogsReference("SO-42");
    JournalEntry existing = journalEntry(1003L, reference);
    when(journalReferenceResolver.findExistingEntry(company, reference))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postCogsJournal(
            "SO-42",
            7L,
            LocalDate.of(2026, 4, 10),
            "COGS",
            List.of(line(21L, "COGS", "10.00", "0.00"), line(22L, "Inventory", "0.00", "10.00")));

    assertThat(result.id()).isEqualTo(1003L);
    assertThat(result.referenceNumber()).isEqualTo(reference);
  }

  @Test
  void postCostVarianceAllocation_returnsNullWhenVarianceIsZero() {
    JournalEntryDto result =
        operations.postCostVarianceAllocation(
            "BATCH-2",
            "2026-04",
            LocalDate.of(2026, 4, 10),
            11L,
            12L,
            13L,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "variance");

    assertThat(result).isNull();
  }

  @Test
  void postCostVarianceAllocation_returnsExistingEntryWhenReferenceAlreadyExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 11L)).thenReturn(account(11L));
    when(accountingLookupService.requireAccount(company, 12L)).thenReturn(account(12L));
    when(accountingLookupService.requireAccount(company, 13L)).thenReturn(account(13L));
    String reference = "CVAR-BATCH-4-2026-04";
    JournalEntry existing = journalEntry(1005L, reference);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, reference))
        .thenReturn(Optional.of(existing));

    JournalEntryDto result =
        operations.postCostVarianceAllocation(
            "BATCH-4",
            "2026-04",
            LocalDate.of(2026, 4, 10),
            11L,
            12L,
            13L,
            new BigDecimal("2.00"),
            BigDecimal.ZERO,
            "variance");

    assertThat(result.id()).isEqualTo(1005L);
    assertThat(result.referenceNumber()).isEqualTo(reference);
  }

  @Test
  void postCostVarianceAllocation_postsStandardJournalWhenVarianceExists() {
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(accountingLookupService.requireAccount(company, 11L)).thenReturn(account(11L));
    when(accountingLookupService.requireAccount(company, 12L)).thenReturn(account(12L));
    when(accountingLookupService.requireAccount(company, 13L)).thenReturn(account(13L));
    String reference = "CVAR-BATCH-5-2026-04";
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, reference))
        .thenReturn(Optional.empty());
    when(accountingService.createStandardJournal(org.mockito.ArgumentMatchers.any()))
        .thenReturn(journalEntryDto(2003L, reference));

    JournalEntryDto result =
        operations.postCostVarianceAllocation(
            "BATCH-5",
            "2026-04",
            LocalDate.of(2026, 4, 10),
            11L,
            12L,
            13L,
            new BigDecimal("2.00"),
            BigDecimal.ZERO,
            "variance");

    assertThat(result.referenceNumber()).isEqualTo(reference);
  }

  private JournalEntryRequest.JournalLineRequest line(
      Long accountId, String description, String debit, String credit) {
    return new JournalEntryRequest.JournalLineRequest(
        accountId, description, new BigDecimal(debit), new BigDecimal(credit));
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
