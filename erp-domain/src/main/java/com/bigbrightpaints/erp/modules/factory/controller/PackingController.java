package com.bigbrightpaints.erp.modules.factory.controller;

import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/factory")
public class PackingController {

    private final PackingService packingService;
    private final BulkPackingService bulkPackingService;

    public PackingController(PackingService packingService, BulkPackingService bulkPackingService) {
        this.packingService = packingService;
        this.bulkPackingService = bulkPackingService;
    }

    @PostMapping("/packing-records")
    public ResponseEntity<ApiResponse<ProductionLogDetailDto>> recordPacking(@Valid @RequestBody PackingRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Packing recorded", packingService.recordPacking(request)));
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
}
