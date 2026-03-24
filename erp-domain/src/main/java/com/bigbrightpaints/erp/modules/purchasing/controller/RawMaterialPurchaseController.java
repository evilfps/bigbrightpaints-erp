package com.bigbrightpaints.erp.modules.purchasing.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/purchasing/raw-material-purchases")
public class RawMaterialPurchaseController {

    private final PurchasingService purchasingService;

    public RawMaterialPurchaseController(PurchasingService purchasingService) {
        this.purchasingService = purchasingService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<RawMaterialPurchaseResponse>>> listPurchases(@RequestParam(required = false) Long supplierId) {
        return ResponseEntity.ok(ApiResponse.success("Raw material purchases", purchasingService.listPurchases(supplierId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<RawMaterialPurchaseResponse>> getPurchase(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getPurchase(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<RawMaterialPurchaseResponse>> createPurchase(@Valid @RequestBody RawMaterialPurchaseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Raw material purchase recorded", purchasingService.createPurchase(request)));
    }

    @PostMapping("/returns")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordPurchaseReturn(@Valid @RequestBody PurchaseReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Purchase return recorded", purchasingService.recordPurchaseReturn(request)));
    }

    @PostMapping("/returns/preview")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PurchaseReturnPreviewDto>> previewPurchaseReturn(@Valid @RequestBody PurchaseReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Purchase return preview", purchasingService.previewPurchaseReturn(request)));
    }
}
