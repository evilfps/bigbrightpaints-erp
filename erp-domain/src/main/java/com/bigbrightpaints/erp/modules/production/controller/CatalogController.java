package com.bigbrightpaints.erp.modules.production.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemRequest;
import com.bigbrightpaints.erp.modules.production.service.CatalogService;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/catalog")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES','ROLE_FACTORY')")
public class CatalogController {

  private final CatalogService catalogService;
  private final ProductionCatalogService productionCatalogService;

  public CatalogController(
      CatalogService catalogService, ProductionCatalogService productionCatalogService) {
    this.catalogService = catalogService;
    this.productionCatalogService = productionCatalogService;
  }

  @PostMapping("/brands")
  public ResponseEntity<ApiResponse<CatalogBrandDto>> createBrand(
      @Valid @RequestBody CatalogBrandRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Brand created", catalogService.createBrand(request)));
  }

  @GetMapping("/brands")
  public ResponseEntity<ApiResponse<List<CatalogBrandDto>>> listBrands(
      @RequestParam(value = "active", required = false) Boolean active) {
    return ResponseEntity.ok(ApiResponse.success(catalogService.listBrands(active)));
  }

  @GetMapping("/brands/{brandId}")
  public ResponseEntity<ApiResponse<CatalogBrandDto>> getBrand(@PathVariable Long brandId) {
    return ResponseEntity.ok(ApiResponse.success(catalogService.getBrand(brandId)));
  }

  @PutMapping("/brands/{brandId}")
  public ResponseEntity<ApiResponse<CatalogBrandDto>> updateBrand(
      @PathVariable Long brandId, @Valid @RequestBody CatalogBrandRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Brand updated", catalogService.updateBrand(brandId, request)));
  }

  @DeleteMapping("/brands/{brandId}")
  public ResponseEntity<ApiResponse<CatalogBrandDto>> deactivateBrand(@PathVariable Long brandId) {
    return ResponseEntity.ok(
        ApiResponse.success("Brand deactivated", catalogService.deactivateBrand(brandId)));
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<CatalogImportResponse>> importCatalog(
      @RequestPart("file") MultipartFile file,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    rejectLegacyIdempotencyHeader(request.getHeader("X-Idempotency-Key"));
    return ResponseEntity.ok(
        ApiResponse.success(
            "Catalog import processed",
            productionCatalogService.importCatalog(file, idempotencyKey)));
  }

  @PostMapping("/items")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<CatalogItemDto>> createItem(
      @Valid @RequestBody CatalogItemRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Item created", catalogService.createItem(request)));
  }

  @GetMapping("/items")
  public ResponseEntity<ApiResponse<PageResponse<CatalogItemDto>>> searchItems(
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "itemClass", required = false) String itemClass,
      @RequestParam(value = "includeStock", defaultValue = "false") boolean includeStock,
      @RequestParam(value = "includeReadiness", defaultValue = "false") boolean includeReadiness,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
      Authentication authentication) {
    return ResponseEntity.ok(
        ApiResponse.success(
            catalogService.searchItems(
                q,
                itemClass,
                includeStock && canViewStock(authentication),
                includeReadiness,
                page,
                pageSize,
                canViewAccountingMetadata(authentication))));
  }

  @GetMapping("/items/{itemId}")
  public ResponseEntity<ApiResponse<CatalogItemDto>> getItem(
      @PathVariable Long itemId,
      @RequestParam(value = "includeStock", defaultValue = "true") boolean includeStock,
      @RequestParam(value = "includeReadiness", defaultValue = "true") boolean includeReadiness,
      Authentication authentication) {
    return ResponseEntity.ok(
        ApiResponse.success(
            catalogService.getItem(
                itemId,
                includeStock && canViewStock(authentication),
                includeReadiness,
                canViewAccountingMetadata(authentication))));
  }

  @PutMapping("/items/{itemId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<CatalogItemDto>> updateItem(
      @PathVariable Long itemId, @Valid @RequestBody CatalogItemRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Item updated", catalogService.updateItem(itemId, request)));
  }

  @DeleteMapping("/items/{itemId}")
  public ResponseEntity<ApiResponse<CatalogItemDto>> deactivateItem(@PathVariable Long itemId) {
    return ResponseEntity.ok(
        ApiResponse.success("Item deactivated", catalogService.deactivateItem(itemId)));
  }

  private boolean canViewStock(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(grantedAuthority -> grantedAuthority.getAuthority())
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority)
            || "ROLE_ACCOUNTING".equals(authority)
            || "ROLE_FACTORY".equals(authority));
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

  private void rejectLegacyIdempotencyHeader(String legacyIdempotencyKey) {
    if (!StringUtils.hasText(legacyIdempotencyKey)) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "X-Idempotency-Key is not supported for catalog import; use Idempotency-Key")
        .withDetail("legacyHeader", "X-Idempotency-Key")
        .withDetail("canonicalHeader", "Idempotency-Key")
        .withDetail("canonicalPath", "/api/v1/catalog/import");
  }
}
