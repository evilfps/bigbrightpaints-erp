package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductCreateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductUpdateRequest;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private ProductionCatalogService productionCatalogService;

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
                skuReadinessService,
                productionCatalogService);
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
        lenient().when(skuReadinessService.forProducts(eq(company), anyCollection())).thenReturn(Map.of());
        lenient().when(finishedGoodRepository.findByCompanyAndProductCode(any(), any())).thenReturn(Optional.empty());
        lenient().when(rawMaterialRepository.findByCompanyAndSku(any(), any())).thenReturn(Optional.empty());
        lenient().when(rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(any(), anyCollection())).thenReturn(List.of());
    }

    @Test
    void createProduct_withAllAttributes_generatesSkuAndStoresMappings() {
        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Exterior Emulsion",
                "FINISHED_GOOD",
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
                null,
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
                null,
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
    void updateProduct_preservesProductionItemClassForLegacyPackagingSkuWhenRequestOmitsItemClass() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 5031L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Legacy Bucket Resin");
        existing.setCategory("RAW_MATERIAL");
        existing.setSkuCode("PKG-LEGACY-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("20L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("20L", 1)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320890");
        existing.setBasePrice(new BigDecimal("710.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMinDiscountPercent(new BigDecimal("4.00"));
        existing.setMinSellingPrice(new BigDecimal("690.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", 801L)));

        RawMaterial legacyProductionMaterial = new RawMaterial();
        legacyProductionMaterial.setCompany(company);
        legacyProductionMaterial.setSku("PKG-LEGACY-001");
        legacyProductionMaterial.setMaterialType(MaterialType.PRODUCTION);

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Legacy Bucket Resin Updated",
                null,
                List.of("White"),
                List.of("20L"),
                List.of(new CatalogProductCartonSizeRequest("20L", 1)),
                "LITER",
                "320890",
                new BigDecimal("720.00"),
                new BigDecimal("18.00"),
                new BigDecimal("4.50"),
                new BigDecimal("700.00"),
                Map.of("rawMaterialInventoryAccountId", 801L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 5031L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Legacy Bucket Resin Updated"))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-LEGACY-001"))
                .thenReturn(Optional.of(legacyProductionMaterial));
        when(rawMaterialRepository.findByCompanyAndSku(company, "PKG-LEGACY-001"))
                .thenReturn(Optional.of(legacyProductionMaterial));

        CatalogProductDto response = service.updateProduct(5031L, request);

        assertThat(response.category()).isEqualTo("RAW_MATERIAL");
        assertThat(response.itemClass()).isEqualTo("RAW_MATERIAL");
        assertThat(legacyProductionMaterial.getMaterialType()).isEqualTo(MaterialType.PRODUCTION);
    }

    @Test
    void updateProduct_reclassifiesFinishedGoodAsRawMaterialAndDeletesStaleFinishedGoodMirror() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 50315L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer Base");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("FG-TO-RM-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("20L")));
        existing.setCartonSizes(new LinkedHashMap<>(Map.of("20L", 1)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320890");
        existing.setBasePrice(new BigDecimal("710.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of("productType", "decorative")));

        RawMaterial syncedMaterial = new RawMaterial();
        syncedMaterial.setCompany(company);
        syncedMaterial.setSku("FG-TO-RM-001");
        syncedMaterial.setMaterialType(MaterialType.PRODUCTION);

        FinishedGood staleFinishedGood = new FinishedGood();
        staleFinishedGood.setCompany(company);
        staleFinishedGood.setProductCode("FG-TO-RM-001");

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Primer Base Raw",
                "RAW_MATERIAL",
                List.of("White"),
                List.of("20L"),
                List.of(new CatalogProductCartonSizeRequest("20L", 1)),
                "LITER",
                "320890",
                new BigDecimal("720.00"),
                new BigDecimal("18.00"),
                null,
                null,
                Map.of("rawMaterialInventoryAccountId", 801L),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 50315L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer Base Raw"))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.findByCompanyAndSku(company, "FG-TO-RM-001")).thenReturn(Optional.empty());
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "FG-TO-RM-001"))
                .thenReturn(Optional.of(syncedMaterial));
        when(finishedGoodRepository.findByCompanyAndProductCodeIgnoreCase(company, "FG-TO-RM-001"))
                .thenReturn(Optional.of(staleFinishedGood));

        CatalogProductDto response = service.updateProduct(50315L, request);

        assertThat(response.category()).isEqualTo("RAW_MATERIAL");
        assertThat(response.itemClass()).isEqualTo("RAW_MATERIAL");
        verify(rawMaterialRepository).save(any(RawMaterial.class));
        verify(finishedGoodRepository).delete(staleFinishedGood);
    }

    @Test
    void updateProduct_reclassifiesRawMaterialAsFinishedGoodAndDeletesStaleRawMaterialMirror() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 50316L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Resin Base");
        existing.setCategory("RAW_MATERIAL");
        existing.setSkuCode("RM-TO-FG-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("20L")));
        existing.setCartonSizes(new LinkedHashMap<>(Map.of("20L", 1)));
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320890");
        existing.setBasePrice(new BigDecimal("410.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMetadata(new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", 801L)));

        RawMaterial staleRawMaterial = new RawMaterial();
        staleRawMaterial.setCompany(company);
        staleRawMaterial.setSku("RM-TO-FG-001");
        staleRawMaterial.setMaterialType(MaterialType.PRODUCTION);

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Resin Base Finish",
                "FINISHED_GOOD",
                List.of("White"),
                List.of("20L"),
                List.of(new CatalogProductCartonSizeRequest("20L", 1)),
                "LITER",
                "320890",
                new BigDecimal("510.00"),
                new BigDecimal("18.00"),
                null,
                null,
                Map.of("productType", "decorative"),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 50316L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Resin Base Finish"))
                .thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "RM-TO-FG-001")).thenReturn(Optional.empty());
        when(finishedGoodRepository.save(any(FinishedGood.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-TO-FG-001"))
                .thenReturn(Optional.of(staleRawMaterial));

        CatalogProductDto response = service.updateProduct(50316L, request);

        assertThat(response.category()).isEqualTo("FINISHED_GOOD");
        assertThat(response.itemClass()).isEqualTo("FINISHED_GOOD");
        verify(finishedGoodRepository).save(any(FinishedGood.class));
        verify(rawMaterialRepository).delete(staleRawMaterial);
    }

    @Test
    void updateProduct_rejectsUnknownItemClassValues() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 5032L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PRIMER-003");

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Primer Updated",
                "LEGACY_ITEM_CLASS",
                List.of("White"),
                List.of("1L"),
                List.of(new CatalogProductCartonSizeRequest("1L", 12)),
                "LITER",
                "320910",
                new BigDecimal("720.00"),
                new BigDecimal("18.00"),
                new BigDecimal("4.50"),
                new BigDecimal("700.00"),
                Map.of("productType", "decorative"),
                true
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 5032L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateProduct(5032L, request))
                .hasMessageContaining("itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
    }

    @Test
    void getProduct_returnsRawMaterialMirrorIdentityForPublicView() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 5033L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Bucket Lid WHITE 1L");
        existing.setCategory("RAW_MATERIAL");
        existing.setSkuCode("PKG-BBR-LID-WHITE-1L");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("WHITE")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 1)));
        existing.setUnitOfMeasure("UNIT");
        existing.setHsnCode("392310");
        existing.setMetadata(new LinkedHashMap<>(Map.of("wipAccountId", 801L)));

        RawMaterial rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 8802L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("PKG-BBR-LID-WHITE-1L");
        rawMaterial.setMaterialType(MaterialType.PACKAGING);

        when(productRepository.findByCompanyAndId(company, 5033L)).thenReturn(Optional.of(existing));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-BBR-LID-WHITE-1L"))
                .thenReturn(Optional.of(rawMaterial));

        CatalogProductDto response = service.getProduct(5033L, false);

        assertThat(response.rawMaterialId()).isEqualTo(8802L);
        assertThat(response.itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(response.metadata()).doesNotContainKey("wipAccountId");
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
                null,
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
                null,
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
                null,
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
                null,
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
                "Premium Primer",
                "FINISHED_GOOD");

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
    void helperMethods_coverPackagingItemClassResolution() {
        RawMaterial packagingMaterial = new RawMaterial();
        packagingMaterial.setMaterialType(MaterialType.PACKAGING);
        RawMaterial legacyProductionMaterial = new RawMaterial();
        legacyProductionMaterial.setMaterialType(MaterialType.PRODUCTION);
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-001")).thenReturn(Optional.of(packagingMaterial));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-LEGACY")).thenReturn(Optional.of(legacyProductionMaterial));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "RM-001")).thenReturn(Optional.empty());

        ProductionProduct packagingProduct = new ProductionProduct();
        packagingProduct.setCompany(company);
        packagingProduct.setCategory("RAW_MATERIAL");
        packagingProduct.setSkuCode("PKG-001");

        ProductionProduct legacyProductionProduct = new ProductionProduct();
        legacyProductionProduct.setCompany(company);
        legacyProductionProduct.setCategory("RAW_MATERIAL");
        legacyProductionProduct.setSkuCode("PKG-LEGACY");

        ProductionProduct rawMaterialProduct = new ProductionProduct();
        rawMaterialProduct.setCompany(company);
        rawMaterialProduct.setCategory("RAW_MATERIAL");
        rawMaterialProduct.setSkuCode("RM-001");

        ProductionProduct finishedGoodProduct = new ProductionProduct();
        finishedGoodProduct.setCategory("FINISHED_GOOD");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", new Object[]{null}))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", finishedGoodProduct))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", packagingProduct))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", legacyProductionProduct, legacyProductionMaterial))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", rawMaterialProduct))
                .isEqualTo("RAW_MATERIAL");
    }

    @Test
    void helperMethods_coverRequestedItemClassAndRawMaterialResolutionBranches() {
        ProductionProduct legacyPackagingSkuProduct = new ProductionProduct();
        legacyPackagingSkuProduct.setCompany(company);
        legacyPackagingSkuProduct.setCategory("RAW_MATERIAL");
        legacyPackagingSkuProduct.setSkuCode("PKG-LEGACY");

        RawMaterial legacyProductionMirror = new RawMaterial();
        legacyProductionMirror.setMaterialType(MaterialType.PRODUCTION);

        ProductionProduct plainRaw = new ProductionProduct();
        plainRaw.setCategory("RAW_MATERIAL");
        plainRaw.setProductName("Resin Base");
        plainRaw.setSkuCode("RM-PLAIN");

        RawMaterial productionMirror = new RawMaterial();
        productionMirror.setMaterialType(MaterialType.PRODUCTION);
        RawMaterial duplicateMirror = new RawMaterial();
        duplicateMirror.setSku("PKG-DUPLICATE");
        RawMaterial duplicateMirror2 = new RawMaterial();
        duplicateMirror2.setSku("PKG-DUPLICATE");

        ProductionProduct duplicateSkuProduct = new ProductionProduct();
        duplicateSkuProduct.setSkuCode("PKG-DUPLICATE");
        duplicateSkuProduct.setCategory("RAW_MATERIAL");

        ProductionProduct blankSkuProduct = new ProductionProduct();
        blankSkuProduct.setSkuCode("   ");
        blankSkuProduct.setCategory("RAW_MATERIAL");

        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-LEGACY"))
                .thenReturn(Optional.of(legacyProductionMirror));
        when(rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(company, List.of("pkg-duplicate")))
                .thenReturn(List.of(duplicateMirror, duplicateMirror2));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "resolveRequestedItemClass", null, null, true))
                .hasMessageContaining("itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "resolveRequestedItemClass", legacyPackagingSkuProduct, null, false))
                .isEqualTo("RAW_MATERIAL");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", (Object) null))
                .hasMessageContaining("itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "packaging"))
                .isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "production"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "categoryForItemClass", "PACKAGING_RAW_MATERIAL"))
                .isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuKey", " pkg-001 "))
                .isEqualTo("PKG-001");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuLookupKey", " pkg-001 "))
                .isEqualTo("pkg-001");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuKey", "   "))
                .isNull();
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "rawMaterialsBySku", null, List.of(legacyPackagingSkuProduct)))
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "rawMaterialsBySku", company, null))
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "rawMaterialsBySku", company, List.of()))
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "rawMaterialsBySku", company, List.of(blankSkuProduct)))
                .isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.invokeMethod(service, "rawMaterialsBySku", company, List.of(duplicateSkuProduct)))
                .hasSize(1);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(service, "resolveRawMaterialMaterialType", plainRaw, productionMirror, null))
                .isEqualTo(MaterialType.PRODUCTION);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(service, "resolveRawMaterialMaterialType", plainRaw, null, "RAW_MATERIAL"))
                .isEqualTo(MaterialType.PRODUCTION);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(service, "resolveRawMaterialMaterialType", plainRaw, null, "PACKAGING"))
                .isEqualTo(MaterialType.PACKAGING);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeItemClass", "LEGACY"))
                .hasMessageContaining("itemClass must be FINISHED_GOOD, RAW_MATERIAL, or PACKAGING_RAW_MATERIAL");
    }

    @Test
    void helperMethods_coverSkuNormalizationAndDefaultItemClassResolution() {
        ProductionProduct rawProduct = new ProductionProduct();
        rawProduct.setCategory("RAW_MATERIAL");
        rawProduct.setSkuCode("PKG-BBR-PACK-1");

        ProductionProduct finishedGoodProduct = new ProductionProduct();
        finishedGoodProduct.setCategory("FINISHED_GOOD");

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuKey", " pkg-bbr-pack-1 "))
                .isEqualTo("PKG-BBR-PACK-1");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuLookupKey", " pkg-bbr-pack-1 "))
                .isEqualTo("pkg-bbr-pack-1");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeSkuKey", "   "))
                .isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", new Object[]{null}))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", finishedGoodProduct))
                .isEqualTo("FINISHED_GOOD");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "itemClassForProduct", rawProduct))
                .isEqualTo("RAW_MATERIAL");
    }

    @Test
    void helperMethods_coverNullMaterialTypeAndPackagingSkuFallbackBranches() {
        ProductionProduct blankSkuRaw = new ProductionProduct();
        blankSkuRaw.setCompany(company);
        blankSkuRaw.setCategory("RAW_MATERIAL");
        blankSkuRaw.setSkuCode("   ");

        ProductionProduct plainRaw = new ProductionProduct();
        plainRaw.setCategory("RAW_MATERIAL");
        plainRaw.setProductName("Resin Base");
        plainRaw.setSkuCode("RM-PLAIN");

        ProductionProduct packagingSkuRaw = new ProductionProduct();
        packagingSkuRaw.setCategory("RAW_MATERIAL");
        packagingSkuRaw.setSkuCode("PKG-NO-MIRROR");

        RawMaterial unresolvedMirror = new RawMaterial();
        unresolvedMirror.setMaterialType(null);

        assertThat((RawMaterial) ReflectionTestUtils.invokeMethod(service, "rawMaterialForProduct", blankSkuRaw)).isNull();
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                plainRaw,
                unresolvedMirror,
                null)).isEqualTo(MaterialType.PRODUCTION);
        assertThat((MaterialType) ReflectionTestUtils.invokeMethod(
                service,
                "resolveRawMaterialMaterialType",
                plainRaw,
                null,
                null)).isEqualTo(MaterialType.PRODUCTION);
        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "itemClassForProduct",
                plainRaw,
                unresolvedMirror)).isEqualTo("RAW_MATERIAL");
        assertThat((String) ReflectionTestUtils.invokeMethod(
                service,
                "itemClassForProduct",
                packagingSkuRaw,
                unresolvedMirror)).isEqualTo("RAW_MATERIAL");

        verifyNoInteractions(rawMaterialRepository);
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

    @Test
    void searchProducts_batchesRawMaterialMirrorLookupForPackagingRows() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 702L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName("Bucket Shell WHITE 1L");
        product.setCategory("RAW_MATERIAL");
        product.setSkuCode("Pkg-Bbr-Bucket-White-1L");
        product.setDefaultColour("WHITE");
        product.setSizeLabel("1L");
        product.setUnitOfMeasure("UNIT");
        product.setHsnCode("392310");
        product.setActive(true);

        RawMaterial rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 8801L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("PKG-BBR-BUCKET-WHITE-1L");
        rawMaterial.setMaterialType(MaterialType.PACKAGING);

        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product), org.springframework.data.domain.PageRequest.of(0, 20), 1));
        when(rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(eq(company), anyCollection()))
                .thenReturn(List.of(rawMaterial));

        PageResponse<CatalogProductDto> response = service.searchProducts(null, null, null, true, 0, 20);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(response.content().getFirst().rawMaterialId()).isEqualTo(8801L);
        verify(rawMaterialRepository).findByCompanyAndSkuInIgnoreCase(eq(company), argThat(skus ->
                skus.size() == 1
                        && skus.contains("pkg-bbr-bucket-white-1l")
                        && !skus.contains("Pkg-Bbr-Bucket-White-1L")));
        verify(rawMaterialRepository, never()).findByCompanyAndSkuIgnoreCase(any(), any());
    }

    @Test
    void createItem_translatesCanonicalRequestAndHydratesPackagingMirror() {
        CatalogItemRequest request = new CatalogItemRequest(
                11L,
                "Bucket Shell",
                "PACKAGING_RAW_MATERIAL",
                "WHITE",
                "1L",
                "UNIT",
                "392310",
                new BigDecimal("12.00"),
                new BigDecimal("18.00"),
                BigDecimal.ZERO,
                new BigDecimal("12.00"),
                Map.of("inventoryAccountId", 9001L),
                true
        );

        ProductionProduct created = new ProductionProduct();
        ReflectionTestUtils.setField(created, "id", 801L);
        created.setCompany(company);
        created.setBrand(brand);
        created.setProductName("Bucket Shell WHITE 1L");
        created.setCategory("RAW_MATERIAL");
        created.setSkuCode("PKG-BBR-BUCKET-WHITE-1L");
        created.setDefaultColour("WHITE");
        created.setSizeLabel("1L");
        created.setUnitOfMeasure("UNIT");
        created.setHsnCode("392310");
        created.setBasePrice(new BigDecimal("12.00"));
        created.setGstRate(new BigDecimal("18.00"));
        created.setMinDiscountPercent(BigDecimal.ZERO);
        created.setMinSellingPrice(new BigDecimal("12.00"));
        created.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 9001L)));
        created.setActive(true);

        RawMaterial rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 8801L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("PKG-BBR-BUCKET-WHITE-1L");
        rawMaterial.setMaterialType(MaterialType.PACKAGING);
        rawMaterial.setCurrentStock(new BigDecimal("7.50"));

        when(productionCatalogService.createProduct(any(ProductCreateRequest.class))).thenReturn(new ProductionProductDto(
                801L,
                null,
                11L,
                brand.getName(),
                brand.getCode(),
                created.getProductName(),
                created.getCategory(),
                created.getDefaultColour(),
                created.getSizeLabel(),
                created.getUnitOfMeasure(),
                created.getHsnCode(),
                created.getSkuCode(),
                null,
                "Bucket Shell",
                true,
                created.getBasePrice(),
                created.getGstRate(),
                created.getMinDiscountPercent(),
                created.getMinSellingPrice(),
                created.getMetadata()
        ));
        when(productRepository.findByCompanyAndId(company, 801L)).thenReturn(Optional.of(created));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "PKG-BBR-BUCKET-WHITE-1L"))
                .thenReturn(Optional.of(rawMaterial));
        when(skuReadinessService.forProduct(company, created)).thenReturn(null);

        CatalogItemDto response = service.createItem(request);

        ArgumentCaptor<ProductCreateRequest> requestCaptor = ArgumentCaptor.forClass(ProductCreateRequest.class);
        verify(productionCatalogService).createProduct(requestCaptor.capture());
        assertThat(requestCaptor.getValue().itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(requestCaptor.getValue().defaultColour()).isEqualTo("WHITE");
        assertThat(requestCaptor.getValue().sizeLabel()).isEqualTo("1L");
        assertThat(requestCaptor.getValue().active()).isTrue();
        assertThat(response.id()).isEqualTo(801L);
        assertThat(response.itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(response.rawMaterialId()).isEqualTo(8801L);
        assertThat(response.stock().onHandQuantity()).isEqualByComparingTo("7.50");
        assertThat(response.stock().availableQuantity()).isEqualByComparingTo("7.50");
    }

    @Test
    void updateItem_delegatesCanonicalUpdateAndHydratesFinishedGoodMirror() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 802L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Primer");
        existing.setCategory("FINISHED_GOOD");
        existing.setSkuCode("BBR-PRIMER-001");
        existing.setDefaultColour("WHITE");
        existing.setSizeLabel("20L");
        existing.setUnitOfMeasure("LITER");
        existing.setHsnCode("320810");
        existing.setBasePrice(new BigDecimal("799.00"));
        existing.setGstRate(new BigDecimal("18.00"));
        existing.setMinDiscountPercent(new BigDecimal("5.00"));
        existing.setMinSellingPrice(new BigDecimal("760.00"));
        existing.setActive(true);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("BBR-PRIMER-001");
        finishedGood.setCurrentStock(new BigDecimal("12.00"));
        finishedGood.setReservedStock(new BigDecimal("2.00"));
        finishedGood.setUnit("LITER");

        CatalogItemRequest request = new CatalogItemRequest(
                11L,
                "Primer Updated",
                "FINISHED_GOOD",
                "WHITE",
                "20L",
                "LITER",
                "320810",
                new BigDecimal("820.00"),
                new BigDecimal("18.00"),
                new BigDecimal("4.00"),
                new BigDecimal("790.00"),
                Map.of("productType", "decorative"),
                false
        );

        when(productRepository.findByCompanyAndId(company, 802L)).thenReturn(Optional.of(existing));
        when(finishedGoodRepository.findByCompanyAndProductCodeIgnoreCase(company, "BBR-PRIMER-001"))
                .thenReturn(Optional.of(finishedGood));
        when(skuReadinessService.forProduct(company, existing)).thenReturn(null);
        when(productionCatalogService.updateProduct(eq(802L), any(ProductUpdateRequest.class))).thenAnswer(invocation -> {
            existing.setProductName("Primer Updated");
            existing.setActive(false);
            existing.setBasePrice(new BigDecimal("820.00"));
            existing.setMinDiscountPercent(new BigDecimal("4.00"));
            existing.setMinSellingPrice(new BigDecimal("790.00"));
            return new ProductionProductDto(
                    802L,
                    null,
                    11L,
                    brand.getName(),
                    brand.getCode(),
                    existing.getProductName(),
                    existing.getCategory(),
                    existing.getDefaultColour(),
                    existing.getSizeLabel(),
                    existing.getUnitOfMeasure(),
                    existing.getHsnCode(),
                    existing.getSkuCode(),
                    null,
                    "Primer",
                    existing.isActive(),
                    existing.getBasePrice(),
                    existing.getGstRate(),
                    existing.getMinDiscountPercent(),
                    existing.getMinSellingPrice(),
                    Map.of("productType", "decorative")
            );
        });

        CatalogItemDto response = service.updateItem(802L, request);

        ArgumentCaptor<ProductUpdateRequest> requestCaptor = ArgumentCaptor.forClass(ProductUpdateRequest.class);
        verify(productionCatalogService).updateProduct(eq(802L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().itemClass()).isEqualTo("FINISHED_GOOD");
        assertThat(requestCaptor.getValue().active()).isFalse();
        assertThat(response.active()).isFalse();
        assertThat(response.stock().onHandQuantity()).isEqualByComparingTo("12.00");
        assertThat(response.stock().reservedQuantity()).isEqualByComparingTo("2.00");
        assertThat(response.stock().availableQuantity()).isEqualByComparingTo("10.00");
    }

    @Test
    void deactivateItem_marksItemInactiveAndReturnsHydratedMirror() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 803L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Titanium Dioxide");
        existing.setCategory("RAW_MATERIAL");
        existing.setSkuCode("RM-TIO2-001");
        existing.setUnitOfMeasure("KG");
        existing.setHsnCode("282300");
        existing.setActive(true);

        RawMaterial rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 8803L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-TIO2-001");
        rawMaterial.setMaterialType(MaterialType.PRODUCTION);
        rawMaterial.setCurrentStock(new BigDecimal("3.00"));

        when(productRepository.findByCompanyAndId(company, 803L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "RM-TIO2-001")).thenReturn(Optional.of(rawMaterial));
        when(skuReadinessService.forProduct(company, existing)).thenReturn(null);

        CatalogItemDto response = service.deactivateItem(803L);

        assertThat(existing.isActive()).isFalse();
        assertThat(response.active()).isFalse();
        assertThat(response.rawMaterialId()).isEqualTo(8803L);
        assertThat(response.stock().availableQuantity()).isEqualByComparingTo("3.00");
    }

    @Test
    void searchItems_filtersByItemClassAndIncludesStock() {
        ProductionProduct packaging = new ProductionProduct();
        ReflectionTestUtils.setField(packaging, "id", 804L);
        packaging.setCompany(company);
        packaging.setBrand(brand);
        packaging.setProductName("Bucket Shell WHITE 1L");
        packaging.setCategory("RAW_MATERIAL");
        packaging.setSkuCode("Pkg-Bbr-Bucket-White-1L");
        packaging.setDefaultColour("WHITE");
        packaging.setSizeLabel("1L");
        packaging.setUnitOfMeasure("UNIT");
        packaging.setHsnCode("392310");
        packaging.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 9001L)));
        packaging.setActive(true);

        ProductionProduct finishedGoodProduct = new ProductionProduct();
        ReflectionTestUtils.setField(finishedGoodProduct, "id", 805L);
        finishedGoodProduct.setCompany(company);
        finishedGoodProduct.setBrand(brand);
        finishedGoodProduct.setProductName("Primer");
        finishedGoodProduct.setCategory("FINISHED_GOOD");
        finishedGoodProduct.setSkuCode("BBR-PRIMER-002");
        finishedGoodProduct.setUnitOfMeasure("LITER");
        finishedGoodProduct.setHsnCode("320810");
        finishedGoodProduct.setActive(true);

        RawMaterial packagingMirror = new RawMaterial();
        ReflectionTestUtils.setField(packagingMirror, "id", 8804L);
        packagingMirror.setCompany(company);
        packagingMirror.setSku("PKG-BBR-BUCKET-WHITE-1L");
        packagingMirror.setMaterialType(MaterialType.PACKAGING);
        packagingMirror.setCurrentStock(new BigDecimal("9.00"));

        when(productRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(packaging), org.springframework.data.domain.PageRequest.of(0, 20), 1));
        when(rawMaterialRepository.findByCompanyAndSkuInIgnoreCase(eq(company), anyCollection()))
                .thenReturn(List.of(packagingMirror));
        when(finishedGoodRepository.findByCompanyAndProductCodeInIgnoreCase(eq(company), anyCollection()))
                .thenReturn(List.of());
        when(skuReadinessService.forProducts(company, List.of(packaging))).thenReturn(Map.of());

        PageResponse<CatalogItemDto> response = service.searchItems(
                "bucket",
                "PACKAGING_RAW_MATERIAL",
                true,
                true,
                0,
                20,
                false
        );

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().id()).isEqualTo(804L);
        assertThat(response.content().getFirst().itemClass()).isEqualTo("PACKAGING_RAW_MATERIAL");
        assertThat(response.content().getFirst().rawMaterialId()).isEqualTo(8804L);
        assertThat(response.content().getFirst().stock().availableQuantity()).isEqualByComparingTo("9.00");
        assertThat(response.content().getFirst().metadata()).doesNotContainKey("inventoryAccountId");
        verify(rawMaterialRepository).findByCompanyAndSkuInIgnoreCase(eq(company), argThat(skus ->
                skus.size() == 1 && skus.contains("pkg-bbr-bucket-white-1l")));
    }
}
