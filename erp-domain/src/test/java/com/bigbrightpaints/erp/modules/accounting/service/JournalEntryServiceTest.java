package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class JournalEntryServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private DealerLedgerService dealerLedgerService;
  @Mock private SupplierLedgerService supplierLedgerService;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private PayrollRunLineRepository payrollRunLineRepository;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private ReferenceNumberService referenceNumberService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CompanyClock companyClock;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private SupplierRepository supplierRepository;
  @Mock private InvoiceSettlementPolicy invoiceSettlementPolicy;
  @Mock private JournalReferenceResolver journalReferenceResolver;
  @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Mock private EntityManager entityManager;
  @Mock private SystemSettingsService systemSettingsService;
  @Mock private AuditService auditService;
  @Mock private AccountingEventStore accountingEventStore;

  private JournalEntryService journalEntryService;
  private Company company;

  @BeforeEach
  void setUp() {
    journalEntryService =
        new JournalEntryService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            dealerLedgerService,
            supplierLedgerService,
            payrollRunRepository,
            payrollRunLineRepository,
            accountingPeriodService,
            referenceNumberService,
            eventPublisher,
            companyClock,
            companyEntityLookup,
            settlementAllocationRepository,
            rawMaterialPurchaseRepository,
            invoiceRepository,
            rawMaterialMovementRepository,
            rawMaterialBatchRepository,
            finishedGoodBatchRepository,
            dealerRepository,
            supplierRepository,
            invoiceSettlementPolicy,
            journalReferenceResolver,
            journalReferenceMappingRepository,
            entityManager,
            systemSettingsService,
            auditService,
            accountingEventStore);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 44L);
    company.setBaseCurrency("INR");
    company.setTimezone("Pacific/Auckland");
    new CompanyTime(companyClock);
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void createStandardJournal_manualSourceDefaultsNullEntryDateFromCurrentCompanyClock() {
    LocalDate tenantToday = LocalDate.of(2026, 3, 1);
    Instant tenantNow = Instant.parse("2024-02-29T12:00:00Z");
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(tenantToday.getYear());
    postingPeriod.setMonth(tenantToday.getMonthValue());
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    Account debitAccount = account(11L, "CASH-11", AccountType.ASSET);
    Account creditAccount = account(22L, "REV-22", AccountType.REVENUE);
    AtomicReference<com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry> savedEntry =
        new AtomicReference<>();

    when(companyClock.today(company)).thenReturn(tenantToday);
    lenient().when(companyClock.today((Company) null)).thenReturn(tenantToday.minusDays(1));
    when(companyClock.now(company)).thenReturn(tenantNow);
    when(referenceNumberService.nextJournalReference(company)).thenReturn("JRN-4401");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "manual-local-ref"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "JRN-4401"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "manual-local-ref"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(44L), anyString(), anyString(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(1);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "manual-local-ref"))
        .thenReturn(List.of(mapping));
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company),
            eq(tenantToday),
            eq("MANUAL"),
            eq("manual-local-ref"),
            eq("Tenant-aware manual"),
            eq(false)))
        .thenReturn(postingPeriod);
    when(accountRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), eq(11L), eq(new BigDecimal("25.00"))))
        .thenReturn(1);
    when(accountRepository.updateBalanceAtomic(eq(company), eq(22L), eq(new BigDecimal("-25.00"))))
        .thenReturn(1);
    when(journalEntryRepository.save(any()))
        .thenAnswer(
            invocation -> {
              com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry entry =
                  invocation.getArgument(0);
              savedEntry.set(entry);
              ReflectionTestUtils.setField(entry, "id", 4401L);
              return entry;
            });

    JournalEntryDto result =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                new BigDecimal("25.00"),
                11L,
                22L,
                "Tenant-aware manual",
                "MANUAL",
                "manual-local-ref",
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        11L, new BigDecimal("25.00"), BigDecimal.ZERO, "Debit"),
                    new JournalCreationRequest.LineRequest(
                        22L, BigDecimal.ZERO, new BigDecimal("25.00"), "Credit")),
                null,
                null,
                null,
                false));

    assertThat(result).isNotNull();
    assertThat(result.entryDate()).isEqualTo(tenantToday);
    assertThat(result.referenceNumber()).isEqualTo("JRN-4401");
    assertThat(savedEntry.get()).isNotNull();
    assertThat(savedEntry.get().getSourceReference()).isEqualTo("manual-local-ref");
  }

  @Test
  void createStandardJournal_manualSourceKeepsExplicitEntryDate() {
    LocalDate explicitDate = LocalDate.of(2026, 3, 5);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(explicitDate.getYear());
    postingPeriod.setMonth(explicitDate.getMonthValue());
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    Account debitAccount = account(11L, "CASH-11", AccountType.ASSET);
    Account creditAccount = account(22L, "REV-22", AccountType.REVENUE);
    AtomicReference<com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry> savedEntry =
        new AtomicReference<>();

    when(companyClock.today(company)).thenReturn(explicitDate);
    lenient().when(companyClock.today((Company) null)).thenReturn(explicitDate);
    when(referenceNumberService.nextJournalReference(company)).thenReturn("JRN-4402");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "manual-explicit-ref"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "JRN-4402"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "manual-explicit-ref"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(44L), anyString(), anyString(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(1);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "manual-explicit-ref"))
        .thenReturn(List.of(mapping));
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company),
            eq(explicitDate),
            eq("MANUAL"),
            eq("manual-explicit-ref"),
            eq("Explicit date manual"),
            eq(false)))
        .thenReturn(postingPeriod);
    when(accountRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), eq(11L), eq(new BigDecimal("25.00"))))
        .thenReturn(1);
    when(accountRepository.updateBalanceAtomic(eq(company), eq(22L), eq(new BigDecimal("-25.00"))))
        .thenReturn(1);
    when(journalEntryRepository.save(any()))
        .thenAnswer(
            invocation -> {
              com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry entry =
                  invocation.getArgument(0);
              savedEntry.set(entry);
              ReflectionTestUtils.setField(entry, "id", 4402L);
              return entry;
            });

    JournalEntryDto result =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                new BigDecimal("25.00"),
                11L,
                22L,
                "Explicit date manual",
                "MANUAL",
                "manual-explicit-ref",
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        11L, new BigDecimal("25.00"), BigDecimal.ZERO, "Debit"),
                    new JournalCreationRequest.LineRequest(
                        22L, BigDecimal.ZERO, new BigDecimal("25.00"), "Credit")),
                explicitDate,
                null,
                null,
                false));

    assertThat(result).isNotNull();
    assertThat(result.entryDate()).isEqualTo(explicitDate);
    assertThat(savedEntry.get()).isNotNull();
    assertThat(savedEntry.get().getEntryDate()).isEqualTo(explicitDate);
  }

  @Test
  void createStandardJournal_overrideRequestedWithoutAuthorityUsesUnauthorizedPostablePeriodFlag() {
    LocalDate explicitDate = LocalDate.of(2026, 3, 7);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(explicitDate.getYear());
    postingPeriod.setMonth(explicitDate.getMonthValue());
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    Account debitAccount = account(11L, "CASH-11", AccountType.ASSET);
    Account creditAccount = account(22L, "REV-22", AccountType.REVENUE);

    when(companyClock.today(company)).thenReturn(explicitDate);
    lenient().when(companyClock.today((Company) null)).thenReturn(explicitDate);
    when(referenceNumberService.nextJournalReference(company)).thenReturn("JRN-4403");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "manual-override-ref"))
        .thenReturn(Optional.empty());
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "JRN-4403"))
        .thenReturn(Optional.empty());
    when(journalReferenceResolver.findExistingEntry(company, "manual-override-ref"))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.reserveManualReference(
            eq(44L), anyString(), anyString(), eq("JOURNAL_ENTRY"), any()))
        .thenReturn(1);
    when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, "manual-override-ref"))
        .thenReturn(List.of(mapping));
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company),
            eq(explicitDate),
            eq("MANUAL"),
            eq("manual-override-ref"),
            eq("Override requested"),
            eq(false)))
        .thenReturn(postingPeriod);
    when(accountRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), eq(11L), eq(new BigDecimal("25.00"))))
        .thenReturn(1);
    when(accountRepository.updateBalanceAtomic(eq(company), eq(22L), eq(new BigDecimal("-25.00"))))
        .thenReturn(1);
    when(journalEntryRepository.save(any()))
        .thenAnswer(
            invocation -> {
              com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry entry =
                  invocation.getArgument(0);
              ReflectionTestUtils.setField(entry, "id", 4403L);
              return entry;
            });

    JournalEntryDto result =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                new BigDecimal("25.00"),
                11L,
                22L,
                "Override requested",
                "MANUAL",
                "manual-override-ref",
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        11L, new BigDecimal("25.00"), BigDecimal.ZERO, "Debit"),
                    new JournalCreationRequest.LineRequest(
                        22L, BigDecimal.ZERO, new BigDecimal("25.00"), "Credit")),
                explicitDate,
                null,
                null,
                true));

    assertThat(result).isNotNull();
    verify(accountingPeriodService)
        .requirePostablePeriod(
            eq(company),
            eq(explicitDate),
            eq("MANUAL"),
            eq("manual-override-ref"),
            eq("Override requested"),
            eq(false));
  }

  @Test
  void reverseJournalEntry_cascadeRequestDisablesReplayForPrimaryAndRelatedEntries() {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    LocalDate today = LocalDate.of(2026, 3, 6);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(today.getYear());
    postingPeriod.setMonth(today.getMonthValue());
    Account cashAccount = account(11L, "CASH", AccountType.ASSET);
    Account revenueAccount = account(22L, "REV", AccountType.REVENUE);
    Account expenseAccount = account(33L, "EXP", AccountType.EXPENSE);
    JournalEntry primaryEntry =
        reversalSourceEntry(700L, "INV-700", today, cashAccount, revenueAccount);
    JournalEntry relatedEntry =
        reversalSourceEntry(701L, "INV-700-COGS", today, expenseAccount, cashAccount);
    JournalEntry primaryReversal = reversalResult(900L, "REV-INV-700");
    JournalEntry relatedReversal = reversalResult(901L, "REV-INV-700-COGS");

    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), anyString(), anyString(), any(), eq(false)))
        .thenReturn(postingPeriod);
    when(referenceNumberService.reversalReference("INV-700")).thenReturn("REV-INV-700");
    when(referenceNumberService.reversalReference("INV-700-COGS")).thenReturn("REV-INV-700-COGS");
    when(companyEntityLookup.requireJournalEntry(company, 700L)).thenReturn(primaryEntry);
    when(companyEntityLookup.requireJournalEntry(company, 900L)).thenReturn(primaryReversal);
    when(companyEntityLookup.requireJournalEntry(company, 901L)).thenReturn(relatedReversal);
    when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, "INV-700-"))
        .thenReturn(List.of(relatedEntry));
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(900L), stubEntry(901L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Cascade test",
            "Primary cascade memo",
            Boolean.FALSE,
            null,
            true,
            List.of(701L),
            JournalEntryReversalRequest.ReversalReasonCode.WRONG_ACCOUNT,
            "checker.user",
            "DOC-900");

    JournalEntryDto result = journalEntryService.reverseJournalEntry(700L, request);

    assertThat(result.id()).isEqualTo(900L);
    assertThat(primaryEntry.getStatus()).isEqualTo("REVERSED");
    assertThat(relatedEntry.getStatus()).isEqualTo("REVERSED");
    assertThat(primaryReversal.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(relatedReversal.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
    assertThat(primaryReversal.getCorrectionReason())
        .contains("[WRONG_ACCOUNT] Cascade test")
        .contains("Approved by: checker.user")
        .contains("Doc: DOC-900");
    assertThat(relatedReversal.getCorrectionReason())
        .contains("[WRONG_ACCOUNT] Cascade reversal: Cascade test")
        .contains("Approved by: checker.user")
        .contains("Doc: DOC-900");
    ArgumentCaptor<JournalEntryRequest> requestCaptor =
        ArgumentCaptor.forClass(JournalEntryRequest.class);
    verify(journalEntryService, times(2)).createJournalEntry(requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .extracting(JournalEntryRequest::memo)
        .containsExactly("Primary cascade memo", "Cascade from INV-700");
    verify(journalEntryRepository, times(1))
        .findByCompanyAndReferenceNumberStartingWith(company, "INV-700-");
  }

  @Test
  void reverseJournalEntry_nullRequestUsesDirectReversePath() {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    LocalDate today = LocalDate.of(2026, 3, 6);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(today.getYear());
    postingPeriod.setMonth(today.getMonthValue());
    Account cashAccount = account(11L, "CASH", AccountType.ASSET);
    Account revenueAccount = account(22L, "REV", AccountType.REVENUE);
    JournalEntry primaryEntry =
        reversalSourceEntry(702L, "INV-702", today, cashAccount, revenueAccount);
    JournalEntry directReversal = reversalResult(902L, "REV-INV-702");

    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), anyString(), anyString(), any(), eq(false)))
        .thenReturn(postingPeriod);
    when(referenceNumberService.reversalReference("INV-702")).thenReturn("REV-INV-702");
    when(companyEntityLookup.requireJournalEntry(company, 702L)).thenReturn(primaryEntry);
    when(companyEntityLookup.requireJournalEntry(company, 902L)).thenReturn(directReversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(902L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryDto result = journalEntryService.reverseJournalEntry(702L, null);

    assertThat(result.id()).isEqualTo(902L);
    assertThat(primaryEntry.getStatus()).isEqualTo("REVERSED");
    verify(journalEntryRepository, times(0))
        .findByCompanyAndReferenceNumberStartingWith(company, "INV-702-");
  }

  @Test
  void reverseJournalEntry_relatedEntryIdsTriggerCascadeWithoutCascadeFlag() {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    LocalDate today = LocalDate.of(2026, 3, 6);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(today.getYear());
    postingPeriod.setMonth(today.getMonthValue());
    Account cashAccount = account(11L, "CASH", AccountType.ASSET);
    Account revenueAccount = account(22L, "REV", AccountType.REVENUE);
    Account expenseAccount = account(33L, "EXP", AccountType.EXPENSE);
    JournalEntry primaryEntry =
        reversalSourceEntry(710L, "INV-710", today, cashAccount, revenueAccount);
    JournalEntry relatedEntry =
        reversalSourceEntry(711L, "INV-710-COGS", today, expenseAccount, cashAccount);
    JournalEntry primaryReversal = reversalResult(910L, "REV-INV-710");
    JournalEntry relatedReversal = reversalResult(911L, "REV-INV-710-COGS");

    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), anyString(), anyString(), any(), eq(false)))
        .thenReturn(postingPeriod);
    when(referenceNumberService.reversalReference("INV-710")).thenReturn("REV-INV-710");
    when(referenceNumberService.reversalReference("INV-710-COGS")).thenReturn("REV-INV-710-COGS");
    when(companyEntityLookup.requireJournalEntry(company, 710L)).thenReturn(primaryEntry);
    when(companyEntityLookup.requireJournalEntry(company, 910L)).thenReturn(primaryReversal);
    when(companyEntityLookup.requireJournalEntry(company, 911L)).thenReturn(relatedReversal);
    when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, "INV-710-"))
        .thenReturn(List.of(relatedEntry));
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(910L), stubEntry(911L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Cascade via related ids",
            "Primary cascade memo",
            Boolean.FALSE,
            null,
            false,
            List.of(711L),
            JournalEntryReversalRequest.ReversalReasonCode.WRONG_ACCOUNT,
            "checker.user",
            "DOC-910");

    JournalEntryDto result = journalEntryService.reverseJournalEntry(710L, request);

    assertThat(result.id()).isEqualTo(910L);
    assertThat(primaryEntry.getStatus()).isEqualTo("REVERSED");
    assertThat(relatedEntry.getStatus()).isEqualTo("REVERSED");
    verify(journalEntryRepository, times(1))
        .findByCompanyAndReferenceNumberStartingWith(company, "INV-710-");
  }

  @Test
  void reverseJournalEntry_emptyRelatedIdsUsesDirectReversePath() {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    LocalDate today = LocalDate.of(2026, 3, 6);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(today.getYear());
    postingPeriod.setMonth(today.getMonthValue());
    Account cashAccount = account(11L, "CASH", AccountType.ASSET);
    Account revenueAccount = account(22L, "REV", AccountType.REVENUE);
    JournalEntry primaryEntry =
        reversalSourceEntry(720L, "INV-720", today, cashAccount, revenueAccount);
    JournalEntry directReversal = reversalResult(920L, "REV-INV-720");

    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), anyString(), anyString(), any(), eq(false)))
        .thenReturn(postingPeriod);
    when(referenceNumberService.reversalReference("INV-720")).thenReturn("REV-INV-720");
    when(companyEntityLookup.requireJournalEntry(company, 720L)).thenReturn(primaryEntry);
    when(companyEntityLookup.requireJournalEntry(company, 920L)).thenReturn(directReversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(920L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryReversalRequest request =
        new JournalEntryReversalRequest(
            today,
            false,
            "Direct reverse",
            "Direct memo",
            Boolean.FALSE,
            null,
            false,
            List.of(),
            null,
            null,
            null);

    JournalEntryDto result = journalEntryService.reverseJournalEntry(720L, request);

    assertThat(result.id()).isEqualTo(920L);
    assertThat(primaryEntry.getStatus()).isEqualTo("REVERSED");
    verify(journalEntryRepository, times(0))
        .findByCompanyAndReferenceNumberStartingWith(company, "INV-720-");
  }

  @Test
  void reverseJournalEntry_overrideRequestedWithoutAuthorityUsesUnauthorizedPostablePeriodFlag() {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    LocalDate today = LocalDate.of(2026, 3, 8);
    AccountingPeriod postingPeriod = new AccountingPeriod();
    postingPeriod.setYear(today.getYear());
    postingPeriod.setMonth(today.getMonthValue());
    Account cashAccount = account(11L, "CASH", AccountType.ASSET);
    Account revenueAccount = account(22L, "REV", AccountType.REVENUE);
    JournalEntry primaryEntry =
        reversalSourceEntry(730L, "INV-730", today, cashAccount, revenueAccount);
    JournalEntry directReversal = reversalResult(930L, "REV-INV-730");

    when(companyClock.today(company)).thenReturn(today);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
    when(accountingPeriodService.requirePostablePeriod(
            eq(company), eq(today), anyString(), anyString(), any(), eq(false)))
        .thenReturn(postingPeriod);
    when(referenceNumberService.reversalReference("INV-730")).thenReturn("REV-INV-730");
    when(companyEntityLookup.requireJournalEntry(company, 730L)).thenReturn(primaryEntry);
    when(companyEntityLookup.requireJournalEntry(company, 930L)).thenReturn(directReversal);
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doReturn(stubEntry(930L))
        .when(journalEntryService)
        .createJournalEntry(any(JournalEntryRequest.class));

    JournalEntryDto result =
        journalEntryService.reverseJournalEntry(
            730L,
            new JournalEntryReversalRequest(
                today,
                false,
                "Unauthorized override request",
                "Direct memo",
                Boolean.TRUE,
                null,
                false,
                List.of(),
                null,
                null,
                null));

    assertThat(result.id()).isEqualTo(930L);
    verify(accountingPeriodService)
        .requirePostablePeriod(
            eq(company), eq(today), eq("JOURNAL_REVERSAL"), eq("INV-730"), any(), eq(false));
  }

  @Test
  void createJournalEntryForReversal_usesSystemEntryDateOverrideScopeWhenRequested()
      throws ClassNotFoundException {
    journalEntryService = org.mockito.Mockito.spy(journalEntryService);
    JournalEntryRequest payload =
        new JournalEntryRequest(
            "REV-900",
            LocalDate.of(2026, 3, 9),
            "Scoped override",
            null,
            null,
            Boolean.TRUE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    11L, "Debit", new BigDecimal("50.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    22L, "Credit", BigDecimal.ZERO, new BigDecimal("50.00"))));
    @SuppressWarnings("unchecked")
    ThreadLocal<Boolean> overrideScope =
        (ThreadLocal<Boolean>)
            ReflectionTestUtils.getField(
                Class.forName(
                    "com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreEngineCore"),
                "SYSTEM_ENTRY_DATE_OVERRIDE");
    AtomicReference<Boolean> observedOverride = new AtomicReference<>(Boolean.FALSE);
    doAnswer(
            invocation -> {
              observedOverride.set(Boolean.TRUE.equals(overrideScope.get()));
              return stubEntry(940L);
            })
        .when(journalEntryService)
        .createJournalEntry(payload);

    JournalEntryDto result =
        ReflectionTestUtils.invokeMethod(
            journalEntryService, "createJournalEntryForReversal", payload, true);

    assertThat(result.id()).isEqualTo(940L);
    assertThat(observedOverride.get()).isTrue();
    assertThat(Boolean.TRUE.equals(overrideScope.get())).isFalse();
  }

  private Account account(Long id, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(company);
    account.setCode(code);
    account.setType(type);
    account.setActive(true);
    account.setBalance(BigDecimal.ZERO);
    return account;
  }

  private JournalEntryDto stubEntry(long id) {
    return new JournalEntryDto(
        id,
        null,
        "JRN-" + id,
        LocalDate.of(2026, 3, 6),
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
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private JournalEntry reversalSourceEntry(
      Long id, String reference, LocalDate entryDate, Account debitAccount, Account creditAccount) {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(entryDate);
    entry.setMemo("Original " + reference);
    entry.setStatus("POSTED");
    entry.addLine(journalLine(entry, debitAccount, new BigDecimal("100.00"), BigDecimal.ZERO));
    entry.addLine(journalLine(entry, creditAccount, BigDecimal.ZERO, new BigDecimal("100.00")));
    return entry;
  }

  private JournalEntry reversalResult(Long id, String reference) {
    JournalEntry entry = new JournalEntry();
    ReflectionTestUtils.setField(entry, "id", id);
    entry.setCompany(company);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(LocalDate.of(2026, 3, 6));
    entry.setStatus("POSTED");
    return entry;
  }

  private JournalLine journalLine(
      JournalEntry entry, Account account, BigDecimal debit, BigDecimal credit) {
    JournalLine line = new JournalLine();
    line.setJournalEntry(entry);
    line.setAccount(account);
    line.setDescription("line-" + account.getCode());
    line.setDebit(debit);
    line.setCredit(credit);
    return line;
  }
}
