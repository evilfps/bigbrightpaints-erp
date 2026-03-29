package com.bigbrightpaints.erp.modules.inventory.controller;

import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryAdjustmentService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

@RestController
@RequestMapping("/api/v1/inventory/adjustments")
public class InventoryAdjustmentController {

  private static final String CANONICAL_INVENTORY_ADJUSTMENT_PATH = "/api/v1/inventory/adjustments";

  private final InventoryAdjustmentService inventoryAdjustmentService;
  private final Validator validator;

  public InventoryAdjustmentController(
      InventoryAdjustmentService inventoryAdjustmentService, Validator validator) {
    this.inventoryAdjustmentService = inventoryAdjustmentService;
    this.validator = validator;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<InventoryAdjustmentDto>>> listAdjustments() {
    return ResponseEntity.ok(ApiResponse.success(inventoryAdjustmentService.listAdjustments()));
  }

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<InventoryAdjustmentDto>> createAdjustment(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Parameter(hidden = true) @RequestHeader(value = "X-Idempotency-Key", required = false)
          String legacyIdempotencyKey,
      @RequestBody InventoryAdjustmentRequest request) {
    InventoryAdjustmentRequest resolved =
        applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
    validateRequest(resolved);
    return ResponseEntity.ok(
        ApiResponse.success(
            "Inventory adjustment posted", inventoryAdjustmentService.createAdjustment(resolved)));
  }

  private InventoryAdjustmentRequest applyIdempotencyKey(
      InventoryAdjustmentRequest request,
      String idempotencyKeyHeader,
      String legacyIdempotencyKeyHeader) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Inventory adjustment request is required");
    }
    IdempotencyHeaderUtils.rejectLegacyHeader(
        legacyIdempotencyKeyHeader,
        "inventory adjustments",
        CANONICAL_INVENTORY_ADJUSTMENT_PATH);
    String resolvedKey =
        IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
            request.idempotencyKey(), idempotencyKeyHeader, null);
    if (StringUtils.hasText(request.idempotencyKey())) {
      return request;
    }
    if (!StringUtils.hasText(resolvedKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Idempotency-Key header is required");
    }
    return new InventoryAdjustmentRequest(
        request.adjustmentDate(),
        request.type(),
        request.adjustmentAccountId(),
        request.reason(),
        request.adminOverride(),
        resolvedKey,
        request.lines());
  }

  private void validateRequest(InventoryAdjustmentRequest request) {
    Set<ConstraintViolation<InventoryAdjustmentRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
  }
}
