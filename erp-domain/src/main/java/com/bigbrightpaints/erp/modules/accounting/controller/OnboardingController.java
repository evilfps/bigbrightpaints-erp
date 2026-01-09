package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingAccountSuggestionsResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingOpeningStockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingOpeningStockResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingPartnerOpeningBalanceRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingPartnerOpeningBalanceResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OnboardingRawMaterialRequest;
import com.bigbrightpaints.erp.modules.accounting.service.OnboardingService;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionBrandRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionCategoryDto;
import com.bigbrightpaints.erp.modules.production.dto.ProductionCategoryRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting/onboarding")
@PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('portal:accounting') and hasAuthority('onboarding.manage')")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/brands")
    public ResponseEntity<ApiResponse<List<ProductionBrandDto>>> listBrands() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listBrands()));
    }

    @PostMapping("/brands")
    public ResponseEntity<ApiResponse<ProductionBrandDto>> upsertBrand(@Valid @RequestBody ProductionBrandRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Brand saved", onboardingService.upsertBrand(request)));
    }

    @PutMapping("/brands/{brandId}")
    public ResponseEntity<ApiResponse<ProductionBrandDto>> updateBrand(@PathVariable Long brandId,
                                                                       @Valid @RequestBody ProductionBrandRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Brand updated", onboardingService.updateBrand(brandId, request)));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<ProductionCategoryDto>>> listCategories() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listCategories()));
    }

    @PostMapping("/categories")
    public ResponseEntity<ApiResponse<ProductionCategoryDto>> upsertCategory(
            @Valid @RequestBody ProductionCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Category saved", onboardingService.upsertCategory(request)));
    }

    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<ApiResponse<ProductionCategoryDto>> updateCategory(@PathVariable Long categoryId,
                                                                             @Valid @RequestBody ProductionCategoryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Category updated", onboardingService.updateCategory(categoryId, request)));
    }

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<List<ProductionProductDto>>> listProducts() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listProducts()));
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductionProductDto>> upsertProduct(@Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Product saved", onboardingService.upsertProduct(request)));
    }

    @PutMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductionProductDto>> updateProduct(@PathVariable Long productId,
                                                                           @RequestBody ProductUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Product updated", onboardingService.updateProduct(productId, request)));
    }

    @PostMapping("/products/variants")
    public ResponseEntity<ApiResponse<BulkVariantResponse>> createVariants(@Valid @RequestBody BulkVariantRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Variants processed", onboardingService.createVariants(request)));
    }

    @GetMapping("/raw-materials")
    public ResponseEntity<ApiResponse<List<RawMaterialDto>>> listRawMaterials() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listRawMaterials()));
    }

    @PostMapping("/raw-materials")
    public ResponseEntity<ApiResponse<RawMaterialDto>> upsertRawMaterial(
            @Valid @RequestBody OnboardingRawMaterialRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Raw material saved", onboardingService.upsertRawMaterial(request)));
    }

    @PutMapping("/raw-materials/{id}")
    public ResponseEntity<ApiResponse<RawMaterialDto>> updateRawMaterial(@PathVariable Long id,
                                                                         @Valid @RequestBody OnboardingRawMaterialRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Raw material updated", onboardingService.updateRawMaterial(id, request)));
    }

    @GetMapping("/suppliers")
    public ResponseEntity<ApiResponse<List<SupplierResponse>>> listSuppliers() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listSuppliers()));
    }

    @PostMapping("/suppliers")
    public ResponseEntity<ApiResponse<SupplierResponse>> upsertSupplier(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Supplier saved", onboardingService.upsertSupplier(request)));
    }

    @PutMapping("/suppliers/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> updateSupplier(@PathVariable Long id,
                                                                        @Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Supplier updated", onboardingService.updateSupplier(id, request)));
    }

    @GetMapping("/dealers")
    public ResponseEntity<ApiResponse<List<DealerResponse>>> listDealers() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.listDealers()));
    }

    @GetMapping("/account-suggestions")
    public ResponseEntity<ApiResponse<OnboardingAccountSuggestionsResponse>> accountSuggestions() {
        return ResponseEntity.ok(ApiResponse.success(onboardingService.accountSuggestions()));
    }

    @PostMapping("/dealers")
    public ResponseEntity<ApiResponse<DealerResponse>> upsertDealer(@Valid @RequestBody CreateDealerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Dealer saved", onboardingService.upsertDealer(request)));
    }

    @PutMapping("/dealers/{dealerId}")
    public ResponseEntity<ApiResponse<DealerResponse>> updateDealer(@PathVariable Long dealerId,
                                                                    @Valid @RequestBody CreateDealerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Dealer updated", onboardingService.updateDealer(dealerId, request)));
    }

    @PostMapping("/opening-stock")
    public ResponseEntity<ApiResponse<OnboardingOpeningStockResponse>> openingStock(
            @Valid @RequestBody OnboardingOpeningStockRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Opening stock recorded", onboardingService.recordOpeningStock(request)));
    }

    @PostMapping("/opening-balances/dealers")
    public ResponseEntity<ApiResponse<OnboardingPartnerOpeningBalanceResponse>> openingReceivable(
            @Valid @RequestBody OnboardingPartnerOpeningBalanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Opening receivable recorded",
                onboardingService.recordDealerOpeningBalance(request)));
    }

    @PostMapping("/opening-balances/suppliers")
    public ResponseEntity<ApiResponse<OnboardingPartnerOpeningBalanceResponse>> openingPayable(
            @Valid @RequestBody OnboardingPartnerOpeningBalanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Opening payable recorded",
                onboardingService.recordSupplierOpeningBalance(request)));
    }
}
