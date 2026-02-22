package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingFacadePeriodCloseBoundaryTest {

    @Test
    void reverseClosingEntryForPeriodReopen_delegatesToAccountingService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingFacade facade = new AccountingFacade(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                accountingService,
                mock(JournalEntryRepository.class),
                mock(ReferenceNumberService.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyAccountingSettingsService.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class)
        );

        JournalEntry entry = new JournalEntry();
        AccountingPeriod period = new AccountingPeriod();
        facade.reverseClosingEntryForPeriodReopen(entry, period, "reopen for correction");

        boolean delegated = mockingDetails(accountingService).getInvocations().stream()
                .anyMatch(invocation ->
                        "reverseClosingEntryForPeriodReopen".equals(invocation.getMethod().getName())
                                && invocation.getArguments().length == 3
                                && invocation.getArguments()[0] == entry
                                && invocation.getArguments()[1] == period
                                && "reopen for correction".equals(invocation.getArguments()[2]));

        assertThat(delegated).isTrue();
    }

    @Test
    void postPurchaseJournal_legacyFallback_prefersCanonicalNumericSequence() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        AccountingService accountingService = mock(AccountingService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);
        JournalReferenceMappingRepository journalReferenceMappingRepository = mock(JournalReferenceMappingRepository.class);

        AccountingFacade facade = new AccountingFacade(
                companyContextService,
                mock(AccountRepository.class),
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                companyEntityLookup,
                mock(CompanyAccountingSettingsService.class),
                journalReferenceResolver,
                journalReferenceMappingRepository
        );

        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Supplier supplier = new Supplier();
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 301L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.requireSupplier(company, 88L)).thenReturn(supplier);

        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", 201L);
        when(companyEntityLookup.requireAccount(company, 201L)).thenReturn(inventory);

        String baseReference = "RMP-SUP-INV100";
        when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-100"))
                .thenReturn(baseReference);
        when(journalReferenceResolver.findExistingEntry(company, baseReference))
                .thenReturn(Optional.empty());

        JournalEntry nonCanonical = new JournalEntry();
        ReflectionTestUtils.setField(nonCanonical, "id", 900L);
        nonCanonical.setReferenceNumber(baseReference + "-0001-REV");

        JournalEntry canonicalSecond = new JournalEntry();
        ReflectionTestUtils.setField(canonicalSecond, "id", 502L);
        canonicalSecond.setReferenceNumber(baseReference + "-0002");

        JournalEntry canonicalFirst = new JournalEntry();
        ReflectionTestUtils.setField(canonicalFirst, "id", 501L);
        canonicalFirst.setReferenceNumber(baseReference + "-0001");

        when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, baseReference + "-"))
                .thenReturn(List.of(nonCanonical, canonicalSecond, canonicalFirst));
        when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(company, baseReference))
                .thenReturn(Optional.empty());

        assertThat(facade.postPurchaseJournal(
                88L,
                "INV-100",
                LocalDate.of(2026, 2, 21),
                "legacy replay",
                Map.of(201L, new BigDecimal("100.00")),
                null,
                new BigDecimal("100.00"),
                null
        ).referenceNumber()).isEqualTo(baseReference + "-0001");

        verify(accountingService, never()).createJournalEntry(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void postPurchaseJournal_existingBaseReference_shortCircuitsIdempotently() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        AccountingService accountingService = mock(AccountingService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);

        AccountingFacade facade = new AccountingFacade(
                companyContextService,
                mock(AccountRepository.class),
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                companyEntityLookup,
                mock(CompanyAccountingSettingsService.class),
                journalReferenceResolver,
                mock(JournalReferenceMappingRepository.class)
        );

        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Supplier supplier = new Supplier();
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 401L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.requireSupplier(company, 99L)).thenReturn(supplier);

        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", 501L);
        when(companyEntityLookup.requireAccount(company, 501L)).thenReturn(inventory);

        String baseReference = "RMP-LEGACY-REF";
        when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-777"))
                .thenReturn(baseReference);

        JournalEntry existing = new JournalEntry();
        ReflectionTestUtils.setField(existing, "id", 777L);
        existing.setReferenceNumber(baseReference + "-0003");
        when(journalReferenceResolver.findExistingEntry(company, baseReference))
                .thenReturn(Optional.of(existing));

        assertThat(facade.postPurchaseJournal(
                99L,
                "INV-777",
                LocalDate.of(2026, 2, 21),
                "idempotent replay",
                Map.of(501L, new BigDecimal("220.00")),
                null,
                new BigDecimal("220.00"),
                null
        ).referenceNumber()).isEqualTo(baseReference + "-0003");

        verify(journalEntryRepository, never()).findByCompanyAndReferenceNumberStartingWith(
                company, baseReference + "-");
        verify(accountingService, never()).createJournalEntry(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void postPurchaseJournal_withoutCanonicalLegacyEntry_createsNewJournal() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        AccountingService accountingService = mock(AccountingService.class);
        JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
        ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);
        JournalReferenceMappingRepository journalReferenceMappingRepository = mock(JournalReferenceMappingRepository.class);

        AccountingFacade facade = new AccountingFacade(
                companyContextService,
                mock(AccountRepository.class),
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                companyEntityLookup,
                mock(CompanyAccountingSettingsService.class),
                journalReferenceResolver,
                journalReferenceMappingRepository
        );

        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Supplier supplier = new Supplier();
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 601L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.requireSupplier(company, 55L)).thenReturn(supplier);

        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", 701L);
        when(companyEntityLookup.requireAccount(company, 701L)).thenReturn(inventory);

        String baseReference = "RMP-NEW-INV";
        String generatedReference = baseReference + "-0004";
        when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-NEW"))
                .thenReturn(baseReference);
        when(journalReferenceResolver.findExistingEntry(company, baseReference))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, baseReference + "-"))
                .thenReturn(List.of());
        when(referenceNumberService.purchaseReference(company, supplier, "INV-NEW"))
                .thenReturn(generatedReference);
        when(journalEntryRepository.findByCompanyAndReferenceNumber(company, generatedReference))
                .thenReturn(Optional.empty());
        when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(company, baseReference))
                .thenReturn(Optional.of(mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping.class)));

        JournalEntryDto created = new JournalEntryDto(
                456L,
                null,
                generatedReference,
                LocalDate.of(2026, 2, 21),
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
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingService.createJournalEntry(org.mockito.ArgumentMatchers.any()))
                .thenReturn(created);

        JournalEntry saved = new JournalEntry();
        ReflectionTestUtils.setField(saved, "id", 456L);
        saved.setReferenceNumber(generatedReference);
        when(companyEntityLookup.requireJournalEntry(company, 456L)).thenReturn(saved);

        assertThat(facade.postPurchaseJournal(
                55L,
                "INV-NEW",
                LocalDate.of(2026, 2, 21),
                "fresh post",
                Map.of(701L, new BigDecimal("180.00")),
                null,
                new BigDecimal("180.00"),
                null
        ).referenceNumber()).isEqualTo(generatedReference);
    }

    @Test
    void purchaseReferenceHelpers_enforceCanonicalNumericSuffixContract() {
        AccountingFacade facade = new AccountingFacade(
                mock(CompanyContextService.class),
                mock(AccountRepository.class),
                mock(AccountingService.class),
                mock(JournalEntryRepository.class),
                mock(ReferenceNumberService.class),
                mock(DealerRepository.class),
                mock(SupplierRepository.class),
                mock(CompanyClock.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyAccountingSettingsService.class),
                mock(JournalReferenceResolver.class),
                mock(JournalReferenceMappingRepository.class)
        );

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", "RMP-SUP-INV100", "RMP-SUP-INV100-0001"))
                .isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", "RMP-SUP-INV100", "RMP-SUP-INV100-0001-REV"))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", "RMP-SUP-INV100", "RMP-SUP-INV100"))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", "RMP-SUP-INV100", "RMP-SUP-INV100-"))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", "RMP-SUP-INV100", " "))
                .isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                facade, "isCanonicalPurchaseReference", " ", "RMP-SUP-INV100-0001"))
                .isFalse();

        assertThat((Long) ReflectionTestUtils.invokeMethod(
                facade, "purchaseReferenceSequence", "RMP-SUP-INV100", "RMP-SUP-INV100-0002"))
                .isEqualTo(2L);
        assertThat((Long) ReflectionTestUtils.invokeMethod(
                facade, "purchaseReferenceSequence", "RMP-SUP-INV100", "RMP-SUP-INV100-REV"))
                .isEqualTo(Long.MAX_VALUE);

        assertThat((Optional<?>) ReflectionTestUtils.invokeMethod(
                facade, "findLegacyPurchaseCanonicalEntry", new Company(), " "))
                .isEmpty();
    }
}
