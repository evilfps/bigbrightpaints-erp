package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeAccountingFacadeExecutableCoverageTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private AccountRepository accountRepository;
    @Mock private AccountingService accountingService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private ReferenceNumberService referenceNumberService;
    @Mock private DealerRepository dealerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CompanyClock companyClock;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock private JournalReferenceResolver journalReferenceResolver;
    @Mock private JournalReferenceMappingRepository journalReferenceMappingRepository;

    private AccountingFacade accountingFacade;
    private Company company;

    @BeforeEach
    void setUp() {
        accountingFacade = new AccountingFacade(
                companyContextService,
                accountRepository,
                accountingService,
                journalEntryRepository,
                referenceNumberService,
                dealerRepository,
                supplierRepository,
                companyClock,
                companyEntityLookup,
                companyAccountingSettingsService,
                journalReferenceResolver,
                journalReferenceMappingRepository
        );
        company = new Company();
        company.setBaseCurrency("INR");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void postPurchaseJournal_legacyPrefixFallback_ignoresReversalSuffixes() {
        Long supplierId = 88L;
        Supplier supplier = new Supplier();
        supplier.setStatus(SupplierStatus.ACTIVE);
        Account payable = new Account();
        payable.setCode("AP");
        payable.setName("Accounts Payable");
        ReflectionTestUtils.setField(payable, "id", 301L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.requireSupplier(eq(company), eq(supplierId))).thenReturn(supplier);

        Long inventoryAccountId = 201L;
        Account inventory = new Account();
        ReflectionTestUtils.setField(inventory, "id", inventoryAccountId);
        when(companyEntityLookup.requireAccount(eq(company), eq(inventoryAccountId))).thenReturn(inventory);

        String baseReference = "RMP-ACME-SUP-INV100";
        when(referenceNumberService.purchaseReferenceKey(eq(company), eq(supplier), eq("INV-100"))).thenReturn(baseReference);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(baseReference))).thenReturn(Optional.empty());

        JournalEntry reversal = new JournalEntry();
        ReflectionTestUtils.setField(reversal, "id", 501L);
        reversal.setReferenceNumber(baseReference + "-0001-REV");

        JournalEntry canonical = new JournalEntry();
        ReflectionTestUtils.setField(canonical, "id", 500L);
        canonical.setReferenceNumber(baseReference + "-0001");
        canonical.setEntryDate(LocalDate.of(2026, 1, 13));

        JournalEntry later = new JournalEntry();
        ReflectionTestUtils.setField(later, "id", 499L);
        later.setReferenceNumber(baseReference + "-0002");
        later.setEntryDate(LocalDate.of(2026, 1, 10));

        when(journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(eq(company), eq(baseReference + "-")))
                .thenReturn(List.of(reversal, later, canonical));
        when(journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(eq(company), eq(baseReference)))
                .thenReturn(Optional.empty());

        JournalEntryDto dto = accountingFacade.postPurchaseJournal(
                supplierId,
                "INV-100",
                LocalDate.of(2026, 1, 10),
                "legacy replay",
                Map.of(inventoryAccountId, new BigDecimal("100.00")),
                null,
                new BigDecimal("100.00"),
                null
        );

        assertThat(dto.referenceNumber()).isEqualTo(baseReference + "-0001");
        verify(accountingService, never()).createJournalEntry(any());
    }

    @Test
    void postSalesJournal_duplicateReplayUsesCanonicalReference() {
        Long dealerId = 77L;
        Dealer dealer = new Dealer();
        Account receivable = new Account();
        receivable.setCode("AR");
        receivable.setName("Accounts Receivable");
        ReflectionTestUtils.setField(receivable, "id", 701L);
        dealer.setReceivableAccount(receivable);
        when(companyEntityLookup.requireDealer(eq(company), eq(dealerId))).thenReturn(dealer);

        String orderNumber = "SO-1001";
        String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
        JournalEntry existing = new JournalEntry();
        ReflectionTestUtils.setField(existing, "id", 777L);
        existing.setReferenceNumber(canonicalReference);
        existing.setEntryDate(LocalDate.of(2026, 1, 5));
        existing.setStatus("POSTED");
        existing.setDealer(dealer);
        when(journalReferenceResolver.findExistingEntry(eq(company), eq(canonicalReference)))
                .thenReturn(Optional.of(existing));

        JournalEntryDto replay = new JournalEntryDto(
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
                null
        );
        when(accountingService.createStandardJournal(any())).thenReturn(replay);
        when(companyEntityLookup.requireJournalEntry(eq(company), eq(777L))).thenReturn(existing);

        JournalEntryDto dto = accountingFacade.postSalesJournal(
                dealerId,
                orderNumber,
                LocalDate.of(2026, 1, 5),
                "sales replay",
                Map.of(9001L, new BigDecimal("120.00")),
                Map.of(9002L, new BigDecimal("21.60")),
                new BigDecimal("141.60"),
                null
        );

        assertThat(dto.referenceNumber()).isEqualTo(canonicalReference);
        verify(accountingService).createStandardJournal(any());
    }
}
