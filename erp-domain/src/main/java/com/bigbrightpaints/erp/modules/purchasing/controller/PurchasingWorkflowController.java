package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchasing")
public class PurchasingWorkflowController {

    private final PurchasingService purchasingService;

    public PurchasingWorkflowController(PurchasingService purchasingService) {
        this.purchasingService = purchasingService;
    }

    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<PurchaseOrderResponse>>> listPurchaseOrders(@RequestParam(required = false) Long supplierId) {
        return ResponseEntity.ok(ApiResponse.success("Purchase orders", purchasingService.listPurchaseOrders(supplierId)));
    }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PurchaseOrderResponse>> getPurchaseOrder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getPurchaseOrder(id)));
    }

    @PostMapping("/purchase-orders")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PurchaseOrderResponse>> createPurchaseOrder(
            @Valid @RequestBody PurchaseOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Purchase order recorded", purchasingService.createPurchaseOrder(request)));
    }

    @GetMapping("/goods-receipts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<GoodsReceiptResponse>>> listGoodsReceipts(@RequestParam(required = false) Long supplierId) {
        return ResponseEntity.ok(ApiResponse.success("Goods receipts", purchasingService.listGoodsReceipts(supplierId)));
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
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey,
            @Valid @RequestBody GoodsReceiptRequest request) {
        GoodsReceiptRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Goods receipt recorded", purchasingService.createGoodsReceipt(resolved)));
    }

    private GoodsReceiptRequest applyIdempotencyKey(GoodsReceiptRequest request,
                                                    String idempotencyKeyHeader,
                                                    String legacyIdempotencyKeyHeader) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Goods receipt request is required");
        }
        String resolvedKey = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
                request.idempotencyKey(),
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader
        );
        if (StringUtils.hasText(request.idempotencyKey())) {
            return request;
        }
        if (!StringUtils.hasText(resolvedKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency-Key header is required");
        }
        return new GoodsReceiptRequest(
                request.purchaseOrderId(),
                request.receiptNumber(),
                request.receiptDate(),
                request.memo(),
                resolvedKey,
                request.lines()
        );
    }
}
