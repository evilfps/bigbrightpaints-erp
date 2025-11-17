package com.bigbrightpaints.erp.modules.factory.controller;

import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/factory")
public class PackingController {

    private final PackingService packingService;

    public PackingController(PackingService packingService) {
        this.packingService = packingService;
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
}
