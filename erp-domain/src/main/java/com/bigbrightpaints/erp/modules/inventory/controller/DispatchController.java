package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final SalesDispatchReconciliationService salesDispatchReconciliationService;
    private final DeliveryChallanPdfService deliveryChallanPdfService;

    public DispatchController(FinishedGoodsService finishedGoodsService,
                              SalesDispatchReconciliationService salesDispatchReconciliationService,
                              DeliveryChallanPdfService deliveryChallanPdfService) {
        this.finishedGoodsService = finishedGoodsService;
        this.salesDispatchReconciliationService = salesDispatchReconciliationService;
        this.deliveryChallanPdfService = deliveryChallanPdfService;
    }

    /**
     * Get all packaging slips pending dispatch.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<PackagingSlipDto>>> getPendingSlips() {
        List<PackagingSlipDto> slips = finishedGoodsService.listPackagingSlips().stream()
                .filter(s -> !"DISPATCHED".equalsIgnoreCase(s.status()))
                .map(this::toPackagingSlipView)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(slips));
    }

    /**
     * Get dispatch preview for confirmation modal.
     * Shows what was ordered vs what's available to ship.
     */
    @GetMapping("/preview/{slipId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<ApiResponse<DispatchPreviewDto>> getDispatchPreview(@PathVariable Long slipId) {
        DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slipId);
        return ResponseEntity.ok(ApiResponse.success(toDispatchPreviewView(preview)));
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
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
    }

    /**
     * Get packaging slip by sales order ID.
     */
    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')")
    public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlipByOrder(@PathVariable Long orderId) {
        PackagingSlipDto slip = finishedGoodsService.getPackagingSlipByOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
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
        validateFactoryDispatchMetadata(request);
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
                request.overrideRequestId(),
                request.transporterName(),
                request.driverName(),
                request.vehicleNumber(),
                request.challanReference()
        );
        salesDispatchReconciliationService.confirmDispatch(accountingRequest);
        DispatchConfirmationResponse response = toDispatchConfirmationView(
                finishedGoodsService.getDispatchConfirmation(request.packagingSlipId()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping(value = "/slip/{slipId}/challan/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')")
    public ResponseEntity<byte[]> downloadDeliveryChallan(@PathVariable Long slipId) {
        DeliveryChallanPdfService.PdfDocument pdf = deliveryChallanPdfService.renderDeliveryChallanPdf(slipId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.content());
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
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
    }

    private void validateFactoryDispatchMetadata(DispatchConfirmationRequest request) {
        if (!isOperationalFactoryView()) {
            return;
        }
        boolean hasTransportActor = hasText(request.transporterName()) || hasText(request.driverName());
        if (!hasTransportActor) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Dispatch confirmation requires transporterName or driverName");
        }
        if (!hasText(request.vehicleNumber())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Dispatch confirmation requires vehicleNumber");
        }
        if (!hasText(request.challanReference())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput("Dispatch confirmation requires challanReference");
        }
    }

    private PackagingSlipDto toPackagingSlipView(PackagingSlipDto slip) {
        if (slip == null || !isOperationalFactoryView()) {
            return slip;
        }
        return new PackagingSlipDto(
                slip.id(),
                slip.publicId(),
                slip.salesOrderId(),
                slip.orderNumber(),
                slip.dealerName(),
                slip.slipNumber(),
                slip.status(),
                slip.createdAt(),
                slip.confirmedAt(),
                slip.confirmedBy(),
                slip.dispatchedAt(),
                slip.dispatchNotes(),
                null,
                null,
                slip.lines(),
                slip.transporterName(),
                slip.driverName(),
                slip.vehicleNumber(),
                slip.challanReference(),
                slip.deliveryChallanNumber(),
                slip.deliveryChallanPdfPath()
        );
    }

    private DispatchPreviewDto toDispatchPreviewView(DispatchPreviewDto preview) {
        if (preview == null || !isOperationalFactoryView()) {
            return preview;
        }
        List<DispatchPreviewDto.LinePreview> lines = preview.lines() == null
                ? List.of()
                : preview.lines().stream()
                .map(line -> new DispatchPreviewDto.LinePreview(
                        line.lineId(),
                        line.finishedGoodId(),
                        line.productCode(),
                        line.productName(),
                        line.batchCode(),
                        line.orderedQuantity(),
                        line.availableQuantity(),
                        line.suggestedShipQuantity(),
                        null,
                        null,
                        null,
                        null,
                        line.hasShortage()))
                .toList();
        return new DispatchPreviewDto(
                preview.packagingSlipId(),
                preview.slipNumber(),
                preview.status(),
                preview.salesOrderId(),
                preview.salesOrderNumber(),
                preview.dealerName(),
                preview.dealerCode(),
                preview.createdAt(),
                null,
                preview.totalAvailableAmount(),
                null,
                lines
        );
    }

    private DispatchConfirmationResponse toDispatchConfirmationView(DispatchConfirmationResponse response) {
        if (response == null || !isOperationalFactoryView()) {
            return response;
        }
        List<DispatchConfirmationResponse.LineResult> lineResults = response.lines() == null
                ? List.of()
                : response.lines().stream()
                .map(line -> new DispatchConfirmationResponse.LineResult(
                        line.lineId(),
                        line.productCode(),
                        line.productName(),
                        line.orderedQuantity(),
                        line.shippedQuantity(),
                        line.backorderQuantity(),
                        null,
                        null,
                        line.notes()))
                .toList();
        return new DispatchConfirmationResponse(
                response.packagingSlipId(),
                response.slipNumber(),
                response.status(),
                response.confirmedAt(),
                response.confirmedBy(),
                null,
                null,
                null,
                null,
                null,
                lineResults,
                response.backorderSlipId(),
                response.transporterName(),
                response.driverName(),
                response.vehicleNumber(),
                response.challanReference(),
                response.deliveryChallanNumber(),
                response.deliveryChallanPdfPath()
        );
    }

    private boolean isOperationalFactoryView() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        boolean factory = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_FACTORY".equals(authority.getAuthority()));
        boolean elevated = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_ACCOUNTING".equals(authority.getAuthority())
                        || "ROLE_SALES".equals(authority.getAuthority()));
        return factory && !elevated;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
