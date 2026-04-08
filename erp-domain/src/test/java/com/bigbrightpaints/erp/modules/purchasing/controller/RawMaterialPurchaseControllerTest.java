package com.bigbrightpaints.erp.modules.purchasing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;

class RawMaterialPurchaseControllerTest {

  @Test
  @DisplayName("createPurchase forwards canonical Idempotency-Key to purchasing service")
  void createPurchase_forwardsCanonicalIdempotencyHeader() {
    PurchasingService purchasingService = mock(PurchasingService.class);
    RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
    RawMaterialPurchaseRequest request = purchaseRequest();
    when(purchasingService.createPurchase(request, "purchase-idem-001")).thenReturn(null);

    controller.createPurchase("purchase-idem-001", null, request);

    verify(purchasingService).createPurchase(request, "purchase-idem-001");
  }

  @Test
  @DisplayName("createPurchase rejects X-Idempotency-Key legacy header")
  void createPurchase_rejectsLegacyIdempotencyHeader() {
    PurchasingService purchasingService = mock(PurchasingService.class);
    RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);

    assertThatThrownBy(() -> controller.createPurchase(null, "legacy-001", purchaseRequest()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("X-Idempotency-Key is not supported for purchase invoices");
    verify(purchasingService, never())
        .createPurchase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("recordPurchaseReturn forwards canonical Idempotency-Key to purchasing service")
  void recordPurchaseReturn_forwardsCanonicalIdempotencyHeader() {
    PurchasingService purchasingService = mock(PurchasingService.class);
    RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
    PurchaseReturnRequest request = returnRequest();
    when(purchasingService.recordPurchaseReturn(request, "return-idem-001")).thenReturn(null);

    controller.recordPurchaseReturn("return-idem-001", null, request);

    verify(purchasingService).recordPurchaseReturn(request, "return-idem-001");
  }

  @Test
  @DisplayName("recordPurchaseReturn rejects X-Idempotency-Key legacy header")
  void recordPurchaseReturn_rejectsLegacyIdempotencyHeader() {
    PurchasingService purchasingService = mock(PurchasingService.class);
    RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);

    assertThatThrownBy(() -> controller.recordPurchaseReturn(null, "legacy-001", returnRequest()))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("X-Idempotency-Key is not supported for purchase returns");
    verify(purchasingService, never())
        .recordPurchaseReturn(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void previewPurchaseReturn_wrapsServiceResult() {
    PurchasingService purchasingService = mock(PurchasingService.class);
    RawMaterialPurchaseController controller = new RawMaterialPurchaseController(purchasingService);
    PurchaseReturnRequest request = returnRequest();
    PurchaseReturnPreviewDto preview =
        new PurchaseReturnPreviewDto(
            20L,
            "PINV-20",
            30L,
            "Titanium White",
            BigDecimal.ONE,
            new BigDecimal("4.00"),
            new BigDecimal("5.00"),
            BigDecimal.ZERO,
            new BigDecimal("5.00"),
            LocalDate.of(2026, 3, 12),
            "PRN-1");
    when(purchasingService.previewPurchaseReturn(request)).thenReturn(preview);

    var response = controller.previewPurchaseReturn(request);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().message()).isEqualTo("Purchase return preview");
    assertThat(response.getBody().data()).isEqualTo(preview);
    verify(purchasingService).previewPurchaseReturn(request);
  }

  private RawMaterialPurchaseRequest purchaseRequest() {
    return new RawMaterialPurchaseRequest(
        10L,
        "INV-001",
        LocalDate.of(2026, 3, 12),
        "memo",
        11L,
        22L,
        BigDecimal.ZERO,
        List.of(
            new RawMaterialPurchaseLineRequest(
                30L, "BATCH-1", BigDecimal.ONE, "KG", new BigDecimal("5.00"), null, null, "line")));
  }

  private PurchaseReturnRequest returnRequest() {
    return new PurchaseReturnRequest(
        10L,
        20L,
        30L,
        BigDecimal.ONE,
        new BigDecimal("5.00"),
        "PRN-1",
        LocalDate.of(2026, 3, 12),
        "Damaged");
  }
}
