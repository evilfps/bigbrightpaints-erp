package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryBatchQueryService;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
public class RawMaterialController {

    private final RawMaterialService rawMaterialService;
    private final InventoryBatchQueryService inventoryBatchQueryService;
    private final Validator validator;

    public RawMaterialController(RawMaterialService rawMaterialService,
                                 InventoryBatchQueryService inventoryBatchQueryService,
                                 Validator validator) {
        this.rawMaterialService = rawMaterialService;
        this.inventoryBatchQueryService = inventoryBatchQueryService;
        this.validator = validator;
    }

    @GetMapping("/raw-materials/stock")
    public ResponseEntity<ApiResponse<StockSummaryDto>> stockSummary() {
        return ResponseEntity.ok(ApiResponse.success(rawMaterialService.summarizeStock()));
    }

    @GetMapping("/raw-materials/stock/inventory")
    public ResponseEntity<ApiResponse<List<InventoryStockSnapshot>>> inventory() {
        return ResponseEntity.ok(ApiResponse.success(rawMaterialService.listInventory()));
    }

    @GetMapping("/raw-materials/stock/low-stock")
    public ResponseEntity<ApiResponse<List<InventoryStockSnapshot>>> lowStock() {
        return ResponseEntity.ok(ApiResponse.success(rawMaterialService.listLowStock()));
    }

    @PostMapping("/inventory/raw-materials/adjustments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<RawMaterialAdjustmentDto>> adjustRawMaterials(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey,
            @RequestBody RawMaterialAdjustmentRequest request
    ) {
        RawMaterialAdjustmentRequest resolvedRequest = applyAdjustmentIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        validateAdjustmentRequest(resolvedRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Raw material adjustment posted",
                rawMaterialService.adjustStock(resolvedRequest)
        ));
    }

    @GetMapping("/inventory/batches/expiring-soon")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<InventoryExpiringBatchDto>>> expiringSoonBatches(
            @RequestParam(defaultValue = "30") int days
    ) {
        int safeDays = Math.max(days, 0);
        return ResponseEntity.ok(ApiResponse.success(
                inventoryBatchQueryService.listExpiringSoonBatches(safeDays)
        ));
    }

    private RawMaterialAdjustmentRequest applyAdjustmentIdempotencyKey(RawMaterialAdjustmentRequest request,
                                                                       String idempotencyKeyHeader,
                                                                       String legacyIdempotencyKeyHeader) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Raw material adjustment request is required");
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
        return new RawMaterialAdjustmentRequest(
                request.adjustmentDate(),
                request.direction(),
                request.adjustmentAccountId(),
                request.reason(),
                request.adminOverride(),
                resolvedKey,
                request.lines()
        );
    }

    private void validateAdjustmentRequest(RawMaterialAdjustmentRequest request) {
        Set<ConstraintViolation<RawMaterialAdjustmentRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
