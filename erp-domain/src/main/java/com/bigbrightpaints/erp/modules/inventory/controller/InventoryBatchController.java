package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.modules.inventory.dto.InventoryBatchTraceabilityDto;
import com.bigbrightpaints.erp.modules.inventory.service.InventoryBatchTraceabilityService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/batches")
public class InventoryBatchController {

    private final InventoryBatchTraceabilityService inventoryBatchTraceabilityService;

    public InventoryBatchController(InventoryBatchTraceabilityService inventoryBatchTraceabilityService) {
        this.inventoryBatchTraceabilityService = inventoryBatchTraceabilityService;
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<InventoryBatchTraceabilityDto>> getMovementHistory(
            @PathVariable Long id,
            @RequestParam(required = false) String batchType) {
        InventoryBatchTraceabilityDto traceability =
                inventoryBatchTraceabilityService.getBatchMovementHistory(id, batchType);
        return ResponseEntity.ok(ApiResponse.success("Batch movement history", traceability));
    }
}
