package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounting/catalog")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class AccountingCatalogController {

    private final ProductionCatalogService productionCatalogService;

    public AccountingCatalogController(ProductionCatalogService productionCatalogService) {
        this.productionCatalogService = productionCatalogService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CatalogImportResponse>> importCatalog(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(ApiResponse.success("Catalog import processed",
                productionCatalogService.importCatalog(file, idempotencyKey)));
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<ProductionProductDto>>> listProducts() {
        return ResponseEntity.ok(ApiResponse.success(productionCatalogService.listProducts()));
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductionProductDto>> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Product created", productionCatalogService.createProduct(request)));
    }

    @PostMapping("/products/bulk-variants")
    public ResponseEntity<ApiResponse<BulkVariantResponse>> createVariants(@Valid @RequestBody BulkVariantRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Variants processed", productionCatalogService.createVariants(request)));
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductionProductDto>> updateProduct(@PathVariable Long id,
                                                                           @RequestBody ProductUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Product updated", productionCatalogService.updateProduct(id, request)));
    }
}
