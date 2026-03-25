package com.bigbrightpaints.erp.modules.inventory.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.inventory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.service.DeliveryChallanPdfService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

/**
 * Read-only controller for operational dispatch lookup.
 * Serves pending-slip lookup, dispatch preview, slip detail, order lookup, and challan download
 * surfaces for factory-facing dispatch operations.
 */
@RestController
@RequestMapping("/api/v1/dispatch")
public class DispatchController {

  private final FinishedGoodsService finishedGoodsService;
  private final DeliveryChallanPdfService deliveryChallanPdfService;

  public DispatchController(
      FinishedGoodsService finishedGoodsService,
      DeliveryChallanPdfService deliveryChallanPdfService) {
    this.finishedGoodsService = finishedGoodsService;
    this.deliveryChallanPdfService = deliveryChallanPdfService;
  }

  /**
   * Get all packaging slips pending dispatch.
   */
  @GetMapping("/pending")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY_SALES)
  public ResponseEntity<ApiResponse<List<PackagingSlipDto>>> getPendingSlips() {
    List<PackagingSlipDto> slips =
        finishedGoodsService.listPackagingSlips().stream()
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
  public ResponseEntity<ApiResponse<DispatchPreviewDto>> getDispatchPreview(
      @PathVariable Long slipId) {
    DispatchPreviewDto preview = finishedGoodsService.getDispatchPreview(slipId);
    return ResponseEntity.ok(ApiResponse.success(toDispatchPreviewView(preview)));
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
  public ResponseEntity<ApiResponse<PackagingSlipDto>> getPackagingSlipByOrder(
      @PathVariable Long orderId) {
    PackagingSlipDto slip = finishedGoodsService.getPackagingSlipByOrder(orderId);
    return ResponseEntity.ok(ApiResponse.success(toPackagingSlipView(slip)));
  }

  @GetMapping(value = "/slip/{slipId}/challan/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY)
  public ResponseEntity<byte[]> downloadDeliveryChallan(@PathVariable Long slipId) {
    DeliveryChallanPdfService.PdfDocument pdf =
        deliveryChallanPdfService.renderDeliveryChallanPdf(slipId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf.content());
  }

  private PackagingSlipDto toPackagingSlipView(PackagingSlipDto slip) {
    if (slip == null || !isOperationalFactoryView()) {
      return slip;
    }
    List<PackagingSlipLineDto> redactedLines =
        slip.lines() == null
            ? List.of()
            : slip.lines().stream()
                .map(
                    line -> {
                      BigDecimal redactedUnitCost = null;
                      return new PackagingSlipLineDto(
                          line.id(),
                          line.batchPublicId(),
                          line.batchCode(),
                          line.productCode(),
                          line.productName(),
                          line.orderedQuantity(),
                          line.shippedQuantity(),
                          line.backorderQuantity(),
                          line.quantity(),
                          redactedUnitCost,
                          line.notes());
                    })
                .toList();
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
        redactedLines,
        slip.transporterName(),
        slip.driverName(),
        slip.vehicleNumber(),
        slip.challanReference(),
        slip.deliveryChallanNumber(),
        slip.deliveryChallanPdfPath());
  }

  private DispatchPreviewDto toDispatchPreviewView(DispatchPreviewDto preview) {
    if (preview == null || !isOperationalFactoryView()) {
      return preview;
    }
    List<DispatchPreviewDto.LinePreview> lines =
        preview.lines() == null
            ? List.of()
            : preview.lines().stream()
                .map(
                    line ->
                        new DispatchPreviewDto.LinePreview(
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
        lines);
  }

  private boolean isOperationalFactoryView() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    boolean factory =
        authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_FACTORY".equals(authority.getAuthority()));
    boolean elevated =
        authentication.getAuthorities().stream()
            .anyMatch(
                authority ->
                    "ROLE_ADMIN".equals(authority.getAuthority())
                        || "ROLE_ACCOUNTING".equals(authority.getAuthority())
                        || "ROLE_SALES".equals(authority.getAuthority()));
    return factory && !elevated;
  }
}
