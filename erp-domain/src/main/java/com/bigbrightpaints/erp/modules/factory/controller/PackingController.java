package com.bigbrightpaints.erp.modules.factory.controller;

import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/factory")
@PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
public class PackingController {
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String PACKING_COMMAND = "FACTORY.PACKING.RECORD";

    private final PackingService packingService;
    private final BulkPackingService bulkPackingService;

    public PackingController(PackingService packingService, BulkPackingService bulkPackingService) {
        this.packingService = packingService;
        this.bulkPackingService = bulkPackingService;
    }

    @PostMapping("/packing-records")
    public ResponseEntity<ApiResponse<ProductionLogDetailDto>> recordPacking(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody PackingRequest request) {
        PackingRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey, requestId);
        return ResponseEntity.ok(ApiResponse.success("Packing recorded", packingService.recordPacking(resolved)));
    }

    @PostMapping("/packing-records/{productionLogId}/complete")
    public ResponseEntity<ApiResponse<ProductionLogDetailDto>> completePacking(@PathVariable Long productionLogId) {
        return ResponseEntity.ok(ApiResponse.success("Packing completed", packingService.completePacking(productionLogId)));
    }

    @GetMapping("/unpacked-batches")
    public ResponseEntity<ApiResponse<List<UnpackedBatchDto>>> listUnpackedBatches() {
        return ResponseEntity.ok(ApiResponse.success(packingService.listUnpackedBatches()));
    }

    @GetMapping("/production-logs/{productionLogId}/packing-history")
    public ResponseEntity<ApiResponse<List<PackingRecordDto>>> packingHistory(@PathVariable Long productionLogId) {
        return ResponseEntity.ok(ApiResponse.success(packingService.packingHistory(productionLogId)));
    }

    // ===== Bulk-to-Size Packaging =====

    /**
     * Pack a bulk FG batch into sized child SKUs.
     * Converts parent SKU (e.g., Safari-WHITE) into child SKUs (Safari-WHITE-1L, Safari-WHITE-4L).
     */
    @PostMapping("/pack")
    @PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BulkPackResponse>> packBulkToSizes(@Valid @RequestBody BulkPackRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bulk packed into sizes", bulkPackingService.pack(request)));
    }

    /**
     * List available bulk batches for a finished good.
     */
    @GetMapping("/bulk-batches/{finishedGoodId}")
    @PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<BulkPackResponse.ChildBatchDto>>> listBulkBatches(
            @PathVariable Long finishedGoodId) {
        return ResponseEntity.ok(ApiResponse.success(bulkPackingService.listBulkBatches(finishedGoodId)));
    }

    /**
     * List child batches created from a parent bulk batch.
     */
    @GetMapping("/bulk-batches/{parentBatchId}/children")
    @PreAuthorize("hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<List<BulkPackResponse.ChildBatchDto>>> listChildBatches(
            @PathVariable Long parentBatchId) {
        return ResponseEntity.ok(ApiResponse.success(bulkPackingService.listChildBatches(parentBatchId)));
    }

    private PackingRequest applyIdempotencyKey(PackingRequest request,
                                               String idempotencyKeyHeader,
                                               String legacyIdempotencyKeyHeader,
                                               String requestId) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Packing request is required");
        }
        String resolvedKey = IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
                request.idempotencyKey(),
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader
        );
        if (StringUtils.hasText(resolvedKey)) {
            return requestWithIdempotencyKey(request, resolvedKey);
        }
        return requestWithIdempotencyKey(request, resolveFallbackIdempotencyKey(request, requestId));
    }

    private PackingRequest requestWithIdempotencyKey(PackingRequest request, String idempotencyKey) {
        return new PackingRequest(request.productionLogId(),
                request.packedDate(),
                request.packedBy(),
                idempotencyKey,
                request.lines());
    }

    private String resolveFallbackIdempotencyKey(PackingRequest request, String requestId) {
        if (StringUtils.hasText(requestId)) {
            String requestScoped = "REQ|" + PACKING_COMMAND + "|" + requestId.trim();
            if (requestScoped.length() <= MAX_IDEMPOTENCY_KEY_LENGTH) {
                return requestScoped;
            }
            return "REQH|" + PACKING_COMMAND + "|" + DigestUtils.sha256Hex(requestScoped);
        }
        return "AUTO|" + PACKING_COMMAND + "|" + DigestUtils.sha256Hex(buildRequestSignature(request));
    }

    private String buildRequestSignature(PackingRequest request) {
        StringBuilder payload = new StringBuilder();
        payload.append("log=").append(request.productionLogId())
                .append("|date=").append(request.packedDate())
                .append("|by=").append(clean(request.packedBy()));
        if (request.lines() != null) {
            int idx = 0;
            for (PackingLineRequest line : request.lines()) {
                idx++;
                payload.append("|line").append(idx).append(':')
                        .append(clean(line.packagingSize()))
                        .append(':').append(decimalToken(line.quantityLiters()))
                        .append(':').append(line.piecesCount())
                        .append(':').append(line.boxesCount())
                        .append(':').append(line.piecesPerBox());
            }
        }
        return payload.toString();
    }

    private String decimalToken(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
