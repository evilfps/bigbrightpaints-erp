package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RawMaterialController {

    private final RawMaterialService rawMaterialService;

    public RawMaterialController(RawMaterialService rawMaterialService) {
        this.rawMaterialService = rawMaterialService;
    }

    @GetMapping("/accounting/raw-materials")
    public ResponseEntity<ApiResponse<List<RawMaterialDto>>> listRawMaterials() {
        return ResponseEntity.ok(ApiResponse.success(rawMaterialService.listRawMaterials()));
    }

    @PostMapping("/accounting/raw-materials")
    public ResponseEntity<ApiResponse<RawMaterialDto>> createRawMaterial(@Valid @RequestBody RawMaterialRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Raw material created", rawMaterialService.createRawMaterial(request)));
    }

    @PutMapping("/accounting/raw-materials/{id}")
    public ResponseEntity<ApiResponse<RawMaterialDto>> updateRawMaterial(@PathVariable Long id,
                                                                         @Valid @RequestBody RawMaterialRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Raw material updated", rawMaterialService.updateRawMaterial(id, request)));
    }

    @DeleteMapping("/accounting/raw-materials/{id}")
    public ResponseEntity<Void> deleteRawMaterial(@PathVariable Long id) {
        rawMaterialService.deleteRawMaterial(id);
        return ResponseEntity.noContent().build();
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

    @GetMapping("/raw-material-batches/{rawMaterialId}")
    public ResponseEntity<ApiResponse<List<RawMaterialBatchDto>>> batches(@PathVariable Long rawMaterialId) {
        return ResponseEntity.ok(ApiResponse.success(rawMaterialService.listBatches(rawMaterialId)));
    }

    @PostMapping("/raw-material-batches/{rawMaterialId}")
    public ResponseEntity<ApiResponse<RawMaterialBatchDto>> createBatch(@PathVariable Long rawMaterialId,
                                                                        @Valid @RequestBody RawMaterialBatchRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Batch recorded", rawMaterialService.createBatch(rawMaterialId, request)));
    }

    @PostMapping("/raw-materials/intake")
    public ResponseEntity<ApiResponse<RawMaterialBatchDto>> intake(@Valid @RequestBody RawMaterialIntakeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Intake recorded", rawMaterialService.intake(request)));
    }
}
