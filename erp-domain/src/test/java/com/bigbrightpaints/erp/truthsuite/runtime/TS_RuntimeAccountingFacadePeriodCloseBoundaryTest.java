package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
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
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingFacadePeriodCloseBoundaryTest {

  @Test
  void reverseClosingEntryForPeriodReopen_delegatesToAccountingService() {
    AccountingService accountingService = mock(AccountingService.class);
    CompanyScopedSalesLookupService salesLookupService =
        mock(CompanyScopedSalesLookupService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            mock(CompanyContextService.class),
            mock(AccountRepository.class),
            accountingService,
            mock(JournalEntryRepository.class),
            mock(ReferenceNumberService.class),
            mock(DealerRepository.class),
            mock(SupplierRepository.class),
            mock(CompanyClock.class),
            salesLookupService,
            accountingLookupService,
            mock(CompanyAccountingSettingsService.class),
            mock(JournalReferenceResolver.class),
            mock(JournalReferenceMappingRepository.class));

    JournalEntry entry = new JournalEntry();
    AccountingPeriod period = new AccountingPeriod();
    facade.reverseClosingEntryForPeriodReopen(entry, period, "reopen for correction");

    boolean delegated =
        mockingDetails(accountingService).getInvocations().stream()
            .anyMatch(
                invocation ->
                    "reverseClosingEntryForPeriodReopen".equals(invocation.getMethod().getName())
                        && invocation.getArguments().length == 3
                        && invocation.getArguments()[0] == entry
                        && invocation.getArguments()[1] == period
                        && "reopen for correction".equals(invocation.getArguments()[2]));

    assertThat(delegated).isTrue();
  }

  @Test
  void postPurchaseJournal_ignoresLegacyPrefixedEntriesWithoutBaseReplay() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AccountingService accountingService = mock(AccountingService.class);
    JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
    ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
    CompanyScopedSalesLookupService salesLookupService =
        mock(CompanyScopedSalesLookupService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);
    JournalReferenceMappingRepository journalReferenceMappingRepository =
        mock(JournalReferenceMappingRepository.class);
    SupplierRepository supplierRepository = mock(SupplierRepository.class);

    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            companyContextService,
            mock(AccountRepository.class),
            accountingService,
            journalEntryRepository,
            referenceNumberService,
            mock(DealerRepository.class),
            supplierRepository,
            mock(CompanyClock.class),
            salesLookupService,
            accountingLookupService,
            mock(CompanyAccountingSettingsService.class),
            journalReferenceResolver,
            journalReferenceMappingRepository);

    Company company = new Company();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 301L);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(company, 88L))
        .thenReturn(Optional.of(supplier));

    Account inventory = new Account();
    ReflectionTestUtils.setField(inventory, "id", 201L);
    when(accountingLookupService.requireAccount(company, 201L)).thenReturn(inventory);

    String baseReference = "RMP-SUP-INV100";
    String generatedReference = baseReference + "-0003";
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-100"))
        .thenReturn(baseReference);
    when(journalReferenceResolver.findExistingEntry(company, baseReference))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(company, supplier, "INV-100"))
        .thenReturn(generatedReference);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, generatedReference))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, baseReference))
        .thenReturn(Optional.empty());
    when(accountingService.createStandardJournal(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new JournalEntryDto(
                501L,
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
                null));
    JournalEntry saved = new JournalEntry();
    ReflectionTestUtils.setField(saved, "id", 501L);
    saved.setReferenceNumber(generatedReference);
    when(accountingLookupService.requireJournalEntry(company, 501L)).thenReturn(saved);

    assertThat(
            facade
                .postPurchaseJournal(
                    88L,
                    "INV-100",
                    LocalDate.of(2026, 2, 21),
                    "legacy replay",
                    Map.of(201L, new BigDecimal("100.00")),
                    null,
                    new BigDecimal("100.00"),
                    null)
                .referenceNumber())
        .isEqualTo(generatedReference);

    verify(journalEntryRepository, never())
        .findByCompanyAndReferenceNumberStartingWith(company, baseReference + "-");
  }

  @Test
  void postPurchaseJournal_existingBaseReference_shortCircuitsEvenWhenSupplierSuspended() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AccountingService accountingService = mock(AccountingService.class);
    JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
    ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
    CompanyScopedSalesLookupService salesLookupService =
        mock(CompanyScopedSalesLookupService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);
    SupplierRepository supplierRepository = mock(SupplierRepository.class);

    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            companyContextService,
            mock(AccountRepository.class),
            accountingService,
            journalEntryRepository,
            referenceNumberService,
            mock(DealerRepository.class),
            supplierRepository,
            mock(CompanyClock.class),
            salesLookupService,
            accountingLookupService,
            mock(CompanyAccountingSettingsService.class),
            journalReferenceResolver,
            mock(JournalReferenceMappingRepository.class));

    Company company = new Company();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.SUSPENDED);
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 401L);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(company, 99L))
        .thenReturn(Optional.of(supplier));

    Account inventory = new Account();
    ReflectionTestUtils.setField(inventory, "id", 501L);
    when(accountingLookupService.requireAccount(company, 501L)).thenReturn(inventory);

    String baseReference = "RMP-LEGACY-REF";
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-777"))
        .thenReturn(baseReference);

    JournalEntry existing = new JournalEntry();
    ReflectionTestUtils.setField(existing, "id", 777L);
    existing.setReferenceNumber(baseReference + "-0003");
    when(journalReferenceResolver.findExistingEntry(company, baseReference))
        .thenReturn(Optional.of(existing));

    assertThat(
            facade
                .postPurchaseJournal(
                    99L,
                    "INV-777",
                    LocalDate.of(2026, 2, 21),
                    "idempotent replay",
                    Map.of(501L, new BigDecimal("220.00")),
                    null,
                    new BigDecimal("220.00"),
                    null)
                .referenceNumber())
        .isEqualTo(baseReference + "-0003");

    verify(journalEntryRepository, never())
        .findByCompanyAndReferenceNumberStartingWith(company, baseReference + "-");
    verify(accountingService, never()).createJournalEntry(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void postPurchaseJournal_withoutCanonicalLegacyEntry_createsNewJournal() {
    CompanyContextService companyContextService = mock(CompanyContextService.class);
    AccountingService accountingService = mock(AccountingService.class);
    JournalEntryRepository journalEntryRepository = mock(JournalEntryRepository.class);
    ReferenceNumberService referenceNumberService = mock(ReferenceNumberService.class);
    CompanyScopedSalesLookupService salesLookupService =
        mock(CompanyScopedSalesLookupService.class);
    CompanyScopedAccountingLookupService accountingLookupService =
        mock(CompanyScopedAccountingLookupService.class);
    JournalReferenceResolver journalReferenceResolver = mock(JournalReferenceResolver.class);
    JournalReferenceMappingRepository journalReferenceMappingRepository =
        mock(JournalReferenceMappingRepository.class);
    SupplierRepository supplierRepository = mock(SupplierRepository.class);

    AccountingFacade facade =
        com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeTestFactory.create(
            companyContextService,
            mock(AccountRepository.class),
            accountingService,
            journalEntryRepository,
            referenceNumberService,
            mock(DealerRepository.class),
            supplierRepository,
            mock(CompanyClock.class),
            salesLookupService,
            accountingLookupService,
            mock(CompanyAccountingSettingsService.class),
            journalReferenceResolver,
            journalReferenceMappingRepository);

    Company company = new Company();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    Supplier supplier = new Supplier();
    supplier.setStatus(SupplierStatus.ACTIVE);
    Account payable = new Account();
    ReflectionTestUtils.setField(payable, "id", 601L);
    supplier.setPayableAccount(payable);
    when(supplierRepository.findByCompanyAndIdWithPayableAccount(company, 55L))
        .thenReturn(Optional.of(supplier));

    Account inventory = new Account();
    ReflectionTestUtils.setField(inventory, "id", 701L);
    when(accountingLookupService.requireAccount(company, 701L)).thenReturn(inventory);

    String baseReference = "RMP-NEW-INV";
    String generatedReference = baseReference + "-0004";
    when(referenceNumberService.purchaseReferenceKey(company, supplier, "INV-NEW"))
        .thenReturn(baseReference);
    when(journalReferenceResolver.findExistingEntry(company, baseReference))
        .thenReturn(Optional.empty());
    when(referenceNumberService.purchaseReference(company, supplier, "INV-NEW"))
        .thenReturn(generatedReference);
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, generatedReference))
        .thenReturn(Optional.empty());
    when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, baseReference))
        .thenReturn(
            Optional.of(
                mock(
                    com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping
                        .class)));

    JournalEntryDto created =
        new JournalEntryDto(
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
            null);
    when(accountingService.createStandardJournal(org.mockito.ArgumentMatchers.any()))
        .thenReturn(created);

    JournalEntry saved = new JournalEntry();
    ReflectionTestUtils.setField(saved, "id", 456L);
    saved.setReferenceNumber(generatedReference);
    when(accountingLookupService.requireJournalEntry(company, 456L)).thenReturn(saved);

    assertThat(
            facade
                .postPurchaseJournal(
                    55L,
                    "INV-NEW",
                    LocalDate.of(2026, 2, 21),
                    "fresh post",
                    Map.of(701L, new BigDecimal("180.00")),
                    null,
                    new BigDecimal("180.00"),
                    null)
                .referenceNumber())
        .isEqualTo(generatedReference);
  }
}
