package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("critical")
class TS_RuntimeAccountingReplayConflictExecutableCoverageTest {

    @Test
    void validatePartnerJournalReplay_missingEntry_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-MISSING",
                PartnerType.DEALER,
                11L,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-MISSING",
                        "DEALER",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_dealerMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(99L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-DEALER-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertReplayConflict(ex, "IDEM-DEALER-MISMATCH", "DEALER", 11L);
                    assertThat(ex.getMessage()).contains("dealer");
                });
    }

    @Test
    void validatePartnerJournalReplay_supplierMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithSupplier(88L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-SUPPLIER-MISMATCH",
                PartnerType.SUPPLIER,
                77L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertReplayConflict(ex, "IDEM-SUPPLIER-MISMATCH", "SUPPLIER", 77L);
                    assertThat(ex.getMessage()).contains("supplier");
                });
    }

    @Test
    void validatePartnerJournalReplay_unknownPartnerType_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-UNKNOWN-TYPE",
                null,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertReplayConflict(ex, "IDEM-UNKNOWN-TYPE", "null", 11L);
                    assertThat(ex.getMessage()).contains("partner type");
                });
    }

    @Test
    void validatePartnerJournalReplay_memoMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo-a", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-MEMO-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo-b",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-MEMO-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validatePartnerJournalReplay_payloadMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        JournalEntry entry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("40.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "IDEM-PAYLOAD-MISMATCH",
                PartnerType.DEALER,
                11L,
                "memo",
                entry,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-PAYLOAD-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validateSettlementIdempotencyKey_partnerMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<PartnerSettlementAllocation> existing = List.of(
                partnerAllocationForDealer(99L, "100.00", "alloc")
        );
        List<SettlementAllocationRequest> requested = List.of(
                new SettlementAllocationRequest(null, null, new BigDecimal("100.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "alloc")
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validateSettlementIdempotencyKey",
                "IDEM-SETTLEMENT-PARTNER-MISMATCH",
                PartnerType.DEALER,
                11L,
                existing,
                requested))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-SETTLEMENT-PARTNER-MISMATCH",
                        "DEALER",
                        11L));
    }

    @Test
    void validateSettlementIdempotencyKey_payloadMismatch_includesPartnerDetails() {
        AccountingService service = accountingService();
        List<PartnerSettlementAllocation> existing = List.of(
                partnerAllocationForSupplier(77L, "100.00", "alloc")
        );
        List<SettlementAllocationRequest> requested = List.of(
                new SettlementAllocationRequest(null, null, new BigDecimal("120.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "alloc")
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validateSettlementIdempotencyKey",
                "IDEM-SETTLEMENT-PAYLOAD-MISMATCH",
                PartnerType.SUPPLIER,
                77L,
                existing,
                requested))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertReplayConflict(
                            ex,
                            "IDEM-SETTLEMENT-PAYLOAD-MISMATCH",
                            "SUPPLIER",
                            77L);
                    if (ex.getDetails().containsKey("existingAllocationCount")
                            || ex.getDetails().containsKey("requestAllocationCount")) {
                        assertThat(ex.getDetails())
                                .containsEntry("existingAllocationCount", 1)
                                .containsEntry("requestAllocationCount", 1)
                                .containsKey("existingAllocationSignatureDigest")
                                .containsKey("requestAllocationSignatureDigest");
                        assertThat(String.valueOf(ex.getDetails().get("existingAllocationSignatureDigest")))
                                .contains("|applied=100");
                        assertThat(String.valueOf(ex.getDetails().get("requestAllocationSignatureDigest")))
                                .contains("|applied=120");
                    }
                });
    }

    @Test
    void replayConflictDetail_trimsIdempotencyKeyInDetails() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                "  IDEM-TRIM  ",
                PartnerType.DEALER,
                11L,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-TRIM",
                        "DEALER",
                        11L));
    }

    @Test
    void replayConflictDetail_allowsNullIdempotencyKeyAndPartnerId() {
        AccountingService service = accountingService();
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(101L, "memo", new BigDecimal("50.00"), BigDecimal.ZERO)
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "validatePartnerJournalReplay",
                null,
                PartnerType.DEALER,
                null,
                "memo",
                null,
                expectedLines,
                "payload mismatch"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException ex = (ApplicationException) throwable;
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getDetails())
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, null)
                            .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "DEALER")
                            .doesNotContainKey(IntegrationFailureMetadataSchema.KEY_PARTNER_ID);
                });
    }

    @Test
    void isJournalEntryPartnerMismatch_coversAllPartnerTypeBranches() {
        AccountingService service = accountingService();
        JournalEntry dealerEntry = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        JournalEntry supplierEntry = journalEntryWithSupplier(77L, "memo", 101L, "50.00", "0.00");
        JournalEntry emptyEntry = new JournalEntry();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                PartnerType.DEALER,
                12L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                PartnerType.DEALER,
                11L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                supplierEntry,
                PartnerType.SUPPLIER,
                78L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                supplierEntry,
                PartnerType.SUPPLIER,
                77L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                emptyEntry,
                PartnerType.DEALER,
                11L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                emptyEntry,
                PartnerType.SUPPLIER,
                77L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isJournalEntryPartnerMismatch",
                dealerEntry,
                null,
                11L)).isTrue();
    }

    @Test
    void isSettlementAllocationPartnerMismatch_coversAllPartnerTypeBranches() {
        AccountingService service = accountingService();
        PartnerSettlementAllocation dealerAllocation = partnerAllocationForDealer(11L, "100.00", "alloc");
        PartnerSettlementAllocation supplierAllocation = partnerAllocationForSupplier(77L, "100.00", "alloc");
        PartnerSettlementAllocation emptyAllocation = new PartnerSettlementAllocation();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                PartnerType.DEALER,
                12L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                PartnerType.DEALER,
                11L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                supplierAllocation,
                PartnerType.SUPPLIER,
                78L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                supplierAllocation,
                PartnerType.SUPPLIER,
                77L)).isFalse();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                emptyAllocation,
                PartnerType.SUPPLIER,
                77L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                emptyAllocation,
                PartnerType.DEALER,
                11L)).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isSettlementAllocationPartnerMismatch",
                dealerAllocation,
                null,
                11L)).isTrue();
    }

    @Test
    void settlementAllocationSummaryHelper_mapsInvoiceAndPurchaseRows() {
        AccountingService service = accountingService();
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 501L);
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 601L);

        PartnerSettlementAllocation invoiceRow = partnerAllocationForDealer(11L, "80.00", "invoice-row");
        invoiceRow.setInvoice(invoice);
        PartnerSettlementAllocation purchaseRow = partnerAllocationForSupplier(77L, "20.00", "purchase-row");
        purchaseRow.setPurchase(purchase);

        @SuppressWarnings("unchecked")
        List<PartnerSettlementResponse.Allocation> summaries = (List<PartnerSettlementResponse.Allocation>)
                ReflectionTestUtils.invokeMethod(
                        service,
                        "toSettlementAllocationSummaries",
                        List.of(invoiceRow, purchaseRow));

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).invoiceId()).isEqualTo(501L);
        assertThat(summaries.get(0).purchaseId()).isNull();
        assertThat(summaries.get(0).appliedAmount()).isEqualByComparingTo("80.00");
        assertThat(summaries.get(0).memo()).isEqualTo("invoice-row");

        assertThat(summaries.get(1).invoiceId()).isNull();
        assertThat(summaries.get(1).purchaseId()).isEqualTo(601L);
        assertThat(summaries.get(1).appliedAmount()).isEqualByComparingTo("20.00");
        assertThat(summaries.get(1).memo()).isEqualTo("purchase-row");
    }

    @Test
    void logSettlementAuditSuccess_handlesPresentAndMissingOptionalMetadata() {
        AccountingService service = accountingService();
        JournalEntryDto entryDto = new JournalEntryDto(
                9001L,
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

        ReflectionTestUtils.invokeMethod(
                service,
                "logSettlementAuditSuccess",
                PartnerType.DEALER,
                11L,
                entryDto,
                LocalDate.of(2026, 2, 16),
                "idem-settlement-1",
                2,
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("3.00"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                BigDecimal.ZERO
        );

        ReflectionTestUtils.invokeMethod(
                service,
                "logSettlementAuditSuccess",
                PartnerType.SUPPLIER,
                null,
                null,
                null,
                null,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    @Test
    void controllerIdempotencyResolution_blankBodyFallsBackToPrimaryHeaderAcrossEndpoints() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingController controller = accountingController(service);
        when(service.recordDealerReceipt(any())).thenReturn(null);
        when(service.recordDealerReceiptSplit(any())).thenReturn(null);
        when(service.settleDealerInvoices(any())).thenReturn(null);
        when(service.recordSupplierPayment(any())).thenReturn(null);
        when(service.settleSupplierInvoices(any())).thenReturn(null);

        controller.recordDealerReceipt(controllerDealerReceiptRequest("   "), "HDR-DR", null);
        controller.recordDealerHybridReceipt(controllerDealerReceiptSplitRequest("   "), "HDR-DRS", null);
        controller.settleDealer(controllerDealerSettlementRequest("   "), "HDR-ADS", null);
        controller.recordSupplierPayment(controllerSupplierPaymentRequest("   "), "HDR-AP", null);
        controller.settleSupplier(controllerSupplierSettlementRequest("   "), "HDR-APS", null);

        ArgumentCaptor<DealerReceiptRequest> dealerCaptor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(service).recordDealerReceipt(dealerCaptor.capture());
        assertThat(dealerCaptor.getValue().idempotencyKey()).isEqualTo("HDR-DR");

        ArgumentCaptor<DealerReceiptSplitRequest> dealerSplitCaptor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(service).recordDealerReceiptSplit(dealerSplitCaptor.capture());
        assertThat(dealerSplitCaptor.getValue().idempotencyKey()).isEqualTo("HDR-DRS");

        ArgumentCaptor<DealerSettlementRequest> dealerSettlementCaptor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(service).settleDealerInvoices(dealerSettlementCaptor.capture());
        assertThat(dealerSettlementCaptor.getValue().idempotencyKey()).isEqualTo("HDR-ADS");

        ArgumentCaptor<SupplierPaymentRequest> supplierPaymentCaptor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(service).recordSupplierPayment(supplierPaymentCaptor.capture());
        assertThat(supplierPaymentCaptor.getValue().idempotencyKey()).isEqualTo("HDR-AP");

        ArgumentCaptor<SupplierSettlementRequest> supplierSettlementCaptor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(service).settleSupplierInvoices(supplierSettlementCaptor.capture());
        assertThat(supplierSettlementCaptor.getValue().idempotencyKey()).isEqualTo("HDR-APS");
    }

    @Test
    void controllerIdempotencyResolution_bodyKeyWinsAcrossEndpoints() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingController controller = accountingController(service);
        when(service.recordDealerReceipt(any())).thenReturn(null);
        when(service.recordDealerReceiptSplit(any())).thenReturn(null);
        when(service.settleDealerInvoices(any())).thenReturn(null);
        when(service.recordSupplierPayment(any())).thenReturn(null);
        when(service.settleSupplierInvoices(any())).thenReturn(null);

        controller.recordDealerReceipt(controllerDealerReceiptRequest("BODY-DR"), "BODY-DR", null);
        controller.recordDealerHybridReceipt(controllerDealerReceiptSplitRequest("BODY-DRS"), "BODY-DRS", null);
        controller.settleDealer(controllerDealerSettlementRequest("BODY-ADS"), "BODY-ADS", null);
        controller.recordSupplierPayment(controllerSupplierPaymentRequest("BODY-AP"), "BODY-AP", null);
        controller.settleSupplier(controllerSupplierSettlementRequest("BODY-APS"), "BODY-APS", null);

        ArgumentCaptor<DealerReceiptRequest> dealerCaptor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(service).recordDealerReceipt(dealerCaptor.capture());
        assertThat(dealerCaptor.getValue().idempotencyKey()).isEqualTo("BODY-DR");

        ArgumentCaptor<DealerReceiptSplitRequest> dealerSplitCaptor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(service).recordDealerReceiptSplit(dealerSplitCaptor.capture());
        assertThat(dealerSplitCaptor.getValue().idempotencyKey()).isEqualTo("BODY-DRS");

        ArgumentCaptor<DealerSettlementRequest> dealerSettlementCaptor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(service).settleDealerInvoices(dealerSettlementCaptor.capture());
        assertThat(dealerSettlementCaptor.getValue().idempotencyKey()).isEqualTo("BODY-ADS");

        ArgumentCaptor<SupplierPaymentRequest> supplierPaymentCaptor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(service).recordSupplierPayment(supplierPaymentCaptor.capture());
        assertThat(supplierPaymentCaptor.getValue().idempotencyKey()).isEqualTo("BODY-AP");

        ArgumentCaptor<SupplierSettlementRequest> supplierSettlementCaptor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(service).settleSupplierInvoices(supplierSettlementCaptor.capture());
        assertThat(supplierSettlementCaptor.getValue().idempotencyKey()).isEqualTo("BODY-APS");
    }

    @Test
    void controllerIdempotencyResolution_noHeaderKeepsNullAcrossEndpoints() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingController controller = accountingController(service);
        when(service.recordDealerReceipt(any())).thenReturn(null);
        when(service.recordDealerReceiptSplit(any())).thenReturn(null);
        when(service.settleDealerInvoices(any())).thenReturn(null);
        when(service.recordSupplierPayment(any())).thenReturn(null);
        when(service.settleSupplierInvoices(any())).thenReturn(null);

        controller.recordDealerReceipt(controllerDealerReceiptRequest(null), null, null);
        controller.recordDealerHybridReceipt(controllerDealerReceiptSplitRequest(null), null, null);
        controller.settleDealer(controllerDealerSettlementRequest(null), null, null);
        controller.recordSupplierPayment(controllerSupplierPaymentRequest(null), null, null);
        controller.settleSupplier(controllerSupplierSettlementRequest(null), null, null);

        ArgumentCaptor<DealerReceiptRequest> dealerCaptor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(service).recordDealerReceipt(dealerCaptor.capture());
        assertThat(dealerCaptor.getValue().idempotencyKey()).isNull();

        ArgumentCaptor<DealerReceiptSplitRequest> dealerSplitCaptor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(service).recordDealerReceiptSplit(dealerSplitCaptor.capture());
        assertThat(dealerSplitCaptor.getValue().idempotencyKey()).isNull();

        ArgumentCaptor<DealerSettlementRequest> dealerSettlementCaptor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(service).settleDealerInvoices(dealerSettlementCaptor.capture());
        assertThat(dealerSettlementCaptor.getValue().idempotencyKey()).isNull();

        ArgumentCaptor<SupplierPaymentRequest> supplierPaymentCaptor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(service).recordSupplierPayment(supplierPaymentCaptor.capture());
        assertThat(supplierPaymentCaptor.getValue().idempotencyKey()).isNull();

        ArgumentCaptor<SupplierSettlementRequest> supplierSettlementCaptor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(service).settleSupplierInvoices(supplierSettlementCaptor.capture());
        assertThat(supplierSettlementCaptor.getValue().idempotencyKey()).isNull();
    }

    @Test
    void controllerIdempotencyResolution_nullRequestPassesThroughAcrossEndpoints() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingController controller = accountingController(service);
        when(service.recordDealerReceipt(any())).thenReturn(null);
        when(service.recordDealerReceiptSplit(any())).thenReturn(null);
        when(service.settleDealerInvoices(any())).thenReturn(null);
        when(service.recordSupplierPayment(any())).thenReturn(null);
        when(service.settleSupplierInvoices(any())).thenReturn(null);

        controller.recordDealerReceipt(null, "HDR-DR", "LEGACY-DR");
        controller.recordDealerHybridReceipt(null, "HDR-DRS", "LEGACY-DRS");
        controller.settleDealer(null, "HDR-ADS", "LEGACY-ADS");
        controller.recordSupplierPayment(null, "HDR-AP", "LEGACY-AP");
        controller.settleSupplier(null, "HDR-APS", "LEGACY-APS");

        ArgumentCaptor<DealerReceiptRequest> dealerCaptor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(service).recordDealerReceipt(dealerCaptor.capture());
        assertThat(dealerCaptor.getValue()).isNull();

        ArgumentCaptor<DealerReceiptSplitRequest> dealerSplitCaptor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(service).recordDealerReceiptSplit(dealerSplitCaptor.capture());
        assertThat(dealerSplitCaptor.getValue()).isNull();

        ArgumentCaptor<DealerSettlementRequest> dealerSettlementCaptor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(service).settleDealerInvoices(dealerSettlementCaptor.capture());
        assertThat(dealerSettlementCaptor.getValue()).isNull();

        ArgumentCaptor<SupplierPaymentRequest> supplierPaymentCaptor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(service).recordSupplierPayment(supplierPaymentCaptor.capture());
        assertThat(supplierPaymentCaptor.getValue()).isNull();

        ArgumentCaptor<SupplierSettlementRequest> supplierSettlementCaptor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(service).settleSupplierInvoices(supplierSettlementCaptor.capture());
        assertThat(supplierSettlementCaptor.getValue()).isNull();
    }

    @Test
    void controllerTransactionAudit_parsesDateFiltersAndForwardsParameters() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingAuditTrailService auditService = org.mockito.Mockito.mock(AccountingAuditTrailService.class);
        AccountingController controller = accountingController(service, auditService);

        controller.transactionAudit("2026-02-01", "2026-02-10", "AR", "OPEN", "REF-1", 2, 25);

        verify(auditService).listTransactions(
                java.time.LocalDate.of(2026, 2, 1),
                java.time.LocalDate.of(2026, 2, 10),
                "AR",
                "OPEN",
                "REF-1",
                2,
                25);
    }

    @Test
    void controllerTransactionAudit_allowsNullDateFilters() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingAuditTrailService auditService = org.mockito.Mockito.mock(AccountingAuditTrailService.class);
        AccountingController controller = accountingController(service, auditService);

        controller.transactionAudit(null, null, null, null, null, 0, 50);

        verify(auditService).listTransactions(null, null, null, null, null, 0, 50);
    }

    @Test
    void controllerTransactionAuditDetail_forwardsJournalEntryId() {
        AccountingService service = org.mockito.Mockito.mock(AccountingService.class);
        AccountingAuditTrailService auditService = org.mockito.Mockito.mock(AccountingAuditTrailService.class);
        AccountingController controller = accountingController(service, auditService);

        controller.transactionAuditDetail(77L);

        verify(auditService).transactionDetail(77L);
    }

    @Test
    void partnerFieldLabel_and_fingerprint_helper_cover_partner_vocab_branches() {
        AccountingService service = accountingService();

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "partnerFieldLabel", PartnerType.DEALER))
                .isEqualTo("dealerId");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "partnerFieldLabel", PartnerType.SUPPLIER))
                .isEqualTo("supplierId");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "partnerFieldLabel", (PartnerType) null))
                .isEqualTo("partnerId");

        StringBuilder dealerFingerprint = new StringBuilder();
        ReflectionTestUtils.invokeMethod(service, "appendPartnerFingerprint", dealerFingerprint, PartnerType.DEALER, 11L);
        assertThat(dealerFingerprint.toString()).isEqualTo("dealerId=11");

        StringBuilder supplierFingerprint = new StringBuilder();
        ReflectionTestUtils.invokeMethod(service, "appendPartnerFingerprint", supplierFingerprint, PartnerType.SUPPLIER, null);
        assertThat(supplierFingerprint.toString()).isEqualTo("supplierId=null");
    }

    @Test
    void settleSupplierInvoices_existingAllocations_replay_branch_revalidates_supplier_journal_lines() {
        AccountingService service = accountingService();
        CompanyContextService companyContextService =
                (CompanyContextService) ReflectionTestUtils.getField(service, "companyContextService");
        SupplierRepository supplierRepository =
                (SupplierRepository) ReflectionTestUtils.getField(service, "supplierRepository");
        CompanyEntityLookup companyEntityLookup =
                (CompanyEntityLookup) ReflectionTestUtils.getField(service, "companyEntityLookup");
        PartnerSettlementAllocationRepository settlementAllocationRepository =
                (PartnerSettlementAllocationRepository) ReflectionTestUtils.getField(service, "settlementAllocationRepository");
        JournalReferenceMappingRepository journalReferenceMappingRepository =
                (JournalReferenceMappingRepository) ReflectionTestUtils.getField(service, "journalReferenceMappingRepository");
        ReferenceNumberService referenceNumberService =
                (ReferenceNumberService) ReflectionTestUtils.getField(service, "referenceNumberService");

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 44L);

        Supplier supplier = supplier(301L);
        supplier.setName("Supplier Replay");
        Account payableAccount = account(701L);
        payableAccount.setCode("AP-701");
        payableAccount.setName("Accounts Payable");
        payableAccount.setType(AccountType.LIABILITY);
        supplier.setPayableAccount(payableAccount);

        Account cashAccount = account(702L);
        cashAccount.setCode("CASH-702");
        cashAccount.setName("Cash");
        cashAccount.setType(AccountType.ASSET);
        cashAccount.setActive(true);

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 900L);

        JournalEntry replayEntry = new JournalEntry();
        ReflectionTestUtils.setField(replayEntry, "id", 8801L);
        replayEntry.setReferenceNumber("SUP-SET-REF-1");
        replayEntry.setSupplier(supplier);
        replayEntry.setMemo("supplier settle memo");
        replayEntry.getLines().add(journalLine(701L, "100.00", "0.00"));
        replayEntry.getLines().add(journalLine(702L, "0.00", "100.00"));

        PartnerSettlementAllocation existingAllocation = partnerAllocationForSupplier(301L, "100.00", "alloc-1");
        existingAllocation.setPurchase(purchase);
        existingAllocation.setJournalEntry(replayEntry);
        existingAllocation.setIdempotencyKey("idem-supplier-settle");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(supplierRepository.lockByCompanyAndId(company, 301L)).thenReturn(Optional.of(supplier));
        when(companyEntityLookup.requireAccount(company, 702L)).thenReturn(cashAccount);
        when(referenceNumberService.supplierPaymentReference(company, supplier)).thenReturn("SUP-SET-REF-1");
        when(journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(any(), any()))
                .thenReturn(List.of());
        when(journalReferenceMappingRepository.reserveReferenceMapping(any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(company, "idem-supplier-settle"))
                .thenReturn(List.of(), List.of(existingAllocation));
        when(settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, "idem-supplier-settle"))
                .thenReturn(List.of());

        SupplierSettlementRequest request = new SupplierSettlementRequest(
                301L,
                702L,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 2, 16),
                null,
                "supplier settle memo",
                "idem-supplier-settle",
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        900L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "alloc-1"))
        );

        PartnerSettlementResponse response = service.settleSupplierInvoices(request);

        assertThat(response).isNotNull();
        assertThat(response.journalEntry()).isNotNull();
        assertThat(response.journalEntry().id()).isEqualTo(8801L);
        assertThat(response.totalApplied()).isEqualByComparingTo("100.00");
    }

    @Test
    void settlement_helper_branches_capture_partner_mismatch_and_audit_metadata() {
        AccountingService service = accountingService();

        ApplicationException missingAllocation = (ApplicationException) ReflectionTestUtils.invokeMethod(
                service,
                "missingReservedPartnerAllocation",
                "Supplier settlement",
                "IDEM-RESERVED",
                PartnerType.SUPPLIER,
                301L);
        assertThat(missingAllocation.getDetails())
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 301L);

        JournalEntry existing = journalEntryWithDealer(11L, "memo", 101L, "50.00", "0.00");
        existing.setReferenceNumber("JE-DUP-1");
        existing.setEntryDate(LocalDate.of(2026, 2, 16));
        JournalEntry candidate = journalEntryWithDealer(99L, "memo", 101L, "50.00", "0.00");
        candidate.setReferenceNumber("JE-DUP-1");
        candidate.setEntryDate(LocalDate.of(2026, 2, 16));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "ensureDuplicateMatchesExisting",
                existing,
                candidate,
                candidate.getLines()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> assertThat(((ApplicationException) throwable).getDetails())
                        .containsKey("partnerMismatchTypes"));

        JournalEntryDto journalEntryDto = new JournalEntryDto(
                991L,
                null,
                "JE-DUP-1",
                LocalDate.of(2026, 2, 16),
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
                null
        );

        ReflectionTestUtils.invokeMethod(
                service,
                "logSettlementAuditSuccess",
                PartnerType.SUPPLIER,
                301L,
                journalEntryDto,
                LocalDate.of(2026, 2, 16),
                "idem-audit",
                1,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    @Test
    void settlement_fingerprint_builders_cover_receipt_and_supplier_paths() {
        AccountingService service = accountingService();
        Company company = new Company();
        Dealer dealer = dealer(11L);
        dealer.setCode("DLR-11");

        DealerReceiptRequest dealerReceiptRequest = new DealerReceiptRequest(
                11L,
                201L,
                new BigDecimal("100.00"),
                null,
                "memo",
                null,
                List.of(new SettlementAllocationRequest(
                        401L,
                        null,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "alloc"))
        );
        String dealerReference = (String) ReflectionTestUtils.invokeMethod(
                service,
                "buildDealerReceiptReference",
                company,
                dealer,
                dealerReceiptRequest);
        assertThat(dealerReference).startsWith("RCPT-");

        DealerReceiptSplitRequest splitRequest = new DealerReceiptSplitRequest(
                11L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(201L, new BigDecimal("100.00"))),
                null,
                "memo",
                null
        );
        String splitReference = (String) ReflectionTestUtils.invokeMethod(
                service,
                "buildDealerReceiptReference",
                company,
                dealer,
                splitRequest);
        assertThat(splitReference).startsWith("RCPT-");

        SupplierSettlementRequest supplierSettlementRequest = new SupplierSettlementRequest(
                301L,
                201L,
                null,
                null,
                null,
                null,
                null,
                null,
                "memo",
                null,
                Boolean.FALSE,
                List.of(new SettlementAllocationRequest(
                        null,
                        900L,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "alloc"))
        );
        String supplierKey = (String) ReflectionTestUtils.invokeMethod(
                service,
                "buildSupplierSettlementIdempotencyKey",
                supplierSettlementRequest);
        assertThat(supplierKey).startsWith("SUPPLIER-SETTLEMENT-");
    }

    @Test
    void delegatedAccountingAndOrchestratorServiceSuites_pass_in_truth_lane() {
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.modules.accounting.service.AccountingServiceTest");
        assertDelegatedSuitePasses("com.bigbrightpaints.erp.orchestrator.service.CommandDispatcherTest");
    }


    private void assertReplayConflict(ApplicationException ex,
                                      String idempotencyKey,
                                      String partnerType,
                                      Long partnerId) {
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
        assertThat(ex.getDetails())
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
    }

    private AccountingService accountingService() {
        return new AccountingService(
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.company.service.CompanyContextService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService.class),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.util.CompanyClock.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.util.CompanyEntityLookup.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.sales.domain.DealerRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository.class),
                org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.config.SystemSettingsService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.audit.AuditService.class),
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore.class)
        );
    }

    private void assertDelegatedSuitePasses(String className) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(className))
                .build();
        SummaryGeneratingListener summaryListener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summaryListener);
        launcher.execute(request);
        TestExecutionSummary summary = summaryListener.getSummary();
        assertThat(summary.getTestsFoundCount()).isGreaterThan(0L);
        assertThat(summary.getTestsFailedCount()).isZero();
    }

    private JournalEntry journalEntryWithDealer(Long dealerId,
                                                String memo,
                                                Long accountId,
                                                String debit,
                                                String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setDealer(dealer(dealerId));
        entry.setMemo(memo);
        entry.getLines().add(journalLine(accountId, debit, credit));
        return entry;
    }

    private JournalEntry journalEntryWithSupplier(Long supplierId,
                                                  String memo,
                                                  Long accountId,
                                                  String debit,
                                                  String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setSupplier(supplier(supplierId));
        entry.setMemo(memo);
        entry.getLines().add(journalLine(accountId, debit, credit));
        return entry;
    }

    private JournalLine journalLine(Long accountId, String debit, String credit) {
        JournalLine line = new JournalLine();
        line.setAccount(account(accountId));
        line.setDebit(new BigDecimal(debit));
        line.setCredit(new BigDecimal(credit));
        return line;
    }

    private PartnerSettlementAllocation partnerAllocationForDealer(Long dealerId, String appliedAmount, String memo) {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setDealer(dealer(dealerId));
        allocation.setAllocationAmount(new BigDecimal(appliedAmount));
        allocation.setDiscountAmount(BigDecimal.ZERO);
        allocation.setWriteOffAmount(BigDecimal.ZERO);
        allocation.setFxDifferenceAmount(BigDecimal.ZERO);
        allocation.setMemo(memo);
        return allocation;
    }

    private PartnerSettlementAllocation partnerAllocationForSupplier(Long supplierId, String appliedAmount, String memo) {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        allocation.setSupplier(supplier(supplierId));
        allocation.setAllocationAmount(new BigDecimal(appliedAmount));
        allocation.setDiscountAmount(BigDecimal.ZERO);
        allocation.setWriteOffAmount(BigDecimal.ZERO);
        allocation.setFxDifferenceAmount(BigDecimal.ZERO);
        allocation.setMemo(memo);
        return allocation;
    }

    private Account account(Long id) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private Dealer dealer(Long id) {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", id);
        return dealer;
    }

    private Supplier supplier(Long id) {
        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", id);
        supplier.setStatus(SupplierStatus.ACTIVE);
        return supplier;
    }

    private AccountingController accountingController(AccountingService accountingService) {
        return accountingController(accountingService, null);
    }

    private AccountingController accountingController(AccountingService accountingService,
                                                      AccountingAuditTrailService accountingAuditTrailService) {
        return new AccountingController(
                accountingService,
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
                accountingAuditTrailService,
                null,
                null,
                null,
                null
        );
    }

    private DealerReceiptRequest controllerDealerReceiptRequest(String idempotencyKey) {
        return new DealerReceiptRequest(
                101L,
                201L,
                new BigDecimal("100.00"),
                "DR-REF-1",
                "memo",
                idempotencyKey,
                controllerAllocations()
        );
    }

    private DealerReceiptSplitRequest controllerDealerReceiptSplitRequest(String idempotencyKey) {
        return new DealerReceiptSplitRequest(
                101L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(201L, new BigDecimal("100.00"))),
                "DR-SPLIT-REF-1",
                "memo",
                idempotencyKey
        );
    }

    private SupplierPaymentRequest controllerSupplierPaymentRequest(String idempotencyKey) {
        return new SupplierPaymentRequest(
                301L,
                201L,
                new BigDecimal("75.00"),
                "AP-REF-1",
                "memo",
                idempotencyKey,
                controllerAllocations()
        );
    }

    private DealerSettlementRequest controllerDealerSettlementRequest(String idempotencyKey) {
        return new DealerSettlementRequest(
                101L,
                201L,
                null,
                null,
                null,
                null,
                java.time.LocalDate.of(2026, 2, 16),
                "AR-SETTLE-REF-1",
                "memo",
                idempotencyKey,
                Boolean.FALSE,
                controllerAllocations(),
                null
        );
    }

    private SupplierSettlementRequest controllerSupplierSettlementRequest(String idempotencyKey) {
        return new SupplierSettlementRequest(
                301L,
                201L,
                null,
                null,
                null,
                null,
                java.time.LocalDate.of(2026, 2, 16),
                "AP-SETTLE-REF-1",
                "memo",
                idempotencyKey,
                Boolean.FALSE,
                controllerAllocations()
        );
    }

    private List<SettlementAllocationRequest> controllerAllocations() {
        return List.of(new SettlementAllocationRequest(
                401L,
                null,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "allocation"
        ));
    }

}
