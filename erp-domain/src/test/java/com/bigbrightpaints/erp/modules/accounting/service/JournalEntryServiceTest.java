package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
    @Mock private AccountingIdempotencyService accountingIdempotencyService;

    private JournalEntryService journalEntryService;
    private Company company;

    @BeforeEach
    void setUp() {
        journalEntryService = new JournalEntryService(
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
                accountingEventStore,
                accountingIdempotencyService
        );
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
        AtomicReference<com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry> savedEntry = new AtomicReference<>();

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
        when(journalReferenceMappingRepository.reserveManualReference(eq(44L), anyString(), anyString(), eq("JOURNAL_ENTRY"), any()))
                .thenReturn(1);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(company, "manual-local-ref"))
                .thenReturn(List.of(mapping));
        when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        when(accountingPeriodService.requirePostablePeriod(eq(company), eq(tenantToday), eq("MANUAL"), eq("manual-local-ref"), eq("Tenant-aware manual"), eq(false)))
                .thenReturn(postingPeriod);
        when(accountRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(creditAccount));
        when(accountRepository.updateBalanceAtomic(eq(company), eq(11L), eq(new BigDecimal("25.00")))).thenReturn(1);
        when(accountRepository.updateBalanceAtomic(eq(company), eq(22L), eq(new BigDecimal("-25.00")))).thenReturn(1);
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> {
            com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry entry = invocation.getArgument(0);
            savedEntry.set(entry);
            ReflectionTestUtils.setField(entry, "id", 4401L);
            return entry;
        });

        JournalEntryDto result = journalEntryService.createStandardJournal(new JournalCreationRequest(
                new BigDecimal("25.00"),
                11L,
                22L,
                "Tenant-aware manual",
                "MANUAL",
                "manual-local-ref",
                null,
                List.of(
                        new JournalCreationRequest.LineRequest(11L, new BigDecimal("25.00"), BigDecimal.ZERO, "Debit"),
                        new JournalCreationRequest.LineRequest(22L, BigDecimal.ZERO, new BigDecimal("25.00"), "Credit")
                ),
                null,
                null,
                null,
                false
        ));

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
        AtomicReference<com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry> savedEntry = new AtomicReference<>();

        when(companyClock.today(company)).thenReturn(explicitDate);
        lenient().when(companyClock.today((Company) null)).thenReturn(explicitDate);
        when(referenceNumberService.nextJournalReference(company)).thenReturn("JRN-4402");
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "manual-explicit-ref"))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "JRN-4402"))
                .thenReturn(Optional.empty());
        when(journalReferenceResolver.findExistingEntry(company, "manual-explicit-ref"))
                .thenReturn(Optional.empty());
        when(journalReferenceMappingRepository.reserveManualReference(eq(44L), anyString(), anyString(), eq("JOURNAL_ENTRY"), any()))
                .thenReturn(1);
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(company, "manual-explicit-ref"))
                .thenReturn(List.of(mapping));
        when(systemSettingsService.isPeriodLockEnforced()).thenReturn(true);
        when(accountingPeriodService.requirePostablePeriod(eq(company), eq(explicitDate), eq("MANUAL"), eq("manual-explicit-ref"), eq("Explicit date manual"), eq(false)))
                .thenReturn(postingPeriod);
        when(accountRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(debitAccount));
        when(accountRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(creditAccount));
        when(accountRepository.updateBalanceAtomic(eq(company), eq(11L), eq(new BigDecimal("25.00")))).thenReturn(1);
        when(accountRepository.updateBalanceAtomic(eq(company), eq(22L), eq(new BigDecimal("-25.00")))).thenReturn(1);
        when(journalEntryRepository.save(any())).thenAnswer(invocation -> {
            com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry entry = invocation.getArgument(0);
            savedEntry.set(entry);
            ReflectionTestUtils.setField(entry, "id", 4402L);
            return entry;
        });

        JournalEntryDto result = journalEntryService.createStandardJournal(new JournalCreationRequest(
                new BigDecimal("25.00"),
                11L,
                22L,
                "Explicit date manual",
                "MANUAL",
                "manual-explicit-ref",
                null,
                List.of(
                        new JournalCreationRequest.LineRequest(11L, new BigDecimal("25.00"), BigDecimal.ZERO, "Debit"),
                        new JournalCreationRequest.LineRequest(22L, BigDecimal.ZERO, new BigDecimal("25.00"), "Credit")
                ),
                explicitDate,
                null,
                null,
                false
        ));

        assertThat(result).isNotNull();
        assertThat(result.entryDate()).isEqualTo(explicitDate);
        assertThat(savedEntry.get()).isNotNull();
        assertThat(savedEntry.get().getEntryDate()).isEqualTo(explicitDate);
    }

    @Test
    void compatibilityConstructorLeavesCompanyDependenciesUnset() {
        JournalEntryService service = new JournalEntryService((AccountingCoreEngine) null, accountingIdempotencyService);

        assertThat(ReflectionTestUtils.getField(service, "companyContextService")).isNull();
        assertThat(ReflectionTestUtils.getField(service, "companyClock")).isNull();
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
}
