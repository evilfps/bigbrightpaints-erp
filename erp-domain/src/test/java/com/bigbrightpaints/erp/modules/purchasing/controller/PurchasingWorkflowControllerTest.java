package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
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
class PurchasingWorkflowControllerTest {

    @Mock
    private PurchasingService purchasingService;

    @Test
    void createGoodsReceipt_rejectsWhenIdempotencyMissing() {
        PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

        assertThatThrownBy(() -> controller.createGoodsReceipt(null, null, requestWithoutIdempotencyKey()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    void createGoodsReceipt_appliesLegacyHeaderWhenPrimaryMissing() {
        PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);
        when(purchasingService.createGoodsReceipt(any())).thenReturn(null);

        controller.createGoodsReceipt(null, "legacy-001", requestWithoutIdempotencyKey());

        ArgumentCaptor<GoodsReceiptRequest> captor = ArgumentCaptor.forClass(GoodsReceiptRequest.class);
        verify(purchasingService).createGoodsReceipt(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("legacy-001");
    }

    @Test
    void createGoodsReceipt_rejectsPrimaryLegacyHeaderMismatch() {
        PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

        assertThatThrownBy(() -> controller.createGoodsReceipt("hdr-001", "legacy-001", requestWithoutIdempotencyKey()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key header mismatch");
    }

    @Test
    void createGoodsReceipt_rejectsHeaderBodyMismatch() {
        PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

        assertThatThrownBy(() -> controller.createGoodsReceipt("hdr-001", null, requestWithIdempotencyKey("body-001")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key mismatch");
    }

    private GoodsReceiptRequest requestWithoutIdempotencyKey() {
        return requestWithIdempotencyKey(null);
    }

    private GoodsReceiptRequest requestWithIdempotencyKey(String idempotencyKey) {
        return new GoodsReceiptRequest(
                101L,
                "GRN-001",
                LocalDate.of(2026, 2, 15),
                "memo",
                idempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        201L,
                        "BATCH-1",
                        new BigDecimal("10.00"),
                        "kg",
                        new BigDecimal("25.00"),
                        "notes"
                ))
        );
    }
}
