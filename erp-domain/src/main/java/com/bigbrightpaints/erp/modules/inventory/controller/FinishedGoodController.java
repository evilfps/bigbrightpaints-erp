package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsCatalogService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsStockService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for finished goods inventory management.
 */
@RestController
@RequestMapping("/api/v1/finished-goods")
public class FinishedGoodController {

    private final FinishedGoodsCatalogService finishedGoodsCatalogService;
    private final FinishedGoodsStockService finishedGoodsStockService;

    public FinishedGoodController(FinishedGoodsCatalogService finishedGoodsCatalogService,
                                  FinishedGoodsStockService finishedGoodsStockService) {
        this.finishedGoodsCatalogService = finishedGoodsCatalogService;
        this.finishedGoodsStockService = finishedGoodsStockService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<FinishedGoodDto>>> listFinishedGoods() {
        List<FinishedGoodDto> goods = finishedGoodsCatalogService.listFinishedGoods();
        return ResponseEntity.ok(ApiResponse.success("Finished goods inventory", goods));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<FinishedGoodDto>> getFinishedGood(@PathVariable Long id) {
        FinishedGoodDto good = finishedGoodsCatalogService.getFinishedGood(id);
        return ResponseEntity.ok(ApiResponse.success(good));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<FinishedGoodDto>> createFinishedGood(
            @Valid @RequestBody FinishedGoodRequest request) {
        FinishedGoodDto created = finishedGoodsCatalogService.createFinishedGood(request);
        return ResponseEntity.ok(ApiResponse.success("Finished good created", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<FinishedGoodDto>> updateFinishedGood(
            @PathVariable Long id,
            @Valid @RequestBody FinishedGoodRequest request) {
        FinishedGoodDto updated = finishedGoodsCatalogService.updateFinishedGood(id, request);
        return ResponseEntity.ok(ApiResponse.success("Finished good updated", updated));
    }

    @GetMapping("/{id}/batches")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<FinishedGoodBatchDto>>> listBatches(@PathVariable Long id) {
        List<FinishedGoodBatchDto> batches = finishedGoodsStockService.listBatchesForFinishedGood(id);
        return ResponseEntity.ok(ApiResponse.success("Finished good batches", batches));
    }

    @PostMapping("/{id}/batches")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<FinishedGoodBatchDto>> registerBatch(
            @PathVariable Long id,
            @Valid @RequestBody FinishedGoodBatchRequest request) {
        FinishedGoodBatchDto batch = finishedGoodsStockService.registerBatch(
                new FinishedGoodBatchRequest(id, request.batchCode(), request.quantity(),
                        request.unitCost(), request.manufacturedAt(), request.expiryDate()));
        return ResponseEntity.ok(ApiResponse.success("Batch registered", batch));
    }

    @GetMapping("/stock-summary")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<StockSummaryDto>>> getStockSummary() {
        List<StockSummaryDto> summary = finishedGoodsStockService.getStockSummary();
        return ResponseEntity.ok(ApiResponse.success("Stock summary", summary));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<FinishedGoodDto>>> getLowStockItems(
            @RequestParam(required = false) Integer threshold) {
        List<FinishedGoodDto> lowStock = finishedGoodsStockService.getLowStockItems(threshold);
        return ResponseEntity.ok(ApiResponse.success("Low stock items", lowStock));
    }

    @GetMapping("/{id}/low-stock-threshold")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<FinishedGoodLowStockThresholdDto>> getLowStockThreshold(@PathVariable Long id) {
        FinishedGoodLowStockThresholdDto threshold = finishedGoodsStockService.getLowStockThreshold(id);
        return ResponseEntity.ok(ApiResponse.success("Finished good low stock threshold", threshold));
    }

    @PutMapping("/{id}/low-stock-threshold")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<FinishedGoodLowStockThresholdDto>> updateLowStockThreshold(
            @PathVariable Long id,
            @Valid @RequestBody FinishedGoodLowStockThresholdRequest request) {
        FinishedGoodLowStockThresholdDto threshold =
                finishedGoodsStockService.updateLowStockThreshold(id, request.threshold());
        return ResponseEntity.ok(ApiResponse.success("Finished good low stock threshold updated", threshold));
    }
}
