package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
                .satisfies(throwable -> assertReplayConflict(
                        (ApplicationException) throwable,
                        "IDEM-SETTLEMENT-PAYLOAD-MISMATCH",
                        "SUPPLIER",
                        77L));
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
