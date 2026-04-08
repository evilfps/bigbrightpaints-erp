package com.bigbrightpaints.erp.modules.inventory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.modules.inventory.dto.InventoryBatchMovementHistoryDto;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryBatchTraceabilityService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/inventory/batches")
public class InventoryBatchController {

  private final InventoryBatchTraceabilityService inventoryBatchTraceabilityService;

  public InventoryBatchController(
      InventoryBatchTraceabilityService inventoryBatchTraceabilityService) {
    this.inventoryBatchTraceabilityService = inventoryBatchTraceabilityService;
  }

  @GetMapping("/{id}/movements")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_SALES')")
  public ResponseEntity<ApiResponse<List<InventoryBatchMovementHistoryDto>>> getMovementHistory(
      @PathVariable Long id, @RequestParam(required = false) String batchType) {
    List<InventoryBatchMovementHistoryDto> movementHistory =
        inventoryBatchTraceabilityService
            .getBatchMovementHistory(id, batchType)
            .movements()
            .stream()
            .map(
                movement ->
                    new InventoryBatchMovementHistoryDto(
                        movement.movementType(), movement.quantity(), movement.createdAt()))
            .toList();
    return ResponseEntity.ok(ApiResponse.success("Batch movement history", movementHistory));
  }
}
