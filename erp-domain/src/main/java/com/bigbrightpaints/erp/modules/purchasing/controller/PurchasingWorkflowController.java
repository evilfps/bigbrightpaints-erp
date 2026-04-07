package com.bigbrightpaints.erp.modules.purchasing.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderStatusHistoryResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderVoidRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/purchasing")
public class PurchasingWorkflowController {

  private static final String CANONICAL_GOODS_RECEIPT_PATH = "/api/v1/purchasing/goods-receipts";
  private final PurchasingService purchasingService;

  public PurchasingWorkflowController(PurchasingService purchasingService) {
    this.purchasingService = purchasingService;
  }

  @GetMapping("/purchase-orders")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<PurchaseOrderResponse>>> listPurchaseOrders(
      @RequestParam(required = false) Long supplierId) {
    return ResponseEntity.ok(
        ApiResponse.success("Purchase orders", purchasingService.listPurchaseOrders(supplierId)));
  }

  @GetMapping("/purchase-orders/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseOrderResponse>> getPurchaseOrder(
      @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(purchasingService.getPurchaseOrder(id)));
  }

  @PostMapping("/purchase-orders")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseOrderResponse>> createPurchaseOrder(
      @Valid @RequestBody PurchaseOrderRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(
        ApiResponse.success(
            "Purchase order recorded", purchasingService.createPurchaseOrder(request)));
  }

  @PostMapping("/purchase-orders/{id}/approve")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseOrderResponse>> approvePurchaseOrder(
      @PathVariable Long id) {
    return ResponseEntity.ok(
        ApiResponse.success("Purchase order approved", purchasingService.approvePurchaseOrder(id)));
  }

  @PostMapping("/purchase-orders/{id}/void")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseOrderResponse>> voidPurchaseOrder(
      @PathVariable Long id, @Valid @RequestBody PurchaseOrderVoidRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Purchase order voided", purchasingService.voidPurchaseOrder(id, request)));
  }

  @PostMapping("/purchase-orders/{id}/close")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseOrderResponse>> closePurchaseOrder(
      @PathVariable Long id) {
    return ResponseEntity.ok(
        ApiResponse.success("Purchase order closed", purchasingService.closePurchaseOrder(id)));
  }

  @GetMapping("/purchase-orders/{id}/timeline")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<PurchaseOrderStatusHistoryResponse>>>
      purchaseOrderTimeline(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(purchasingService.getPurchaseOrderTimeline(id)));
  }

  @GetMapping("/goods-receipts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<GoodsReceiptResponse>>> listGoodsReceipts(
      @RequestParam(required = false) Long supplierId) {
    return ResponseEntity.ok(
        ApiResponse.success("Goods receipts", purchasingService.listGoodsReceipts(supplierId)));
  }

  @GetMapping("/goods-receipts/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<GoodsReceiptResponse>> getGoodsReceipt(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(purchasingService.getGoodsReceipt(id)));
  }

  @PostMapping("/goods-receipts")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<GoodsReceiptResponse>> createGoodsReceipt(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody GoodsReceiptRequest request) {
    GoodsReceiptRequest resolved = applyIdempotencyKey(request, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(
        ApiResponse.success(
            "Goods receipt recorded", purchasingService.createGoodsReceipt(resolved)));
  }

  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<GoodsReceiptResponse>> createGoodsReceipt(
      String ignoredCompanyId, String idempotencyKey, GoodsReceiptRequest request) {
    return createGoodsReceipt(idempotencyKey, request);
  }

  private GoodsReceiptRequest applyIdempotencyKey(
      GoodsReceiptRequest request, String idempotencyKeyHeader) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Goods receipt request is required");
    }
    String resolvedKey =
        IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
            request.idempotencyKey(), idempotencyKeyHeader);
    if (StringUtils.hasText(request.idempotencyKey())) {
      return request;
    }
    if (!StringUtils.hasText(resolvedKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Idempotency-Key header is required");
    }
    return new GoodsReceiptRequest(
        request.purchaseOrderId(),
        request.receiptNumber(),
        request.receiptDate(),
        request.memo(),
        resolvedKey,
        request.lines());
  }
}
