package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingServiceStandardJournalTest {

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
    @Mock private ObjectProvider<AccountingFacade> accountingFacadeProvider;
    @Mock private AccountingFacade accountingFacade;

    private AccountingService accountingService;

    @BeforeEach
    void setUp() {
        accountingService = new AccountingService(
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
                mock(JournalEntryService.class),
                mock(DealerReceiptService.class),
                mock(SettlementService.class),
                mock(CreditDebitNoteService.class),
                mock(AccountingAuditService.class),
                mock(InventoryAccountingService.class),
                accountingFacadeProvider
        );
    }

    @Test
    void createManualJournal_balancedMultiLineDelegatesToFacade() {
        JournalEntryDto expected = new JournalEntryDto(
                301L,
                null,
                "JRN-301",
                LocalDate.of(2026, 2, 28),
                "Manual correction",
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
                null
        );
        ManualJournalRequest request = new ManualJournalRequest(
                LocalDate.of(2026, 2, 28),
                "Manual correction",
                "manual-xyz",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("100.00"), "Debit line", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(12L, new BigDecimal("40.00"), "Debit line 2", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("140.00"), "Credit line", ManualJournalRequest.EntryType.CREDIT)
                )
        );
        when(accountingFacadeProvider.getIfAvailable()).thenReturn(accountingFacade);
        when(accountingFacade.createManualJournal(request)).thenReturn(expected);

        JournalEntryDto actual = accountingService.createManualJournal(request);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void createManualJournalEntry_delegatesToFacade() {
        JournalEntryRequest request = new JournalEntryRequest(
                null,
                LocalDate.of(2026, 2, 28),
                "Manual correction",
                null,
                null,
                false,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(11L, "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(22L, "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))
                ),
                null,
                null,
                null,
                null,
                null
        );
        JournalEntryDto expected = new JournalEntryDto(
                302L,
                null,
                "JRN-302",
                LocalDate.of(2026, 2, 28),
                "Manual correction",
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
                null
        );
        when(accountingFacadeProvider.getIfAvailable()).thenReturn(accountingFacade);
        when(accountingFacade.createManualJournalEntry(request, "manual-xyz")).thenReturn(expected);

        JournalEntryDto actual = accountingService.createManualJournalEntry(request, "manual-xyz");

        assertThat(actual).isSameAs(expected);
    }

}
