package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogServiceProductCrudTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private ProductionBrandRepository brandRepository;
    @Mock private ProductionProductRepository productRepository;
    @Mock private SizeVariantRepository sizeVariantRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private SkuReadinessService skuReadinessService;

    private CatalogService service;
    private Company company;
    private ProductionBrand brand;

    @BeforeEach
    void setUp() {
        service = new CatalogService(
                companyContextService,
                companyEntityLookup,
                brandRepository,
                productRepository,
                sizeVariantRepository,
                finishedGoodRepository,
                rawMaterialRepository,
                skuReadinessService);
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 200L);
        company.setCode("BBP");
        company.setDefaultInventoryAccountId(9001L);
        company.setDefaultCogsAccountId(9002L);
        company.setDefaultRevenueAccountId(9003L);
        company.setDefaultDiscountAccountId(9005L);
        company.setDefaultTaxAccountId(9004L);

        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setCompany(company);
        brand.setName("BigBright");
        brand.setCode("BBR");
        brand.setActive(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyEntityLookup.requireAccount(eq(company), anyLong())).thenAnswer(invocation -> {
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", invocation.getArgument(1, Long.class));
            return account;
        });
        lenient().when(finishedGoodRepository.findByCompanyAndProductCode(any(), any())).thenReturn(Optional.empty());
        lenient().when(rawMaterialRepository.findByCompanyAndSku(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void createProduct_withAllAttributes_generatesSkuAndStoresMappings() {
        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Exterior Emulsion",
                List.of("Red", "Blue"),
                List.of("1L", "4L"),
                List.of(
                        new CatalogProductCartonSizeRequest("1L", 24),
                        new CatalogProductCartonSizeRequest("4L", 6)
                ),
                "LITER",
                "320910",
                new BigDecimal("999.00"),
                new BigDecimal("18.00"),
                new BigDecimal("5.00"),
                new BigDecimal("949.00"),
                Map.of("productType", "decorative", "wipAccountId", 700L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Exterior Emulsion")).thenReturn(Optional.empty());
        when(productRepository.findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(company, "BBR-EXTERIOREMUL")
        ).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndSkuCode(company, "BBR-EXTERIOREMUL-001")).thenReturn(Optional.empty());
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> {
            ProductionProduct saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 501L);
            return saved;
        });

        CatalogProductDto response = service.createProduct(request);

        assertThat(response.id()).isEqualTo(501L);
        assertThat(response.name()).isEqualTo("Exterior Emulsion");
        assertThat(response.brandId()).isEqualTo(11L);
        assertThat(response.sku()).isEqualTo("BBR-EXTERIOREMUL-001");
        assertThat(response.colors()).containsExactly("Red", "Blue");
        assertThat(response.sizes()).containsExactly("1L", "4L");
        assertThat(response.cartonSizes())
                .extracting(mapping -> mapping.size() + ":" + mapping.piecesPerCarton())
                .containsExactly("1L:24", "4L:6");
        assertThat(response.unitOfMeasure()).isEqualTo("LITER");
        assertThat(response.hsnCode()).isEqualTo("320910");
        assertThat(response.basePrice()).isEqualByComparingTo("999.00");
        assertThat(response.gstRate()).isEqualByComparingTo("18.00");
        assertThat(response.minDiscountPercent()).isEqualByComparingTo("5.00");
        assertThat(response.minSellingPrice()).isEqualByComparingTo("949.00");
        assertThat(response.metadata())
                .containsEntry("productType", "decorative")
                .containsEntry("wipAccountId", 700L);
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateProduct_updatesFullCatalogAttributes() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 502L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Old Name");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-OLDNAME-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 12)));
        existing.setBasePrice(new BigDecimal("600.00"));
        existing.setMinDiscountPercent(new BigDecimal("2.50"));
        existing.setMinSellingPrice(new BigDecimal("575.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of("legacyFlag", "keep")));
        existing.setVariantGroupId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        existing.setProductFamilyName("Old Name");

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Interior Emulsion",
                List.of("Ivory"),
                List.of("10L"),
                List.of(new CatalogProductCartonSizeRequest("10L", 2)),
                "LITER",
                "320990",
                new BigDecimal("820.00"),
                new BigDecimal("12.00"),
                new BigDecimal("6.00"),
                new BigDecimal("790.00"),
                Map.of("productType", "decorative", "wipAccountId", 701L, "wastageAccountId", 702L),
                false
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 502L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Interior Emulsion")).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(502L, request);
        UUID expectedVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                company,
                brand,
                "Interior Emulsion",
                "FINISHED_GOOD",
                "LITER",
                "320990");

        assertThat(response.id()).isEqualTo(502L);
        assertThat(response.name()).isEqualTo("Interior Emulsion");
        assertThat(response.sku()).isEqualTo("BBR-OLDNAME-001");
        assertThat(response.productFamilyName()).isEqualTo("Interior Emulsion");
        assertThat(response.variantGroupId()).isEqualTo(expectedVariantGroupId);
        assertThat(response.variantGroupId()).isNotEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThat(response.colors()).containsExactly("Ivory");
        assertThat(response.sizes()).containsExactly("10L");
        assertThat(response.cartonSizes())
                .extracting(mapping -> mapping.size() + ":" + mapping.piecesPerCarton())
                .containsExactly("10L:2");
        assertThat(response.basePrice()).isEqualByComparingTo("820.00");
        assertThat(response.hsnCode()).isEqualTo("320990");
        assertThat(response.gstRate()).isEqualByComparingTo("12.00");
        assertThat(response.minDiscountPercent()).isEqualByComparingTo("6.00");
        assertThat(response.minSellingPrice()).isEqualByComparingTo("790.00");
        assertThat(response.metadata())
                .containsEntry("productType", "decorative")
                .containsEntry("wipAccountId", 701L)
                .containsEntry("wastageAccountId", 702L);
        assertThat(response.active()).isFalse();
    }

    @Test
    void updateProduct_preservesExistingPricingAndMetadata_whenOptionalFieldsAreOmitted() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 503L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setSkuCode("BBR-PRIMER-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 12)));
        existing.setBasePrice(new BigDecimal("710.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMinDiscountPercent(new BigDecimal("4.00"));
        existing.setMinSellingPrice(new BigDecimal("690.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of("wipAccountId", 801L, "productType", "decorative")));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Primer Updated",
                List.of("White"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 12)),
                "LITER",
                "320910",
                null,
                new BigDecimal("18.00"),
                null,
                null,
                null,
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 503L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer Updated")).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(503L, request);

        assertThat(response.basePrice()).isEqualByComparingTo("710.00");
        assertThat(response.minDiscountPercent()).isEqualByComparingTo("4.00");
        assertThat(response.minSellingPrice()).isEqualByComparingTo("690.00");
        assertThat(response.metadata())
                .containsEntry("wipAccountId", 801L)
                .containsEntry("productType", "decorative");
    }

    @Test
    void updateProduct_mergesMetadataWithoutDroppingFinishedGoodPostingAccounts() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 504L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PRIMER-002");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 12)));
        existing.setBasePrice(new BigDecimal("710.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMinDiscountPercent(new BigDecimal("4.00"));
        existing.setMinSellingPrice(new BigDecimal("690.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of(
                "fgValuationAccountId", 9001L,
                "fgCogsAccountId", 9002L,
                "fgRevenueAccountId", 9003L,
                "fgTaxAccountId", 9004L,
                "productType", "decorative")));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Primer Updated",
                List.of("White"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 12)),
                "LITER",
                "320910",
                null,
                new BigDecimal("18.00"),
                null,
                null,
                Map.of("wipAccountId", 801L, "wastageAccountId", 802L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 504L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer Updated")).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(504L, request);

        assertThat(response.metadata())
                .containsEntry("fgValuationAccountId", 9001L)
                .containsEntry("fgCogsAccountId", 9002L)
                .containsEntry("fgRevenueAccountId", 9003L)
                .containsEntry("fgTaxAccountId", 9004L)
                .containsEntry("wipAccountId", 801L)
                .containsEntry("wastageAccountId", 802L)
                .containsEntry("productType", "decorative");
    }

    @Test
    void updateProduct_preservesCanonicalFamilyLinkage_whenMemberNameIsUnchanged() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 505L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Premium Primer WHITE 1L");
        existing.setProductFamilyName("Premium Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PREMIUMPRIM-WHITE-1L");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("WHITE")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 1)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320910");
        UUID variantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                company,
                brand,
                "Premium Primer",
                "FINISHED_GOOD",
                "LITER",
                "320910");
        existing.setVariantGroupId(variantGroupId);
        existing.setMetadata(new LinkedHashMap<>(Map.of("productType", "decorative")));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Premium Primer WHITE 1L",
                List.of("WHITE"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 1)),
                "LITER",
                "320910",
                new BigDecimal("1325.00"),
                new BigDecimal("18.00"),
                new BigDecimal("7.50"),
                new BigDecimal("1225.00"),
                Map.of("wipAccountId", 801L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 505L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Premium Primer WHITE 1L"))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(505L, request);

        assertThat(response.name()).isEqualTo("Premium Primer WHITE 1L");
        assertThat(response.productFamilyName()).isEqualTo("Premium Primer");
        assertThat(response.variantGroupId()).isEqualTo(variantGroupId);
    }

    @Test
    void updateProduct_recomputesCanonicalFamilyLinkage_whenMemberSuffixIsRetained() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 507L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Premium Primer WHITE 1L");
        existing.setProductFamilyName("Premium Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PREMIUMPRIM-WHITE-1L");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("WHITE")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 1)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320910");
        UUID originalVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                company,
                brand,
                "Premium Primer",
                "FINISHED_GOOD",
                "LITER",
                "320910");
        existing.setVariantGroupId(originalVariantGroupId);
        existing.setMetadata(new LinkedHashMap<>(Map.of("productType", "decorative")));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Premium Primer Updated WHITE 1L",
                List.of("WHITE"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 1)),
                "LITER",
                "320910",
                new BigDecimal("1325.00"),
                new BigDecimal("18.00"),
                new BigDecimal("7.50"),
                new BigDecimal("1225.00"),
                Map.of("wipAccountId", 801L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 507L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Premium Primer Updated WHITE 1L"))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(507L, request);
        UUID updatedVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                company,
                brand,
                "Premium Primer Updated",
                "FINISHED_GOOD",
                "LITER",
                "320910");

        assertThat(response.name()).isEqualTo("Premium Primer Updated WHITE 1L");
        assertThat(response.productFamilyName()).isEqualTo("Premium Primer Updated");
        assertThat(response.variantGroupId()).isEqualTo(updatedVariantGroupId);
        assertThat(response.variantGroupId()).isNotEqualTo(originalVariantGroupId);
    }

    @Test
    void updateProduct_rejectsInvalidFinishedGoodAccountMetadataBeforeInventorySync() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 506L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setProductFamilyName("Primer");
        existing.setVariantGroupId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PRIMER-003");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 12)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320910");
        existing.setMetadata(new LinkedHashMap<>(Map.of("productType", "decorative")));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Primer",
                List.of("White"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 12)),
                "LITER",
                "320910",
                new BigDecimal("710.00"),
                new BigDecimal("18.00"),
                new BigDecimal("4.00"),
                new BigDecimal("690.00"),
                Map.of("fgValuationAccountId", 999L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 506L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer")).thenReturn(Optional.of(existing));
        when(companyEntityLookup.requireAccount(company, 999L)).thenThrow(new IllegalArgumentException("Account not found"));

        assertThatThrownBy(() -> service.updateProduct(506L, request))
                .hasMessageContaining("invalid account id 999")
                .hasMessageContaining("fgValuationAccountId");
    }

    @Test
    void helperMethods_coverNullEmptyAndInvalidOptionalPayloadBranches() {
        Long nullMetadataValue = ReflectionTestUtils.invokeMethod(
                service,
                "metadataLong",
                (Map<String, Object>) null,
                "wipAccountId");
        Long blankMetadataValue = ReflectionTestUtils.invokeMethod(
                service,
                "metadataLong",
                Map.of("wipAccountId", "   "),
                "wipAccountId");
        assertThat(nullMetadataValue).isNull();
        assertThat(blankMetadataValue).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedEmpty = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "normalizeMetadata",
                Map.of());
        assertThat(normalizedEmpty).isEmpty();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeOptionalRate", new BigDecimal("101.00")))
                .hasMessageContaining("Minimum discount percent must be between 0 and 100");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeMoney", new BigDecimal("-1.00")))
                .hasMessageContaining("Money values cannot be negative");
    }

    @Test
    void helperMethods_coverRemainingCanonicalFamilyBranches() {
        ProductionProduct canonicalProduct = new ProductionProduct();
        canonicalProduct.setCompany(company);
        canonicalProduct.setBrand(brand);
        canonicalProduct.setProductName("Premium Primer WHITE 1L");
        canonicalProduct.setProductFamilyName("Premium Primer");
        canonicalProduct.setCategory("FINISHED_GOOD");
        canonicalProduct.setUnitOfMeasure("LITER");
        canonicalProduct.setHsnCode("320910");

        ReflectionTestUtils.invokeMethod(
                service,
                "refreshCanonicalFamilyLinkage",
                canonicalProduct,
                brand,
                "Premium Primer WHITE 1L",
                "Premium Primer");

        UUID expectedVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                company,
                brand,
                "Premium Primer",
                "FINISHED_GOOD",
                "LITER",
                "320910");
        String missingPreviousFamily = ReflectionTestUtils.invokeMethod(
                service,
                "resolveCanonicalProductFamilyName",
                "Premium Primer WHITE 1L",
                "Legacy Primer WHITE 1L",
                null);
        String blankDerivedFamilyFallback = ReflectionTestUtils.invokeMethod(
                service,
                "resolveCanonicalProductFamilyName",
                " WHITE 1L",
                "Premium Primer WHITE 1L",
                "Premium Primer");
        String missingNameSuffix = ReflectionTestUtils.invokeMethod(
                service,
                "extractCanonicalMemberSuffix",
                (String) null,
                "Premium Primer");
        String missingFamilySuffix = ReflectionTestUtils.invokeMethod(
                service,
                "extractCanonicalMemberSuffix",
                "Premium Primer WHITE 1L",
                "   ");
        String mismatchedFamilySuffix = ReflectionTestUtils.invokeMethod(
                service,
                "extractCanonicalMemberSuffix",
                "Other WHITE 1L",
                "Premium Primer");

        assertThat(canonicalProduct.getProductFamilyName()).isEqualTo("Premium Primer");
        assertThat(canonicalProduct.getVariantGroupId()).isEqualTo(expectedVariantGroupId);
        assertThat(missingPreviousFamily).isEqualTo("Premium Primer WHITE 1L");
        assertThat(blankDerivedFamilyFallback).isEqualTo("WHITE 1L");
        assertThat(missingNameSuffix).isNull();
        assertThat(missingFamilySuffix).isNull();
        assertThat(mismatchedFamilySuffix).isNull();
    }

    @Test
    void helperMethods_coverRawMaterialInventoryValidationBranches() {
        ProductionProduct rawMaterial = new ProductionProduct();
        rawMaterial.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 7001L)));

        ReflectionTestUtils.invokeMethod(service, "validateInventorySyncMetadata", (Company) null, rawMaterial);
        ReflectionTestUtils.invokeMethod(service, "validateInventorySyncMetadata", company, (ProductionProduct) null);

        @SuppressWarnings("unchecked")
        Map<String, Object> unchangedMetadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "validateInventorySyncMetadata",
                company,
                "RAW_MATERIAL",
                "RM-001",
                Map.of("productType", "base"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inventoryKeyMetadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "validateInventorySyncMetadata",
                company,
                "RAW_MATERIAL",
                "RM-002",
                new LinkedHashMap<>(Map.of("inventoryAccountId", 7001L)));
        @SuppressWarnings("unchecked")
        Map<String, Object> rawKeyMetadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "validateInventorySyncMetadata",
                company,
                "RAW_MATERIAL",
                "RM-003",
                new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", 7002L)));
        Long nullInventoryAccountId = ReflectionTestUtils.invokeMethod(
                service,
                "requireRawMaterialInventoryAccount",
                company,
                0L,
                "RM-004");

        when(companyEntityLookup.requireAccount(company, 999L)).thenThrow(new IllegalArgumentException("Account not found"));

        assertThat(rawMaterial.getMetadata()).containsEntry("inventoryAccountId", 7001L);
        assertThat(unchangedMetadata).containsEntry("productType", "base");
        assertThat(inventoryKeyMetadata)
                .containsEntry("inventoryAccountId", 7001L)
                .doesNotContainKey("rawMaterialInventoryAccountId");
        assertThat(rawKeyMetadata)
                .containsEntry("rawMaterialInventoryAccountId", 7002L)
                .doesNotContainKey("inventoryAccountId");
        assertThat(nullInventoryAccountId).isNull();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "requireRawMaterialInventoryAccount",
                company,
                999L,
                "RM-999"))
                .hasMessageContaining("invalid inventory account id 999");
    }

    @Test
    void helperMethods_coverFinishedGoodAccountValidationBranches() {
        Company companyWithoutTaxDefault = new Company();
        companyWithoutTaxDefault.setCode("NO-TAX");
        companyWithoutTaxDefault.setDefaultInventoryAccountId(9001L);
        companyWithoutTaxDefault.setDefaultCogsAccountId(9002L);
        companyWithoutTaxDefault.setDefaultRevenueAccountId(9003L);
        companyWithoutTaxDefault.setDefaultDiscountAccountId(9005L);
        companyWithoutTaxDefault.setDefaultTaxAccountId(null);

        Account remappedDiscountAccount = new Account();
        ReflectionTestUtils.setField(remappedDiscountAccount, "id", 9905L);
        when(companyEntityLookup.requireAccount(company, 7007L)).thenReturn(remappedDiscountAccount);

        @SuppressWarnings("unchecked")
        Map<String, Object> remappedFinishedGoodMetadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "ensureFinishedGoodAccounts",
                company,
                "FG-002",
                new LinkedHashMap<>(Map.of(
                        "fgValuationAccountId", 9001L,
                        "fgCogsAccountId", 9002L,
                        "fgRevenueAccountId", 9003L,
                        "fgTaxAccountId", 9004L,
                        "fgDiscountAccountId", 7007L)));
        Long missingFinishedGoodAccountId = ReflectionTestUtils.invokeMethod(
                service,
                "requireFinishedGoodAccount",
                company,
                0L,
                "FG-003",
                "fgTaxAccountId");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "ensureFinishedGoodAccounts",
                companyWithoutTaxDefault,
                "FG-001",
                Map.of("productType", "decorative")))
                .hasMessageContaining("Default fgTaxAccountId is not configured for company NO-TAX");
        assertThat(remappedFinishedGoodMetadata).containsEntry("fgDiscountAccountId", 9905L);
        assertThat(missingFinishedGoodAccountId).isNull();
    }

    @Test
    void helperMethods_coverHasLongValueAndNegativeRateBranches() {
        Boolean positiveNumber = ReflectionTestUtils.invokeMethod(service, "hasLongValue", 5L);
        Boolean zeroNumber = ReflectionTestUtils.invokeMethod(service, "hasLongValue", 0L);
        Boolean positiveString = ReflectionTestUtils.invokeMethod(service, "hasLongValue", " 17 ");
        Boolean zeroString = ReflectionTestUtils.invokeMethod(service, "hasLongValue", " 0 ");
        Boolean blankString = ReflectionTestUtils.invokeMethod(service, "hasLongValue", "   ");
        Boolean invalidString = ReflectionTestUtils.invokeMethod(service, "hasLongValue", "abc");

        assertThat(positiveNumber).isTrue();
        assertThat(zeroNumber).isFalse();
        assertThat(positiveString).isTrue();
        assertThat(zeroString).isFalse();
        assertThat(blankString).isFalse();
        assertThat(invalidString).isFalse();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeOptionalRate", new BigDecimal("-0.01")))
                .hasMessageContaining("Minimum discount percent must be between 0 and 100");
    }

    @Test
    void deactivateProduct_hidesAccountingMetadataOnPublicResponse() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 601L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PRIMER-DELETE");
        existing.setActive(true);
        existing.setMetadata(new LinkedHashMap<>(Map.of(
                "productType", "decorative",
                "wipAccountId", 801L,
                "fgTaxAccountId", 9004L)));

        when(productRepository.findByCompanyAndId(company, 601L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.deactivateProduct(601L);

        assertThat(response.active()).isFalse();
        assertThat(response.metadata())
                .containsEntry("productType", "decorative")
                .doesNotContainKeys("wipAccountId", "fgTaxAccountId");
    }

    @Test
    void searchProducts_returnsPaginatedFilteredResponse() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 701L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName("Primer");
        product.setCategory("FINISHED_GOOD");
        product.setSkuCode("BBR-PRIMER-001");
        product.setDefaultColour("Red");
        product.setSizeLabel("20L");
        product.setVariantGroupId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        product.setProductFamilyName("Primer");
        product.setActive(true);
        product.setColors(new LinkedHashSet<>(List.of("Red")));
        product.setSizes(new LinkedHashSet<>(List.of("20L")));
        product.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("20L", 1)));
        product.setUnitOfMeasure("LITER");
        product.setHsnCode("320810");
        product.setBasePrice(new BigDecimal("799.00"));
        product.setGstRate(new BigDecimal("18.00"));
        product.setMinDiscountPercent(new BigDecimal("5.00"));
        product.setMinSellingPrice(new BigDecimal("760.00"));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productType", "decorative");
        metadata.put("legacyFlag", null);
        metadata.put("wipAccountId", 801L);
        metadata.put("fgValuationAccountId", 9001L);
        product.setMetadata(metadata);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(productRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(product), org.springframework.data.domain.PageRequest.of(1, 5), 11));

        PageResponse<CatalogProductDto> response = service.searchProducts(11L, "red", "20l", true, 1, 5);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(11);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).sku()).isEqualTo("BBR-PRIMER-001");
        assertThat(response.content().get(0).category()).isEqualTo("FINISHED_GOOD");
        assertThat(response.content().get(0).variantGroupId())
                .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(response.content().get(0).productFamilyName()).isEqualTo("Primer");
        assertThat(response.content().get(0).basePrice()).isEqualByComparingTo("799.00");
        assertThat(response.content().get(0).minDiscountPercent()).isEqualByComparingTo("5.00");
        assertThat(response.content().get(0).minSellingPrice()).isEqualByComparingTo("760.00");
        assertThat(response.content().get(0).metadata()).containsEntry("productType", "decorative");
        assertThat(response.content().get(0).metadata()).containsEntry("legacyFlag", null);
        assertThat(response.content().get(0).metadata()).doesNotContainKeys("wipAccountId", "fgValuationAccountId");
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("productName")).isNotNull();
    }
}
