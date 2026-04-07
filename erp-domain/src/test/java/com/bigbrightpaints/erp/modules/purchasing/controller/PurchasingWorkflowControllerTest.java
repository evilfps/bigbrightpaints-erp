package com.bigbrightpaints.erp.modules.purchasing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;

@ExtendWith(MockitoExtension.class)
class PurchasingWorkflowControllerTest {

  @Mock private PurchasingService purchasingService;

  @Test
  void createGoodsReceipt_rejectsWhenRequestMissing() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

    assertThatThrownBy(() -> controller.createGoodsReceipt("hdr-001", null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Goods receipt request is required");
  }

  @Test
  void createGoodsReceipt_rejectsWhenIdempotencyMissing() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

    assertThatThrownBy(() -> controller.createGoodsReceipt(null, requestWithoutIdempotencyKey()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency-Key header is required");
  }

  @Test
  void createGoodsReceipt_appliesPrimaryHeaderWhenBodyMissing() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);
    when(purchasingService.createGoodsReceipt(any())).thenReturn(null);

    controller.createGoodsReceipt("hdr-001", requestWithoutIdempotencyKey());

    ArgumentCaptor<GoodsReceiptRequest> captor = ArgumentCaptor.forClass(GoodsReceiptRequest.class);
    verify(purchasingService).createGoodsReceipt(captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("hdr-001");
  }

  @Test
  void createGoodsReceipt_rejectsHeaderBodyMismatch() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);

    assertThatThrownBy(
            () -> controller.createGoodsReceipt("hdr-001", requestWithIdempotencyKey("body-001")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key mismatch");
  }

  @Test
  void voidPurchaseOrder_delegatesToService() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);
    PurchaseOrderVoidRequest request =
        new PurchaseOrderVoidRequest("SUPPLIER_CANCELLED", "duplicate");

    controller.voidPurchaseOrder(42L, request);

    verify(purchasingService).voidPurchaseOrder(eq(42L), eq(request));
  }

  @Test
  void createPurchaseOrder_returnsCreatedStatus() {
    PurchasingWorkflowController controller = new PurchasingWorkflowController(purchasingService);
    PurchaseOrderRequest request =
        new PurchaseOrderRequest(
            101L,
            "PO-001",
            LocalDate.of(2026, 2, 15),
            "memo",
            List.of(
                new PurchaseOrderLineRequest(
                    201L, new BigDecimal("10.00"), "kg", new BigDecimal("25.00"), "notes")));
    PurchaseOrderResponse payload =
        new PurchaseOrderResponse(
            1L,
            UUID.randomUUID(),
            "PO-001",
            LocalDate.of(2026, 2, 15),
            new BigDecimal("250.00"),
            "DRAFT",
            "memo",
            101L,
            "SUP-101",
            "Supplier",
            java.time.Instant.parse("2026-02-15T10:00:00Z"),
            List.of());
    when(purchasingService.createPurchaseOrder(request)).thenReturn(payload);

    var response = controller.createPurchaseOrder(request);

    verify(purchasingService).createPurchaseOrder(request);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(payload);
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
        List.of(
            new GoodsReceiptLineRequest(
                201L, "BATCH-1", new BigDecimal("10.00"), "kg", new BigDecimal("25.00"), "notes")));
  }
}
