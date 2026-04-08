package com.bigbrightpaints.erp.modules.inventory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchInventoryDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodLowStockThresholdRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodStockSummaryDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.validation.Valid;

/**
 * Controller for finished goods inventory management.
 */
@RestController
@RequestMapping("/api/v1/finished-goods")
public class FinishedGoodController {

  private final FinishedGoodsService finishedGoodsService;

  public FinishedGoodController(FinishedGoodsService finishedGoodsService) {
    this.finishedGoodsService = finishedGoodsService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PageResponse<FinishedGoodDto>>> listFinishedGoods(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    PageResponse<FinishedGoodDto> goods = finishedGoodsService.listFinishedGoods(page, size);
    return ResponseEntity.ok(ApiResponse.success("Finished goods inventory", goods));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<FinishedGoodDto>> getFinishedGood(@PathVariable Long id) {
    FinishedGoodDto good = finishedGoodsService.getFinishedGood(id);
    return ResponseEntity.ok(ApiResponse.success(good));
  }

  @GetMapping("/{id}/batches")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<FinishedGoodBatchInventoryDto>>> listBatches(
      @PathVariable Long id) {
    List<FinishedGoodBatchInventoryDto> batches =
        finishedGoodsService.listBatchesForFinishedGood(id);
    return ResponseEntity.ok(ApiResponse.success("Finished good batches", batches));
  }

  @GetMapping("/stock-summary")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<FinishedGoodStockSummaryDto>>> getStockSummary() {
    List<FinishedGoodStockSummaryDto> summary = finishedGoodsService.getStockSummary();
    return ResponseEntity.ok(ApiResponse.success("Stock summary", summary));
  }

  @GetMapping("/low-stock")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<FinishedGoodDto>>> getLowStockItems(
      @RequestParam(required = false) Integer threshold) {
    List<FinishedGoodDto> lowStock = finishedGoodsService.getLowStockItems(threshold);
    return ResponseEntity.ok(ApiResponse.success("Low stock items", lowStock));
  }

  @GetMapping("/{id}/low-stock-threshold")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<FinishedGoodLowStockThresholdDto>> getLowStockThreshold(
      @PathVariable Long id) {
    FinishedGoodLowStockThresholdDto threshold = finishedGoodsService.getLowStockThreshold(id);
    return ResponseEntity.ok(ApiResponse.success("Finished good low stock threshold", threshold));
  }

  @PutMapping("/{id}/low-stock-threshold")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<FinishedGoodLowStockThresholdDto>> updateLowStockThreshold(
      @PathVariable Long id, @Valid @RequestBody FinishedGoodLowStockThresholdRequest request) {
    FinishedGoodLowStockThresholdDto threshold =
        finishedGoodsService.updateLowStockThreshold(id, request.threshold());
    return ResponseEntity.ok(
        ApiResponse.success("Finished good low stock threshold updated", threshold));
  }
}
