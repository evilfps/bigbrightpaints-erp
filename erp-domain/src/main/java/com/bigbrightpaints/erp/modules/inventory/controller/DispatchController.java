package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Controller for dispatch confirmation workflow.
 * Used by factory department to confirm actual shipped quantities before dispatch.
 */
@RestController
@RequestMapping("/api/v1/dispatch")
public class DispatchController {

    private final FinishedGoodsService finishedGoodsService;
    private final SalesService salesService;

    public DispatchController(FinishedGoodsService finishedGoodsService,
                              SalesService salesService) {
        this.finishedGoodsService = finishedGoodsService;
        this.salesService = salesService;
    }

    /**
     * Get all packaging slips pending dispatch.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<PackagingSlipDto>>> getPendingSlips() {
        List<PackagingSlipDto> slips = finishedGoodsService.listPackagingSlips().stream()
                .filter(this::isDispatchPendingSlip)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(slips));
    }

    private boolean isDispatchPendingSlip(PackagingSlipDto slip) {
        if (slip == null) {
            return false;
        }
        String status = slip.status();
        return !"DISPATCHED".equalsIgnoreCase(status)
                && !"CANCELLED".equalsIgnoreCase(status);
    }

    /**
     * Get dispatch preview for confirmation modal.
     * Shows what was ordered vs what's available to ship.
     */
    @GetMapping("/preview/{slipId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<DispatchPreviewDto>> getDispatchPreview(@PathVariable Long slipId) {
        DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slipId);
        return ResponseEntity.ok(ApiResponse.success(preview));
    }

    /**
     * Cancel a backorder slip; releases reserved stock and quantity without shipping.
     */
    @PostMapping("/backorder/{slipId}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<PackagingSlipDto>> cancelBackorder(
            @PathVariable Long slipId,
            @RequestParam(required = false) String reason,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        PackagingSlipDto slip = finishedGoodsService.cancelBackorderSlip(slipId, username, reason);
        return ResponseEntity.ok(ApiResponse.success("Backorder canceled", slip));
    }

    /**
     * Get packaging slip details.
     */
    @GetMapping("/slip/{slipId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlip(@PathVariable Long slipId) {
        PackagingSlipDto slip = finishedGoodsService.getPackagingSlip(slipId);
        return ResponseEntity.ok(ApiResponse.success(slip));
    }

    /**
     * Get packaging slip by sales order ID.
     */
    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlipByOrder(@PathVariable Long orderId) {
        PackagingSlipDto slip = finishedGoodsService.getPackagingSlipByOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(slip));
    }

    /**
     * Confirm dispatch with actual shipped quantities.
     * This is the final step - journals, inventory, and ledger updates happen here.
     */
    @PostMapping("/confirm")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY') and hasAuthority('dispatch.confirm')")
    public ResponseEntity<ApiResponse<DispatchConfirmationResponse>> confirmDispatch(
            @Valid @RequestBody DispatchConfirmationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DispatchConfirmRequest accountingRequest = new DispatchConfirmRequest(
                request.packagingSlipId(),
                null,
                request.lines().stream()
                        .map(line -> new DispatchConfirmRequest.DispatchLine(
                                line.lineId(),
                                null,
                                line.shippedQuantity(),
                                null,
                                null,
                                null,
                                null,
                                line.notes()))
                        .toList(),
                request.notes(),
                username,
                Boolean.FALSE,
                null,
                request.overrideRequestId()
        );
        salesService.confirmDispatch(accountingRequest);
        DispatchConfirmationResponse response = finishedGoodsService.getDispatchConfirmation(request.packagingSlipId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Update packaging slip status (e.g., PENDING -> PACKING -> READY).
     */
    @PatchMapping("/slip/{slipId}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<PackagingSlipDto>> updateSlipStatus(
            @PathVariable Long slipId,
            @RequestParam String status) {
        PackagingSlipDto slip = finishedGoodsService.updateSlipStatus(slipId, status);
        return ResponseEntity.ok(ApiResponse.success(slip));
    }
}
