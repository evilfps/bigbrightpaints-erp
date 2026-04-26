package com.bigbrightpaints.erp.modules.inventory.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportHistoryItem;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import com.bigbrightpaints.erp.modules.production.service.SkuReadinessService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/inventory")
public class OpeningStockImportController {

  private final OpeningStockImportService openingStockImportService;
  private final SkuReadinessService skuReadinessService;

  public OpeningStockImportController(
      OpeningStockImportService openingStockImportService,
      SkuReadinessService skuReadinessService) {
    this.openingStockImportService = openingStockImportService;
    this.skuReadinessService = skuReadinessService;
  }

  @PostMapping(value = "/opening-stock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<OpeningStockImportResponse>> importOpeningStock(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestParam("openingStockBatchKey") String openingStockBatchKey,
      @RequestPart("file") MultipartFile file,
      Authentication authentication) {
    boolean includeAccountingMetadata = canViewAccountingMetadata(authentication);
    try {
      OpeningStockImportResponse response =
          openingStockImportService.importOpeningStock(file, idempotencyKey, openingStockBatchKey);
      return ResponseEntity.ok(
          ApiResponse.success(
              "Opening stock import processed",
              sanitizeResponseReadiness(response, includeAccountingMetadata)));
    } catch (ApplicationException ex) {
      if (includeAccountingMetadata) {
        throw ex;
      }
      throw sanitizeImportException(ex);
    }
  }

  @PostMapping(value = "/opening-stock/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<OpeningStockImportResponse>> previewOpeningStock(
      @RequestParam("openingStockBatchKey") String openingStockBatchKey,
      @RequestPart("file") MultipartFile file,
      Authentication authentication) {
    boolean includeAccountingMetadata = canViewAccountingMetadata(authentication);
    try {
      OpeningStockImportResponse response =
          openingStockImportService.previewOpeningStock(file, openingStockBatchKey);
      return ResponseEntity.ok(
          ApiResponse.success(
              "Opening stock preview processed",
              sanitizeResponseReadiness(response, includeAccountingMetadata)));
    } catch (ApplicationException ex) {
      if (includeAccountingMetadata) {
        throw ex;
      }
      throw sanitizeImportException(ex);
    }
  }

  @GetMapping("/opening-stock")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')")
  public ResponseEntity<ApiResponse<PageResponse<OpeningStockImportHistoryItem>>> importHistory(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    PageResponse<OpeningStockImportHistoryItem> history =
        openingStockImportService.listImportHistory(page, size);
    return ResponseEntity.ok(ApiResponse.success("Opening stock import history", history));
  }

  private OpeningStockImportResponse sanitizeResponseReadiness(
      OpeningStockImportResponse response, boolean includeAccountingMetadata) {
    if (response == null || includeAccountingMetadata) {
      return response;
    }
    return new OpeningStockImportResponse(
        response.openingStockBatchKey(),
        response.preview(),
        response.rowsProcessed(),
        response.rawMaterialBatchesCreated(),
        response.finishedGoodBatchesCreated(),
        response.results().stream()
            .map(
                result ->
                    result.withReadiness(
                        skuReadinessService.sanitizeForCatalogViewer(result.readiness(), false)))
            .toList(),
        response.errors().stream()
            .map(
                error -> {
                  SkuReadinessDto sanitizedReadiness =
                      skuReadinessService.sanitizeForCatalogViewer(error.readiness(), false);
                  return new OpeningStockImportResponse.ImportError(
                      error.rowNumber(),
                      sanitizeErrorMessage(error.message(), error.sku(), sanitizedReadiness),
                      error.sku(),
                      error.stockType(),
                      sanitizedReadiness);
                })
            .toList());
  }

  private String sanitizeErrorMessage(
      String message, String sku, SkuReadinessDto sanitizedReadiness) {
    String stage = stageFromOpeningStockErrorMessage(message);
    if (!StringUtils.hasText(stage) || sanitizedReadiness == null) {
      return message;
    }
    List<String> blockers = blockersForStage(sanitizedReadiness, stage);
    String blockerText =
        blockers == null || blockers.isEmpty() ? "UNKNOWN" : String.join(", ", blockers);
    String effectiveSku = StringUtils.hasText(sku) ? sku : sanitizedReadiness.sku();
    if (!StringUtils.hasText(effectiveSku)) {
      return message;
    }
    return "SKU " + effectiveSku + " is not " + stage + "-ready for opening stock: " + blockerText;
  }

  private String stageFromOpeningStockErrorMessage(String message) {
    if (!StringUtils.hasText(message) || !message.contains("ready for opening stock:")) {
      return null;
    }
    if (message.contains("not catalog-ready")) {
      return "catalog";
    }
    if (message.contains("not inventory-ready")) {
      return "inventory";
    }
    if (message.contains("not production-ready")) {
      return "production";
    }
    if (message.contains("not sales-ready")) {
      return "sales";
    }
    return null;
  }

  private List<String> blockersForStage(SkuReadinessDto readiness, String stage) {
    return switch (stage) {
      case "catalog" -> readiness.catalog() == null ? List.of() : readiness.catalog().blockers();
      case "inventory" ->
          readiness.inventory() == null ? List.of() : readiness.inventory().blockers();
      case "production" ->
          readiness.production() == null ? List.of() : readiness.production().blockers();
      case "sales" -> readiness.sales() == null ? List.of() : readiness.sales().blockers();
      default -> List.of();
    };
  }

  private boolean canViewAccountingMetadata(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(grantedAuthority -> grantedAuthority.getAuthority())
        .anyMatch(
            authority -> "ROLE_ADMIN".equals(authority) || "ROLE_ACCOUNTING".equals(authority));
  }

  private ApplicationException sanitizeImportException(ApplicationException ex) {
    if (ex == null || !isAccountingSensitiveImportFailure(ex.getUserMessage())) {
      return ex;
    }
    return new ApplicationException(
        ex.getErrorCode(),
        "Opening stock import requires accounting configuration to be completed before retrying.");
  }

  private boolean isAccountingSensitiveImportFailure(String message) {
    if (!StringUtils.hasText(message)) {
      return false;
    }
    String normalized = message.trim().toUpperCase(Locale.ROOT);
    return normalized.contains("OPEN-BAL")
        || normalized.contains("INVENTORY ACCOUNT")
        || normalized.contains("VALUATION ACCOUNT")
        || normalized.contains("COGS ACCOUNT")
        || normalized.contains("REVENUE ACCOUNT")
        || normalized.contains("TAX ACCOUNT")
        || normalized.contains("GST OUTPUT ACCOUNT")
        || normalized.contains("DISCOUNT ACCOUNT")
        || normalized.contains("WIP ACCOUNT")
        || normalized.contains("LABOR APPLIED ACCOUNT")
        || normalized.contains("OVERHEAD APPLIED ACCOUNT");
  }
}
