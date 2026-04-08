package com.bigbrightpaints.erp.modules.purchasing.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/purchasing/raw-material-purchases")
public class RawMaterialPurchaseController {
  private static final String CANONICAL_PURCHASE_INVOICE_PATH =
      "/api/v1/purchasing/raw-material-purchases";
  private static final String CANONICAL_PURCHASE_RETURN_PATH =
      "/api/v1/purchasing/raw-material-purchases/returns";

  private final PurchasingService purchasingService;

  public RawMaterialPurchaseController(PurchasingService purchasingService) {
    this.purchasingService = purchasingService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<RawMaterialPurchaseResponse>>> listPurchases(
      @RequestParam(required = false) Long supplierId) {
    return ResponseEntity.ok(
        ApiResponse.success("Raw material purchases", purchasingService.listPurchases(supplierId)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<RawMaterialPurchaseResponse>> getPurchase(
      @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(purchasingService.getPurchase(id)));
  }

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<RawMaterialPurchaseResponse>> createPurchase(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
      @Parameter(hidden = true) @RequestHeader(value = "X-Idempotency-Key", required = false)
          String legacyIdempotencyKeyHeader,
      @Valid @RequestBody RawMaterialPurchaseRequest request) {
    String idempotencyKey =
        resolveIdempotencyKey(
            idempotencyKeyHeader,
            legacyIdempotencyKeyHeader,
            "purchase invoices",
            CANONICAL_PURCHASE_INVOICE_PATH);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.success(
                "Raw material purchase recorded",
                purchasingService.createPurchase(request, idempotencyKey)));
  }

  @PostMapping("/returns")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> recordPurchaseReturn(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
      @Parameter(hidden = true) @RequestHeader(value = "X-Idempotency-Key", required = false)
          String legacyIdempotencyKeyHeader,
      @Valid @RequestBody PurchaseReturnRequest request) {
    String idempotencyKey =
        resolveIdempotencyKey(
            idempotencyKeyHeader,
            legacyIdempotencyKeyHeader,
            "purchase returns",
            CANONICAL_PURCHASE_RETURN_PATH);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.success(
                "Purchase return recorded",
                purchasingService.recordPurchaseReturn(request, idempotencyKey)));
  }

  @PostMapping("/returns/preview")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PurchaseReturnPreviewDto>> previewPurchaseReturn(
      @Valid @RequestBody PurchaseReturnRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Purchase return preview", purchasingService.previewPurchaseReturn(request)));
  }

  private String resolveIdempotencyKey(
      String idempotencyKeyHeader,
      String legacyIdempotencyKeyHeader,
      String resourceDescription,
      String canonicalPath) {
    if (StringUtils.hasText(legacyIdempotencyKeyHeader)) {
      throw IdempotencyHeaderUtils.unsupportedLegacyHeader(
          "X-Idempotency-Key", resourceDescription, canonicalPath);
    }
    return IdempotencyHeaderUtils.resolveHeaderKey(idempotencyKeyHeader);
  }
}
