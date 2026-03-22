package com.bigbrightpaints.erp.modules.production.controller;

import com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductEntryResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import com.bigbrightpaints.erp.modules.production.service.CatalogService;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES','ROLE_FACTORY')")
public class CatalogController {

    private final CatalogService catalogService;
    private final ProductionCatalogService productionCatalogService;

    public CatalogController(CatalogService catalogService,
                             ProductionCatalogService productionCatalogService) {
        this.catalogService = catalogService;
        this.productionCatalogService = productionCatalogService;
    }

    @PostMapping("/brands")
    public ResponseEntity<ApiResponse<CatalogBrandDto>> createBrand(@Valid @RequestBody CatalogBrandRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Brand created", catalogService.createBrand(request)));
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
    public ResponseEntity<ApiResponse<CatalogBrandDto>> updateBrand(@PathVariable Long brandId,
                                                                     @Valid @RequestBody CatalogBrandRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Brand updated", catalogService.updateBrand(brandId, request)));
    }

    @DeleteMapping("/brands/{brandId}")
    public ResponseEntity<ApiResponse<CatalogBrandDto>> deactivateBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(ApiResponse.success("Brand deactivated", catalogService.deactivateBrand(brandId)));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CatalogImportResponse>> importCatalog(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        String resolvedKey = IdempotencyHeaderUtils.resolveHeaderKey(idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Catalog import processed",
                productionCatalogService.importCatalog(file, resolvedKey)));
    }

    @PostMapping("/products")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CatalogProductEntryResponse>> createProduct(
            @Valid @RequestBody CatalogProductEntryRequest request,
            @RequestParam(value = "preview", defaultValue = "false") boolean preview) {
        String message = preview ? "Product preview generated" : "Products created";
        return ResponseEntity.ok(ApiResponse.success(message,
                productionCatalogService.createOrPreviewCatalogProducts(request, preview)));
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<PageResponse<CatalogProductDto>>> searchProducts(
            @RequestParam(value = "brandId", required = false) Long brandId,
            @RequestParam(value = "color", required = false) String color,
            @RequestParam(value = "size", required = false) String size,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(catalogService.searchProducts(
                brandId,
                color,
                size,
                active,
                page,
                pageSize,
                canViewAccountingMetadata(authentication))));
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<CatalogProductDto>> getProduct(@PathVariable Long productId,
                                                                     Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                catalogService.getProduct(productId, canViewAccountingMetadata(authentication))));
    }

    @PutMapping("/products/{productId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CatalogProductDto>> updateProduct(@PathVariable Long productId,
                                                                        @Valid @RequestBody CatalogProductRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Product updated", catalogService.updateProduct(productId, request)));
    }

    @DeleteMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<CatalogProductDto>> deactivateProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success("Product deactivated", catalogService.deactivateProduct(productId)));
    }

    private boolean canViewAccountingMetadata(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "ROLE_ACCOUNTING".equals(authority));
    }
}
