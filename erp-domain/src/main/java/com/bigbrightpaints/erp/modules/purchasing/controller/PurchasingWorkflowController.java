package com.bigbrightpaints.erp.modules.purchasing.controller;

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
            @Valid @RequestBody GoodsReceiptRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Goods receipt recorded", purchasingService.createGoodsReceipt(request)));
    }
}
