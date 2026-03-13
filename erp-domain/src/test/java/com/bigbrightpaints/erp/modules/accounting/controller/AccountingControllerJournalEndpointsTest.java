package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class AccountingControllerJournalEndpointsTest {

    @Test
    void createManualJournal_delegatesToAccountingService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        ManualJournalRequest request = new ManualJournalRequest(
                LocalDate.of(2026, 2, 28),
                "Manual adjustment",
                "manual-100",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("50.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("50.00"), "Credit", ManualJournalRequest.EntryType.CREDIT)
                )
        );
        JournalEntryDto expected = new JournalEntryDto(
                100L,
                null,
                "JRN-100",
                LocalDate.of(2026, 2, 28),
                "Manual adjustment",
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
        when(accountingService.createManualJournal(request)).thenReturn(expected);

        ApiResponse<JournalEntryDto> body = controller.createManualJournal(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isEqualTo(expected);
    }

    @Test
    void listJournals_appliesFilterArguments() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        List<JournalListItemDto> expected = List.of(
                new JournalListItemDto(
                        10L,
                        "INV-10",
                        LocalDate.of(2026, 2, 27),
                        "Dispatch",
                        "POSTED",
                        "AUTOMATED",
                        "SALES",
                        "INV-10",
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00")
                )
        );
        when(accountingService.listJournals(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                "AUTOMATED",
                "SALES"
        )).thenReturn(expected);

        ApiResponse<List<JournalListItemDto>> body = controller.listJournals(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                "AUTOMATED",
                "SALES"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).containsExactlyElementsOf(expected);
    }

    @Test
    void reverseJournalEntryByJournalPath_delegatesToService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                LocalDate.of(2026, 2, 28),
                false,
                "Correction",
                "Reversal",
                false
        );
        JournalEntryDto expected = new JournalEntryDto(
                200L,
                null,
                "REV-200",
                LocalDate.of(2026, 2, 28),
                "Reversal",
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
        when(accountingService.reverseJournalEntry(200L, request)).thenReturn(expected);

        ApiResponse<JournalEntryDto> body = controller.reverseJournalEntryByJournalPath(200L, request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(expected);
    }

    @Test
    void listSalesReturns_usesSalesReturnReferencePrefix() {
        AccountingService accountingService = mock(AccountingService.class);
        JournalEntryService journalEntryService = mock(JournalEntryService.class);
        AccountingController controller = newController(accountingService, journalEntryService, null);

        controller.listSalesReturns();

        verify(journalEntryService).listJournalEntriesByReferencePrefix("CRN-");
    }

    @Test
    void previewSalesReturn_delegatesToSalesReturnService() {
        AccountingService accountingService = mock(AccountingService.class);
        SalesReturnService salesReturnService = mock(SalesReturnService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), salesReturnService);
        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Damaged",
                List.of(new SalesReturnRequest.ReturnLine(20L, new BigDecimal("1.00")))
        );
        SalesReturnPreviewDto preview = new SalesReturnPreviewDto(
                10L,
                "INV-10",
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of()
        );
        when(salesReturnService.previewReturn(request)).thenReturn(preview);

        ApiResponse<SalesReturnPreviewDto> body = controller.previewSalesReturn(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(preview);
    }

    @Test
    void compatibilityConstructorBridge_createJournalEntryDelegatesToAccountingService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = compatibilityController(accountingService);
        JournalEntryRequest request = new JournalEntryRequest(
                "BRIDGE-ENTRY-1",
                LocalDate.of(2026, 2, 28),
                "Bridge entry",
                null,
                null,
                Boolean.FALSE,
                List.of(new JournalEntryRequest.JournalLineRequest(11L, "Debit", new BigDecimal("50.00"), BigDecimal.ZERO))
        );
        JournalEntryDto expected = new JournalEntryDto(
                501L,
                null,
                "BRIDGE-ENTRY-1",
                LocalDate.of(2026, 2, 28),
                "Bridge entry",
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
        when(accountingService.createManualJournalEntry(any(JournalEntryRequest.class), eq("BRIDGE-ENTRY-1"))).thenReturn(expected);

        ApiResponse<JournalEntryDto> body = controller.createJournalEntry(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(expected);
        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        verify(accountingService).createManualJournalEntry(requestCaptor.capture(), eq("BRIDGE-ENTRY-1"));
        JournalEntryRequest sanitized = requestCaptor.getValue();
        assertThat(sanitized.referenceNumber()).isNull();
        assertThat(sanitized.entryDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(sanitized.memo()).isEqualTo("Bridge entry");
        assertThat(sanitized.lines()).hasSize(1);
        assertThat(sanitized.attachmentReferences()).isEmpty();
    }

    @Test
    void createJournalEntry_keepsAttachmentReferencesWhileSanitizingManualFields() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        JournalEntryRequest request = new JournalEntryRequest(
                "MANUAL-2026-0001",
                LocalDate.of(2026, 3, 1),
                "Manual close-period note",
                11L,
                22L,
                Boolean.TRUE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(11L, "Debit", new BigDecimal("90.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(22L, "Credit", BigDecimal.ZERO, new BigDecimal("90.00"))
                ),
                "INR",
                new BigDecimal("1.00"),
                "UPSTREAM",
                "SRC-1",
                "AUTOMATED",
                List.of("scan-1", "scan-2")
        );
        when(accountingService.createManualJournalEntry(any(JournalEntryRequest.class), eq("MANUAL-2026-0001")))
                .thenReturn(expectedJournal(611L, "MANUAL-2026-0001"));

        controller.createJournalEntry(request);

        ArgumentCaptor<JournalEntryRequest> requestCaptor = ArgumentCaptor.forClass(JournalEntryRequest.class);
        verify(accountingService).createManualJournalEntry(requestCaptor.capture(), eq("MANUAL-2026-0001"));
        JournalEntryRequest sanitized = requestCaptor.getValue();
        assertThat(sanitized.referenceNumber()).isNull();
        assertThat(sanitized.sourceModule()).isNull();
        assertThat(sanitized.sourceReference()).isNull();
        assertThat(sanitized.journalType()).isNull();
        assertThat(sanitized.attachmentReferences()).containsExactly("scan-1", "scan-2");
    }

    @Test
    void settleDealer_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
        SettlementService settlementService = mock(SettlementService.class);
        AccountingController controller = controllerWithSettlementService(settlementService);
        DealerSettlementRequest request = new DealerSettlementRequest(
                9L,
                20L,
                21L,
                22L,
                23L,
                24L,
                new BigDecimal("180.00"),
                SettlementAllocationApplication.FUTURE_APPLICATION,
                LocalDate.of(2026, 3, 2),
                "HDR-DEALER-1",
                "dealer settlement",
                null,
                Boolean.TRUE,
                null,
                null
        );
        PartnerSettlementResponse expected = new PartnerSettlementResponse(
                expectedJournal(701L, "HDR-DEALER-1"),
                new BigDecimal("180.00"),
                new BigDecimal("180.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        );
        when(settlementService.settleDealerInvoices(any(DealerSettlementRequest.class))).thenReturn(expected);

        controller.settleDealer(request, "IDEMP-DEALER-HDR-1", null);

        ArgumentCaptor<DealerSettlementRequest> requestCaptor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(settlementService).settleDealerInvoices(requestCaptor.capture());
        DealerSettlementRequest resolved = requestCaptor.getValue();
        assertThat(resolved.amount()).isEqualByComparingTo("180.00");
        assertThat(resolved.unappliedAmountApplication()).isEqualTo(SettlementAllocationApplication.FUTURE_APPLICATION);
        assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-DEALER-HDR-1");
    }

    @Test
    void settleSupplier_preservesAmountAndUnappliedApplicationWhenApplyingHeaderIdempotency() {
        SettlementService settlementService = mock(SettlementService.class);
        AccountingController controller = controllerWithSettlementService(settlementService);
        SupplierSettlementRequest request = new SupplierSettlementRequest(
                8L,
                20L,
                21L,
                22L,
                23L,
                24L,
                new BigDecimal("95.00"),
                SettlementAllocationApplication.ON_ACCOUNT,
                LocalDate.of(2026, 3, 3),
                "HDR-SUP-1",
                "supplier settlement",
                null,
                Boolean.TRUE,
                null
        );
        PartnerSettlementResponse expected = new PartnerSettlementResponse(
                expectedJournal(702L, "HDR-SUP-1"),
                new BigDecimal("95.00"),
                new BigDecimal("95.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        );
        when(settlementService.settleSupplierInvoices(any(SupplierSettlementRequest.class))).thenReturn(expected);

        controller.settleSupplier(request, "IDEMP-SUP-HDR-1", null);

        ArgumentCaptor<SupplierSettlementRequest> requestCaptor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(settlementService).settleSupplierInvoices(requestCaptor.capture());
        SupplierSettlementRequest resolved = requestCaptor.getValue();
        assertThat(resolved.amount()).isEqualByComparingTo("95.00");
        assertThat(resolved.unappliedAmountApplication()).isEqualTo(SettlementAllocationApplication.ON_ACCOUNT);
        assertThat(resolved.idempotencyKey()).isEqualTo("IDEMP-SUP-HDR-1");
    }

    @Test
    void accountingFacade_createManualJournal_requiresReason() {
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

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> facade.createManualJournal(new ManualJournalRequest(
                LocalDate.of(2026, 3, 1),
                "   ",
                "MANUAL-EMPTY",
                Boolean.FALSE,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("10.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("10.00"), "Credit", ManualJournalRequest.EntryType.CREDIT)
                ),
                List.of("scan-9")
        )))
                .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .hasMessageContaining("Manual journal reason is required");
    }

    @Test
    void accountingFacade_createManualJournalEntry_forwardsAttachmentReferences() {
        AccountingFacade facade = org.mockito.Mockito.spy(new AccountingFacade(
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
        ));
        ArgumentCaptor<JournalCreationRequest> requestCaptor = ArgumentCaptor.forClass(JournalCreationRequest.class);
        doReturn(expectedJournal(703L, "MANUAL-FACADE-1")).when(facade).createStandardJournal(requestCaptor.capture());

        facade.createManualJournalEntry(
                new JournalEntryRequest(
                        "MANUAL-FACADE-1",
                        LocalDate.of(2026, 3, 4),
                        "manual facade journal",
                        null,
                        null,
                        Boolean.TRUE,
                        List.of(
                                new JournalEntryRequest.JournalLineRequest(11L, "Debit", new BigDecimal("55.00"), BigDecimal.ZERO),
                                new JournalEntryRequest.JournalLineRequest(22L, "Credit", BigDecimal.ZERO, new BigDecimal("55.00"))
                        ),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("scan-a", "scan-b")
                ),
                "IDEMP-FACADE-1"
        );

        assertThat(requestCaptor.getValue().attachmentReferences()).containsExactly("scan-a", "scan-b");
        assertThat(requestCaptor.getValue().narration()).isEqualTo("manual facade journal");
    }

    @Test
    void accountingFacade_createManualJournal_forwardsAttachmentReferencesAndIdempotencyKey() {
        AccountingFacade facade = org.mockito.Mockito.spy(new AccountingFacade(
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
        ));
        ArgumentCaptor<JournalCreationRequest> requestCaptor = ArgumentCaptor.forClass(JournalCreationRequest.class);
        doReturn(expectedJournal(704L, "MANUAL-FACADE-2")).when(facade).createStandardJournal(requestCaptor.capture());

        facade.createManualJournal(new ManualJournalRequest(
                LocalDate.of(2026, 3, 5),
                "manual correction reason",
                "IDEMP-FACADE-2",
                Boolean.TRUE,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("25.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("25.00"), "Credit", ManualJournalRequest.EntryType.CREDIT)
                ),
                List.of("manual-scan-1", "manual-scan-2")
        ));

        assertThat(requestCaptor.getValue().sourceReference()).isEqualTo("IDEMP-FACADE-2");
        assertThat(requestCaptor.getValue().attachmentReferences()).containsExactly("manual-scan-1", "manual-scan-2");
        assertThat(requestCaptor.getValue().narration()).isEqualTo("manual correction reason");
    }

    @Test
    void accountingFacade_createManualJournalEntry_requiresReason() {
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

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> facade.createManualJournalEntry(
                new JournalEntryRequest(
                        "MANUAL-FACADE-MISSING-REASON",
                        LocalDate.of(2026, 3, 6),
                        "   ",
                        null,
                        null,
                        Boolean.TRUE,
                        List.of(
                                new JournalEntryRequest.JournalLineRequest(11L, "Debit", new BigDecimal("30.00"), BigDecimal.ZERO),
                                new JournalEntryRequest.JournalLineRequest(22L, "Credit", BigDecimal.ZERO, new BigDecimal("30.00"))
                        ),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("scan-z")
                ),
                "IDEMP-FACADE-MISSING-REASON"
        ))
                .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .hasMessageContaining("Manual journal reason is required");
    }

    private AccountingController newController(AccountingService accountingService,
                                               JournalEntryService journalEntryService,
                                               SalesReturnService salesReturnService) {
        return new AccountingController(
                accountingService,
                journalEntryService,
                null,
                null,
                null,
                null,
                null,
                null,
                salesReturnService,
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
                null
        );
    }

    private AccountingController compatibilityController(AccountingService accountingService) {
        return new AccountingController(
                accountingService,
                mock(AccountingFacade.class),
                mock(SalesReturnService.class),
                mock(AccountingPeriodService.class),
                mock(ReconciliationService.class),
                mock(StatementService.class),
                mock(TaxService.class),
                mock(TemporalBalanceService.class),
                mock(AccountHierarchyService.class),
                mock(AgingReportService.class),
                mock(CompanyDefaultAccountsService.class),
                mock(AccountingAuditTrailService.class),
                null,
                null
        );
    }

    private AccountingController controllerWithSettlementService(SettlementService settlementService) {
        return new AccountingController(
                mock(AccountingService.class),
                mock(JournalEntryService.class),
                null,
                settlementService,
                null,
                null,
                null,
                mock(AccountingFacade.class),
                mock(SalesReturnService.class),
                mock(AccountingPeriodService.class),
                mock(ReconciliationService.class),
                mock(StatementService.class),
                mock(TaxService.class),
                mock(TemporalBalanceService.class),
                mock(AccountHierarchyService.class),
                mock(AgingReportService.class),
                mock(CompanyDefaultAccountsService.class),
                mock(AccountingAuditTrailService.class),
                null,
                null,
                null,
                null
        );
    }

    private JournalEntryDto expectedJournal(Long id, String referenceNumber) {
        return new JournalEntryDto(
                id,
                null,
                referenceNumber,
                LocalDate.of(2026, 2, 28),
                "Settlement",
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
    }
}
