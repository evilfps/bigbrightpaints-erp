package com.bigbrightpaints.erp.modules.inventory.controller;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.DispatchMetadataValidator;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
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
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY_SALES)
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
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY)
    public ResponseEntity<ApiResponse<DispatchPreviewDto>> getDispatchPreview(@PathVariable Long slipId) {
        DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slipId);
        return ResponseEntity.ok(ApiResponse.success(toDispatchPreviewView(preview)));
    }

    /**
     * Cancel a backorder slip; releases reserved stock and quantity without shipping.
     */
    @PostMapping("/backorder/{slipId}/cancel")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY)
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
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY_SALES)
    public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlip(@PathVariable Long slipId) {
        PackagingSlipDto slip = finishedGoodsService.getPackagingSlip(slipId);
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
    }

    /**
     * Get packaging slip by sales order ID.
     */
    @GetMapping("/order/{orderId}")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY_SALES)
    public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlipByOrder(@PathVariable Long orderId) {
        PackagingSlipDto slip = finishedGoodsService.getPackagingSlipByOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
    }

    /**
     * Confirm dispatch with actual shipped quantities.
     * This is the final step - journals, inventory, and ledger updates happen here.
     */
    @PostMapping("/confirm")
    @PreAuthorize(PortalRoleActionMatrix.OPERATIONAL_DISPATCH)
    public ResponseEntity<ApiResponse<DispatchConfirmationResponse>> confirmDispatch(
            @Valid @RequestBody DispatchConfirmationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        validateFactoryDispatchMetadata(request);
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
        if (shouldEnforceDispatchMetadata(accountingRequest, request.packagingSlipId())) {
            DispatchMetadataValidator.validate(accountingRequest);
        }
        salesDispatchReconciliationService.confirmDispatch(accountingRequest);
        DispatchConfirmationResponse response = toDispatchConfirmationView(
                finishedGoodsService.getDispatchConfirmation(request.packagingSlipId()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping(value = "/slip/{slipId}/challan/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY)
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
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY)
    public ResponseEntity<ApiResponse<PackagingSlipDto>> updateSlipStatus(
            @PathVariable Long slipId,
            @RequestParam String status) {
        PackagingSlipDto slip = finishedGoodsService.updateSlipStatus(slipId, status);
        return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
    }

    private boolean shouldEnforceDispatchMetadata(DispatchConfirmRequest request, Long packagingSlipId) {
        return DispatchMetadataValidator.shouldEnforceValidation(request, () -> isDispatchedSlipReplay(packagingSlipId));
    }

    private boolean isDispatchedSlipReplay(Long packagingSlipId) {
        if (packagingSlipId == null) {
            return false;
        }
        try {
            PackagingSlipDto slip = finishedGoodsService.getPackagingSlip(packagingSlipId);
            return slip != null && "DISPATCHED".equalsIgnoreCase(slip.status());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void validateFactoryDispatchMetadata(DispatchConfirmationRequest request) {
        if (!isOperationalFactoryView()) {
            return;
        }
        if (request == null || isDispatchedSlipReplay(request.packagingSlipId())) {
            return;
        }
        boolean hasTransportActor = StringUtils.hasText(request.transporterName())
                || StringUtils.hasText(request.driverName());
        if (!hasTransportActor) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput(PortalRoleActionMatrix.transporterOrDriverRequiredMessage());
        }
        if (!StringUtils.hasText(request.vehicleNumber())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput(PortalRoleActionMatrix.vehicleNumberRequiredMessage());
        }
        if (!StringUtils.hasText(request.challanReference())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidInput(PortalRoleActionMatrix.challanReferenceRequiredMessage());
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
                redactFactorySlipLines(slip.lines()),
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
                null,
                null,
                lines
        );
    }

    private List<PackagingSlipLineDto> redactFactorySlipLines(List<PackagingSlipLineDto> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(line -> new PackagingSlipLineDto(
                        line.id(),
                        line.batchPublicId(),
                        line.batchCode(),
                        line.productCode(),
                        line.productName(),
                        line.orderedQuantity(),
                        line.shippedQuantity(),
                        line.backorderQuantity(),
                        line.quantity(),
                        null,
                        line.notes()))
                .toList();
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
}
