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
                null,
                null,
                null,
                null,
                null,
                null,
                accountingFacadeProvider
        );
    }

    @Test
    void createStandardJournal_rejectsNonPositiveAmount() {
        JournalCreationRequest request = new JournalCreationRequest(
                BigDecimal.ZERO,
                11L,
                22L,
                "Dispatch journal",
                "SALES",
                "INV-100",
                null,
                null,
                LocalDate.of(2026, 2, 28),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> accountingService.createStandardJournal(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void createStandardJournal_mapsToCreateJournalEntryWithAutomatedMetadata() {
        AccountingService serviceSpy = spy(accountingService);
        JournalEntryDto expected = new JournalEntryDto(
                101L,
                null,
                "INV-101",
                LocalDate.of(2026, 2, 28),
                "Dispatch",
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
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(expected).when(serviceSpy).createJournalEntry(requestCaptor.capture());

        JournalCreationRequest request = new JournalCreationRequest(
                new BigDecimal("100.00"),
                11L,
                22L,
                "Dispatch",
                "SALES",
                "INV-101",
                null,
                List.of(
                        new JournalCreationRequest.LineRequest(11L, new BigDecimal("100.00"), BigDecimal.ZERO, "AR"),
                        new JournalCreationRequest.LineRequest(22L, BigDecimal.ZERO, new BigDecimal("100.00"), "Revenue")
                ),
                LocalDate.of(2026, 2, 28),
                55L,
                null,
                false
        );

        JournalEntryDto actual = serviceSpy.createStandardJournal(request);

        assertThat(actual).isSameAs(expected);
        JournalEntryRequest captured = requestCaptor.getValue();
        assertThat(captured.referenceNumber()).isEqualTo("INV-101");
        assertThat(captured.sourceModule()).isEqualTo("SALES");
        assertThat(captured.sourceReference()).isEqualTo("INV-101");
        assertThat(captured.journalType()).isEqualTo("AUTOMATED");
        assertThat(captured.lines()).hasSize(2);
    }

    @Test
    void createStandardJournal_mapsManualSourceToManualJournalType() {
        AccountingService serviceSpy = spy(accountingService);
        JournalEntryDto expected = new JournalEntryDto(
                102L,
                null,
                "MAN-101",
                LocalDate.of(2026, 2, 28),
                "Manual source",
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
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(expected).when(serviceSpy).createJournalEntry(requestCaptor.capture());

        JournalCreationRequest request = new JournalCreationRequest(
                new BigDecimal("100.00"),
                11L,
                22L,
                "Manual source",
                " MANUAL ",
                "MAN-101",
                null,
                List.of(
                        new JournalCreationRequest.LineRequest(11L, new BigDecimal("100.00"), BigDecimal.ZERO, "Debit"),
                        new JournalCreationRequest.LineRequest(22L, BigDecimal.ZERO, new BigDecimal("100.00"), "Credit")
                ),
                LocalDate.of(2026, 2, 28),
                null,
                null,
                false
        );

        JournalEntryDto actual = serviceSpy.createStandardJournal(request);

        assertThat(actual).isSameAs(expected);
        JournalEntryRequest captured = requestCaptor.getValue();
        assertThat(captured.sourceModule()).isEqualTo("MANUAL");
        assertThat(captured.sourceReference()).isEqualTo("MAN-101");
        assertThat(captured.journalType()).isEqualTo(JournalEntryType.MANUAL.name());
    }

    @Test
    void createStandardJournal_defaultsMissingEntryDateFromCompanyClock() {
        AccountingService serviceSpy = spy(accountingService);
        Company company = new Company();
        JournalEntryDto expected = new JournalEntryDto(
                103L,
                null,
                "AUTO-103",
                LocalDate.of(2026, 3, 2),
                "Clock fallback",
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
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(expected).when(serviceSpy).createJournalEntry(requestCaptor.capture());
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 2));

        serviceSpy.createStandardJournal(new JournalCreationRequest(
                new BigDecimal("100.00"),
                11L,
                22L,
                "Clock fallback",
                "SALES",
                "INV-103",
                null,
                List.of(
                        new JournalCreationRequest.LineRequest(11L, new BigDecimal("100.00"), BigDecimal.ZERO, "Debit"),
                        new JournalCreationRequest.LineRequest(22L, BigDecimal.ZERO, new BigDecimal("100.00"), "Credit")
                ),
                null,
                null,
                null,
                false
        ));

        assertThat(requestCaptor.getValue().entryDate()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void createStandardJournal_propagatesMissingAccountValidation() {
        AccountingService serviceSpy = spy(accountingService);
        doThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found"))
                .when(serviceSpy).createJournalEntry(any(JournalEntryRequest.class));

        JournalCreationRequest request = new JournalCreationRequest(
                new BigDecimal("100.00"),
                111L,
                222L,
                "Dispatch",
                "SALES",
                "INV-404",
                null,
                null,
                LocalDate.of(2026, 2, 28),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> serviceSpy.createStandardJournal(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Account not found");
    }

    @Test
    void createStandardJournal_propagatesClosedPeriodValidation() {
        AccountingService serviceSpy = spy(accountingService);
        doThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period is closed"))
                .when(serviceSpy).createJournalEntry(any(JournalEntryRequest.class));

        JournalCreationRequest request = new JournalCreationRequest(
                new BigDecimal("100.00"),
                11L,
                22L,
                "Dispatch",
                "SALES",
                "INV-405",
                null,
                null,
                LocalDate.of(2026, 2, 28),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> serviceSpy.createStandardJournal(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Accounting period is closed");
    }

    @Test
    void createManualJournal_rejectsUnbalancedLines() {
        ManualJournalRequest request = new ManualJournalRequest(
                LocalDate.of(2026, 2, 28),
                "Manual correction",
                "manual-001",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("100.00"), "Debit line", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("90.00"), "Credit line", ManualJournalRequest.EntryType.CREDIT)
                )
        );

        assertThatThrownBy(() -> accountingService.createManualJournal(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("must balance");
    }

    @Test
    void createManualJournal_requiresReason() {
        ManualJournalRequest request = new ManualJournalRequest(
                LocalDate.of(2026, 2, 28),
                "   ",
                "manual-no-reason",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("100.00"), "Debit line", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("100.00"), "Credit line", ManualJournalRequest.EntryType.CREDIT)
                )
        );

        assertThatThrownBy(() -> accountingService.createManualJournal(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Manual journal reason is required");
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
    void createManualJournal_defaultsMissingEntryDateFromCompanyClock() {
        AccountingService serviceSpy = spy(accountingService);
        Company company = new Company();
        JournalEntryDto expected = new JournalEntryDto(
                302L,
                null,
                "JRN-302",
                LocalDate.of(2026, 3, 3),
                "Manual clock fallback",
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
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        doReturn(expected).when(serviceSpy).createManualJournalEntry(requestCaptor.capture(), eq("manual-clock"));
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 3));

        serviceSpy.createManualJournal(new ManualJournalRequest(
                null,
                "Manual clock fallback",
                "manual-clock",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("80.00"), "Debit line", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("80.00"), "Credit line", ManualJournalRequest.EntryType.CREDIT)
                )
        ));

        assertThat(requestCaptor.getValue().entryDate()).isEqualTo(LocalDate.of(2026, 3, 3));
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

    @Test
    void listJournals_filtersByDateTypeAndSourceModule() {
        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        JournalEntry manualEntry = new JournalEntry();
        ReflectionTestUtils.setField(manualEntry, "id", 1L);
        manualEntry.setReferenceNumber("JRN-1");
        manualEntry.setEntryDate(LocalDate.of(2026, 2, 20));
        manualEntry.setStatus("POSTED");
        manualEntry.setMemo("Manual");
        manualEntry.setJournalType(JournalEntryType.MANUAL);
        manualEntry.setSourceModule("MANUAL");
        JournalLine mLine1 = new JournalLine();
        mLine1.setDebit(new BigDecimal("50.00"));
        mLine1.setCredit(BigDecimal.ZERO);
        mLine1.setJournalEntry(manualEntry);
        JournalLine mLine2 = new JournalLine();
        mLine2.setDebit(BigDecimal.ZERO);
        mLine2.setCredit(new BigDecimal("50.00"));
        mLine2.setJournalEntry(manualEntry);
        manualEntry.getLines().add(mLine1);
        manualEntry.getLines().add(mLine2);

        JournalEntry automatedEntry = new JournalEntry();
        ReflectionTestUtils.setField(automatedEntry, "id", 2L);
        automatedEntry.setReferenceNumber("INV-2");
        automatedEntry.setEntryDate(LocalDate.of(2026, 2, 22));
        automatedEntry.setStatus("POSTED");
        automatedEntry.setMemo("Sales");
        automatedEntry.setJournalType(JournalEntryType.AUTOMATED);
        automatedEntry.setSourceModule("SALES");
        JournalLine aLine1 = new JournalLine();
        aLine1.setDebit(new BigDecimal("90.00"));
        aLine1.setCredit(BigDecimal.ZERO);
        aLine1.setJournalEntry(automatedEntry);
        JournalLine aLine2 = new JournalLine();
        aLine2.setDebit(BigDecimal.ZERO);
        aLine2.setCredit(new BigDecimal("90.00"));
        aLine2.setJournalEntry(automatedEntry);
        automatedEntry.getLines().add(aLine1);
        automatedEntry.getLines().add(aLine2);

        when(journalEntryRepository.findByCompanyOrderByEntryDateDesc(company))
                .thenReturn(List.of(automatedEntry, manualEntry));

        var result = accountingService.listJournals(
                LocalDate.of(2026, 2, 21),
                LocalDate.of(2026, 2, 28),
                "AUTOMATED",
                "sales"
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().journalType()).isEqualTo("AUTOMATED");
        assertThat(result.getFirst().sourceModule()).isEqualTo("SALES");
    }
}
