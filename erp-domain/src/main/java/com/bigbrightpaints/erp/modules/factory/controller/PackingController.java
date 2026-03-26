package com.bigbrightpaints.erp.modules.factory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/factory")
@PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
public class PackingController {
  private static final String CANONICAL_PACKING_PATH = "/api/v1/factory/packing-records";

  private final PackingService packingService;
  private final BulkPackingService bulkPackingService;

  public PackingController(PackingService packingService, BulkPackingService bulkPackingService) {
    this.packingService = packingService;
    this.bulkPackingService = bulkPackingService;
  }

  @PostMapping("/packing-records")
  public ResponseEntity<ApiResponse<ProductionLogDetailDto>> recordPacking(
      @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false)
          String idempotencyKey,
      @Parameter(hidden = true) @RequestHeader(value = "X-Idempotency-Key", required = false)
          String legacyIdempotencyKey,
      @Parameter(hidden = true) @RequestHeader(value = "X-Request-Id", required = false)
          String requestId,
      @Valid @RequestBody PackingRequest request) {
    PackingRequest resolved =
        applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey, requestId);
    return ResponseEntity.ok(
        ApiResponse.success("Packing recorded", packingService.recordPacking(resolved)));
  }

  @GetMapping("/unpacked-batches")
  public ResponseEntity<ApiResponse<List<UnpackedBatchDto>>> listUnpackedBatches() {
    return ResponseEntity.ok(ApiResponse.success(packingService.listUnpackedBatches()));
  }

  @GetMapping("/production-logs/{productionLogId}/packing-history")
  public ResponseEntity<ApiResponse<List<PackingRecordDto>>> packingHistory(
      @PathVariable Long productionLogId) {
    return ResponseEntity.ok(ApiResponse.success(packingService.packingHistory(productionLogId)));
  }

  /**
   * List available bulk batches for a finished good.
   */
  @GetMapping("/bulk-batches/{finishedGoodId}")
  @PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<List<BulkPackResponse.ChildBatchDto>>> listBulkBatches(
      @PathVariable Long finishedGoodId) {
    return ResponseEntity.ok(
        ApiResponse.success(bulkPackingService.listBulkBatches(finishedGoodId)));
  }

  /**
   * List child batches created from a parent bulk batch.
   */
  @GetMapping("/bulk-batches/{parentBatchId}/children")
  @PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<List<BulkPackResponse.ChildBatchDto>>> listChildBatches(
      @PathVariable Long parentBatchId) {
    return ResponseEntity.ok(
        ApiResponse.success(bulkPackingService.listChildBatches(parentBatchId)));
  }

  private PackingRequest applyIdempotencyKey(
      PackingRequest request,
      String idempotencyKeyHeader,
      String legacyIdempotencyKeyHeader,
      String requestId) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Packing request is required");
    }
    if (StringUtils.hasText(legacyIdempotencyKeyHeader)) {
      throw unsupportedLegacyHeader("X-Idempotency-Key");
    }
    if (StringUtils.hasText(requestId)) {
      throw unsupportedLegacyHeader("X-Request-Id");
    }
    if (!StringUtils.hasText(idempotencyKeyHeader)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Idempotency-Key header is required");
    }
    return requestWithIdempotencyKey(request, idempotencyKeyHeader.trim());
  }

  private PackingRequest requestWithIdempotencyKey(PackingRequest request, String idempotencyKey) {
    return new PackingRequest(
        request.productionLogId(),
        request.packedDate(),
        request.packedBy(),
        idempotencyKey,
        request.lines(),
        request.closeResidualWastage());
  }

  private ApplicationException unsupportedLegacyHeader(String legacyHeader) {
    return new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            legacyHeader + " is not supported for packing records; use Idempotency-Key")
        .withDetail("legacyHeader", legacyHeader)
        .withDetail("canonicalHeader", "Idempotency-Key")
        .withDetail("canonicalPath", CANONICAL_PACKING_PATH);
  }
}
