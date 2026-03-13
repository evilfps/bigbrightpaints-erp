package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingControllerIdempotencyHeaderParityTest {

    @Mock
    private AccountingService accountingService;

    @Mock
    private DealerReceiptService dealerReceiptService;

    @Mock
    private SettlementService settlementService;

    @Test
    void recordDealerReceipt_appliesLegacyHeaderWhenPrimaryMissing() {
        AccountingController controller = controller();
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(dealerReceiptRequest(null), null, "legacy-001");

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService).recordDealerReceipt(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void recordDealerReceipt_rejectsPrimaryLegacyHeaderMismatch() {
        AccountingController controller = controller();
        assertThatThrownBy(() -> controller.recordDealerReceipt(
                dealerReceiptRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordDealerReceipt_rejectsBodyHeaderMismatch() {
        AccountingController controller = controller();

        assertThatThrownBy(() -> controller.recordDealerReceipt(dealerReceiptRequest("body-001"), "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordDealerReceipt_blankBodyKeyFallsBackToHeader() {
        AccountingController controller = controller();
        when(dealerReceiptService.recordDealerReceipt(any())).thenReturn(null);

        controller.recordDealerReceipt(dealerReceiptRequest("   "), "hdr-blank-001", null);

        ArgumentCaptor<DealerReceiptRequest> captor = ArgumentCaptor.forClass(DealerReceiptRequest.class);
        verify(dealerReceiptService).recordDealerReceipt(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-blank-001");
    }

    @Test
    void recordDealerHybridReceipt_appliesLegacyHeaderWhenPrimaryMissing() {
        AccountingController controller = controller();
        when(dealerReceiptService.recordDealerReceiptSplit(any())).thenReturn(null);

        controller.recordDealerHybridReceipt(dealerReceiptSplitRequest(null), null, "legacy-001");

        ArgumentCaptor<DealerReceiptSplitRequest> captor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(dealerReceiptService).recordDealerReceiptSplit(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void recordDealerHybridReceipt_rejectsPrimaryLegacyHeaderMismatch() {
        AccountingController controller = controller();
        assertThatThrownBy(() -> controller.recordDealerHybridReceipt(
                dealerReceiptSplitRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordDealerHybridReceipt_rejectsBodyHeaderMismatch() {
        AccountingController controller = controller();

        assertThatThrownBy(() -> controller.recordDealerHybridReceipt(
                dealerReceiptSplitRequest("body-001"), "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordDealerHybridReceipt_blankBodyKeyFallsBackToHeader() {
        AccountingController controller = controller();
        when(dealerReceiptService.recordDealerReceiptSplit(any())).thenReturn(null);

        controller.recordDealerHybridReceipt(dealerReceiptSplitRequest("   "), "hdr-blank-002", null);

        ArgumentCaptor<DealerReceiptSplitRequest> captor = ArgumentCaptor.forClass(DealerReceiptSplitRequest.class);
        verify(dealerReceiptService).recordDealerReceiptSplit(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-blank-002");
    }

    @Test
    void recordSupplierPayment_appliesLegacyHeaderWhenPrimaryMissing() {
        AccountingController controller = controller();
        when(settlementService.recordSupplierPayment(any())).thenReturn(null);

        controller.recordSupplierPayment(supplierPaymentRequest(null), null, "legacy-001");

        ArgumentCaptor<SupplierPaymentRequest> captor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(settlementService).recordSupplierPayment(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void recordSupplierPayment_rejectsPrimaryLegacyHeaderMismatch() {
        AccountingController controller = controller();
        assertThatThrownBy(() -> controller.recordSupplierPayment(
                supplierPaymentRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordSupplierPayment_rejectsBodyHeaderMismatch() {
        AccountingController controller = controller();

        assertThatThrownBy(() -> controller.recordSupplierPayment(
                supplierPaymentRequest("body-001"), "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void recordSupplierPayment_blankBodyKeyFallsBackToHeader() {
        AccountingController controller = controller();
        when(settlementService.recordSupplierPayment(any())).thenReturn(null);

        controller.recordSupplierPayment(supplierPaymentRequest("   "), "hdr-blank-003", null);

        ArgumentCaptor<SupplierPaymentRequest> captor = ArgumentCaptor.forClass(SupplierPaymentRequest.class);
        verify(settlementService).recordSupplierPayment(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-blank-003");
    }

    @Test
    void settleSupplier_appliesLegacyHeaderWhenPrimaryMissing() {
        AccountingController controller = controller();
        when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

        controller.settleSupplier(supplierSettlementRequest(null), null, "legacy-001");

        ArgumentCaptor<SupplierSettlementRequest> captor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(settlementService).settleSupplierInvoices(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void settleSupplier_rejectsPrimaryLegacyHeaderMismatch() {
        AccountingController controller = controller();
        assertThatThrownBy(() -> controller.settleSupplier(
                supplierSettlementRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void settleSupplier_rejectsBodyHeaderMismatch() {
        AccountingController controller = controller();

        assertThatThrownBy(() -> controller.settleSupplier(
                supplierSettlementRequest("body-001"), "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void settleSupplier_blankBodyKeyFallsBackToHeader() {
        AccountingController controller = controller();
        when(settlementService.settleSupplierInvoices(any())).thenReturn(null);

        controller.settleSupplier(supplierSettlementRequest("   "), "hdr-blank-004", null);

        ArgumentCaptor<SupplierSettlementRequest> captor = ArgumentCaptor.forClass(SupplierSettlementRequest.class);
        verify(settlementService).settleSupplierInvoices(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-blank-004");
    }

    @Test
    void settleDealer_appliesLegacyHeaderWhenPrimaryMissing() {
        AccountingController controller = controller();
        when(settlementService.settleDealerInvoices(any())).thenReturn(null);

        controller.settleDealer(dealerSettlementRequest(null), null, "legacy-001");

        ArgumentCaptor<DealerSettlementRequest> captor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(settlementService).settleDealerInvoices(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void settleDealer_rejectsPrimaryLegacyHeaderMismatch() {
        AccountingController controller = controller();
        assertThatThrownBy(() -> controller.settleDealer(
                dealerSettlementRequest(null), "hdr-001", "legacy-001"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void settleDealer_rejectsBodyHeaderMismatch() {
        AccountingController controller = controller();

        assertThatThrownBy(() -> controller.settleDealer(
                dealerSettlementRequest("body-001"), "hdr-001", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    @Test
    void settleDealer_blankBodyKeyFallsBackToHeader() {
        AccountingController controller = controller();
        when(settlementService.settleDealerInvoices(any())).thenReturn(null);

        controller.settleDealer(dealerSettlementRequest("   "), "hdr-blank-005", null);

        ArgumentCaptor<DealerSettlementRequest> captor = ArgumentCaptor.forClass(DealerSettlementRequest.class);
        verify(settlementService).settleDealerInvoices(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-blank-005");
    }

    private AccountingController controller() {
        return new AccountingController(
                accountingService,
                null,
                dealerReceiptService,
                settlementService,
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
                null
        );
    }

    private DealerReceiptRequest dealerReceiptRequest(String idempotencyKey) {
        return new DealerReceiptRequest(
                1001L,
                2001L,
                new BigDecimal("100.00"),
                "RCPT-001",
                "memo",
                idempotencyKey,
                allocations()
        );
    }

    private DealerReceiptSplitRequest dealerReceiptSplitRequest(String idempotencyKey) {
        return new DealerReceiptSplitRequest(
                1001L,
                List.of(new DealerReceiptSplitRequest.IncomingLine(2001L, new BigDecimal("100.00"))),
                "RCPT-SPLIT-001",
                "memo",
                idempotencyKey
        );
    }

    private DealerSettlementRequest dealerSettlementRequest(String idempotencyKey) {
        return new DealerSettlementRequest(
                1001L,
                2001L,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 2, 15),
                "SETTLE-DEALER-001",
                "memo",
                idempotencyKey,
                Boolean.FALSE,
                allocations(),
                null
        );
    }

    private SupplierPaymentRequest supplierPaymentRequest(String idempotencyKey) {
        return new SupplierPaymentRequest(
                3001L,
                2001L,
                new BigDecimal("75.00"),
                "PAY-001",
                "memo",
                idempotencyKey,
                allocations()
        );
    }

    private SupplierSettlementRequest supplierSettlementRequest(String idempotencyKey) {
        return new SupplierSettlementRequest(
                3001L,
                2001L,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 2, 15),
                "SETTLE-001",
                "memo",
                idempotencyKey,
                Boolean.FALSE,
                allocations()
        );
    }

    private List<SettlementAllocationRequest> allocations() {
        return List.of(new SettlementAllocationRequest(
                4001L,
                null,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "allocation"
        ));
    }
}
