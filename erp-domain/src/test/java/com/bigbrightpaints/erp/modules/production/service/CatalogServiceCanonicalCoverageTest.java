package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("critical")
class CatalogServiceCanonicalCoverageTest {

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

        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setCompany(company);
        brand.setName("BigBright");
        brand.setCode("BBR");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void buildProductSpecification_appliesColorAndSizeFilters() {
        Specification<ProductionProduct> specification = ReflectionTestUtils.invokeMethod(
                service,
                "buildProductSpecification",
                company,
                11L,
                "Red",
                "20L",
                true);

        Root root = mock(Root.class);
        CriteriaQuery query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path companyPath = mock(Path.class);
        Path brandPath = mock(Path.class);
        Path brandIdPath = mock(Path.class);
        Path activePath = mock(Path.class);
        Path defaultColourPath = mock(Path.class);
        Path sizeLabelPath = mock(Path.class);
        SetJoin colorsJoin = mock(SetJoin.class);
        SetJoin sizesJoin = mock(SetJoin.class);
        Expression defaultColourExpr = mock(Expression.class);
        Expression colorExpr = mock(Expression.class);
        Expression sizeLabelExpr = mock(Expression.class);
        Expression sizeExpr = mock(Expression.class);
        Predicate companyPredicate = mock(Predicate.class);
        Predicate brandPredicate = mock(Predicate.class);
        Predicate activePredicate = mock(Predicate.class);
        Predicate defaultColourPredicate = mock(Predicate.class);
        Predicate colorPredicate = mock(Predicate.class);
        Predicate colorOr = mock(Predicate.class);
        Predicate sizeLabelPredicate = mock(Predicate.class);
        Predicate sizePredicate = mock(Predicate.class);
        Predicate sizeOr = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);

        when(root.get("company")).thenReturn(companyPath);
        when(root.get("brand")).thenReturn(brandPath);
        when(brandPath.get("id")).thenReturn(brandIdPath);
        when(root.get("active")).thenReturn(activePath);
        when(root.get("defaultColour")).thenReturn(defaultColourPath);
        when(root.get("sizeLabel")).thenReturn(sizeLabelPath);
        when(root.joinSet("colors", JoinType.LEFT)).thenReturn(colorsJoin);
        when(root.joinSet("sizes", JoinType.LEFT)).thenReturn(sizesJoin);

        when(cb.equal(companyPath, company)).thenReturn(companyPredicate);
        when(cb.equal(brandIdPath, 11L)).thenReturn(brandPredicate);
        when(cb.equal(activePath, true)).thenReturn(activePredicate);
        when(cb.lower(defaultColourPath)).thenReturn(defaultColourExpr);
        when(cb.lower(colorsJoin)).thenReturn(colorExpr);
        when(cb.lower(sizeLabelPath)).thenReturn(sizeLabelExpr);
        when(cb.lower(sizesJoin)).thenReturn(sizeExpr);
        when(cb.equal(defaultColourExpr, "red")).thenReturn(defaultColourPredicate);
        when(cb.equal(colorExpr, "red")).thenReturn(colorPredicate);
        when(cb.equal(sizeLabelExpr, "20l")).thenReturn(sizeLabelPredicate);
        when(cb.equal(sizeExpr, "20l")).thenReturn(sizePredicate);
        when(cb.or(defaultColourPredicate, colorPredicate)).thenReturn(colorOr);
        when(cb.or(sizeLabelPredicate, sizePredicate)).thenReturn(sizeOr);
        when(query.distinct(true)).thenReturn(query);
        doReturn(combined).when(cb).and(org.mockito.ArgumentMatchers.any(Predicate[].class));

        Predicate result = specification.toPredicate(root, query, cb);

        assertThat(result).isSameAs(combined);
        verify(query, times(2)).distinct(true);
    }

    @Test
    void toProductDto_usesFallbackVariants_andEmptyMetadataWhenSourceIsNull() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 701L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName("Primer");
        product.setSkuCode("BBR-PRIMER-001");
        product.setCategory("FINISHED_GOOD");
        product.setDefaultColour("White");
        product.setSizeLabel("1L");
        product.setColors(new LinkedHashSet<>());
        product.setSizes(new LinkedHashSet<>());
        product.setCartonSizes(new LinkedHashMap<>(Map.of("1L", 6)));
        product.setUnitOfMeasure("LITER");
        product.setHsnCode("320910");
        product.setBasePrice(new BigDecimal("1200.00"));
        product.setGstRate(new BigDecimal("18.00"));
        product.setMetadata(null);

        CatalogProductDto dto = ReflectionTestUtils.invokeMethod(service, "toProductDto", product);

        assertThat(dto.colors()).containsExactly("White");
        assertThat(dto.sizes()).containsExactly("1L");
        assertThat(dto.metadata()).isEmpty();
        assertThat(dto.cartonSizes())
                .extracting(item -> item.size() + ":" + item.piecesPerCarton())
                .containsExactly("1L:6");
    }

    @Test
    void toProductDto_includesReadinessSnapshot() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 702L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName("Primer Ready");
        product.setSkuCode("BBR-PRIMER-READY-001");
        product.setCategory("FINISHED_GOOD");
        product.setActive(true);
        product.setColors(new LinkedHashSet<>(List.of("White")));
        product.setSizes(new LinkedHashSet<>(List.of("1L")));

        SkuReadinessDto readiness = new SkuReadinessDto(
                "BBR-PRIMER-READY-001",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("NO_FINISHED_GOOD_BATCH_STOCK")),
                new SkuReadinessDto.Stage(true, List.of())
        );
        when(skuReadinessService.forProduct(company, product)).thenReturn(readiness);
        when(skuReadinessService.sanitizeForCatalogViewer(readiness, true)).thenReturn(readiness);

        CatalogProductDto dto = ReflectionTestUtils.invokeMethod(service, "toProductDto", product);

        assertThat(dto.readiness()).isSameAs(readiness);
    }

    @Test
    void searchProducts_usesBatchReadinessSnapshotInsteadOfPerProductLookups() {
        ProductionProduct first = finishedGoodProduct(1001L, "BBR-PRIMER-001", "Primer One");
        ProductionProduct second = finishedGoodProduct(1002L, "BBR-PRIMER-002", "Primer Two");
        Page<ProductionProduct> page = new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2);

        SkuReadinessDto firstReadiness = new SkuReadinessDto(
                "BBR-PRIMER-001",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("NO_FINISHED_GOOD_BATCH_STOCK")),
                new SkuReadinessDto.Stage(true, List.of())
        );
        SkuReadinessDto secondReadiness = new SkuReadinessDto(
                "BBR-PRIMER-002",
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("NO_FINISHED_GOOD_BATCH_STOCK", "ACCOUNTING_CONFIGURATION_REQUIRED")),
                new SkuReadinessDto.Stage(false, List.of("ACCOUNTING_CONFIGURATION_REQUIRED"))
        );

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(productRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);
        when(skuReadinessService.forProducts(company, List.of(first, second)))
                .thenReturn(Map.of(1001L, firstReadiness, 1002L, secondReadiness));
        when(skuReadinessService.sanitizeForCatalogViewer(firstReadiness, false)).thenReturn(firstReadiness);
        when(skuReadinessService.sanitizeForCatalogViewer(secondReadiness, false)).thenReturn(secondReadiness);

        var response = service.searchProducts(brand.getId(), null, null, true, 0, 20, false);

        assertThat(response.content()).extracting(CatalogProductDto::readiness)
                .containsExactly(firstReadiness, secondReadiness);
        verify(skuReadinessService).forProducts(company, List.of(first, second));
        verify(skuReadinessService, never()).forProduct(any(), any());
    }

    @Test
    void toVariantList_prefersExplicitValues_thenFallback_thenEmpty() {
        @SuppressWarnings("unchecked")
        List<String> explicit = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "toVariantList",
                new LinkedHashSet<>(List.of("Red", "Blue")),
                "White");
        @SuppressWarnings("unchecked")
        List<String> fallback = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "toVariantList",
                new LinkedHashSet<String>(),
                "White");
        @SuppressWarnings("unchecked")
        List<String> empty = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "toVariantList",
                new LinkedHashSet<String>(),
                "   ");

        assertThat(explicit).containsExactly("Red", "Blue");
        assertThat(fallback).containsExactly("White");
        assertThat(empty).isEmpty();
    }

    @Test
    void syncInventoryTruth_shortCircuitsWhenCompanyProductOrIdIsMissing() {
        ProductionProduct persistedProduct = new ProductionProduct();
        ReflectionTestUtils.setField(persistedProduct, "id", 801L);

        ProductionProduct transientProduct = new ProductionProduct();

        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", (Company) null, persistedProduct, "RAW_MATERIAL");
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, null, "RAW_MATERIAL");
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, transientProduct, "RAW_MATERIAL");

        verifyNoInteractions(rawMaterialRepository, finishedGoodRepository);
    }

    @Test
    void syncInventoryTruth_appliesMetadataDefaultAndExistingRawMaterialAccounts() {
        company.setDefaultInventoryAccountId(77L);
        Company companyWithoutDefault = new Company();
        ReflectionTestUtils.setField(companyWithoutDefault, "id", 201L);
        companyWithoutDefault.setCode("BBP-NODEFAULT");

        ProductionProduct metadataProduct = rawMaterialProduct(901L, "RM-META", "Titanium Dioxide", " KG ", new BigDecimal("18.00"));
        metadataProduct.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 88L)));
        ProductionProduct defaultProduct = rawMaterialProduct(902L, "RM-DEFAULT", "Calcium Carbonate", null, null);
        defaultProduct.setMetadata(new LinkedHashMap<>());
        ProductionProduct preservedProduct = rawMaterialProduct(903L, "RM-PRESERVE", "Preserved Account", "LITER", null);
        preservedProduct.setMetadata(new LinkedHashMap<>());
        ProductionProduct noDefaultProduct = rawMaterialProduct(904L, "RM-NODEFAULT", "No Default", null, null);
        noDefaultProduct.setMetadata(new LinkedHashMap<>());

        RawMaterial preserved = new RawMaterial();
        preserved.setInventoryAccountId(55L);
        RawMaterial noDefault = new RawMaterial();

        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-META")).thenReturn(Optional.empty());
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-DEFAULT")).thenReturn(Optional.empty());
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-PRESERVE")).thenReturn(Optional.of(preserved));
        when(rawMaterialRepository.findByCompanyAndSku(companyWithoutDefault, "RM-NODEFAULT")).thenReturn(Optional.of(noDefault));
        when(rawMaterialRepository.save(org.mockito.ArgumentMatchers.any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, metadataProduct, "RAW_MATERIAL");
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, defaultProduct, "RAW_MATERIAL");
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, preservedProduct, "RAW_MATERIAL");
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", companyWithoutDefault, noDefaultProduct, "RAW_MATERIAL");

        ArgumentCaptor<RawMaterial> materialCaptor = ArgumentCaptor.forClass(RawMaterial.class);
        verify(rawMaterialRepository, times(4)).save(materialCaptor.capture());
        List<RawMaterial> savedMaterials = materialCaptor.getAllValues();
        RawMaterial metadataMaterial = savedMaterials.get(0);
        RawMaterial defaultMaterial = savedMaterials.get(1);
        RawMaterial preservedMaterial = savedMaterials.get(2);
        RawMaterial noDefaultMaterial = savedMaterials.get(3);

        assertThat(metadataMaterial.getInventoryAccountId()).isEqualTo(88L);
        assertThat(metadataMaterial.getUnitType()).isEqualTo("KG");
        assertThat(metadataMaterial.getGstRate()).isEqualByComparingTo("18.00");
        assertThat(defaultMaterial.getInventoryAccountId()).isEqualTo(77L);
        assertThat(defaultMaterial.getUnitType()).isEqualTo("UNIT");
        assertThat(defaultMaterial.getGstRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preservedMaterial.getInventoryAccountId()).isEqualTo(55L);
        assertThat(noDefaultMaterial.getInventoryAccountId()).isNull();
        assertThat(noDefaultMaterial.getUnitType()).isEqualTo("UNIT");
    }

    @Test
    void rawMaterialHelpers_coverFallbackAndParsingBranches() {
        String defaultUnit = ReflectionTestUtils.invokeMethod(service, "resolveUnit", (String) null);
        String trimmedUnit = ReflectionTestUtils.invokeMethod(service, "resolveUnit", " KG ");
        assertThat(defaultUnit).isEqualTo("UNIT");
        assertThat(trimmedUnit).isEqualTo("KG");

        Long nullProductAccountId = ReflectionTestUtils.invokeMethod(
                service,
                "rawMaterialInventoryAccountIdFromMetadata",
                (Object) null);
        assertThat(nullProductAccountId).isNull();

        ProductionProduct numericProduct = new ProductionProduct();
        numericProduct.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 12L)));
        Long numericAccountId = ReflectionTestUtils.invokeMethod(service, "rawMaterialInventoryAccountIdFromMetadata", numericProduct);
        assertThat(numericAccountId).isEqualTo(12L);

        ProductionProduct zeroNumeric = new ProductionProduct();
        zeroNumeric.setMetadata(new LinkedHashMap<>(Map.of("inventoryAccountId", 0L)));
        Long zeroNumericAccountId = ReflectionTestUtils.invokeMethod(service, "rawMaterialInventoryAccountIdFromMetadata", zeroNumeric);
        assertThat(zeroNumericAccountId).isNull();

        ProductionProduct stringProduct = new ProductionProduct();
        stringProduct.setMetadata(new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", "42")));
        Long stringAccountId = ReflectionTestUtils.invokeMethod(service, "rawMaterialInventoryAccountIdFromMetadata", stringProduct);
        assertThat(stringAccountId).isEqualTo(42L);

        ProductionProduct negativeStringProduct = new ProductionProduct();
        negativeStringProduct.setMetadata(new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", "-9")));
        Long negativeStringAccountId = ReflectionTestUtils.invokeMethod(service, "rawMaterialInventoryAccountIdFromMetadata", negativeStringProduct);
        assertThat(negativeStringAccountId).isNull();

        ProductionProduct invalidStringProduct = new ProductionProduct();
        invalidStringProduct.setMetadata(new LinkedHashMap<>(Map.of("rawMaterialInventoryAccountId", "abc")));
        Long invalidStringAccountId = ReflectionTestUtils.invokeMethod(service, "rawMaterialInventoryAccountIdFromMetadata", invalidStringProduct);
        assertThat(invalidStringAccountId).isNull();

        Boolean rawMaterialCategory = ReflectionTestUtils.invokeMethod(service, "isRawMaterialCategory", "raw-material");
        Boolean blankCategory = ReflectionTestUtils.invokeMethod(service, "isRawMaterialCategory", " ");
        assertThat(rawMaterialCategory).isTrue();
        assertThat(blankCategory).isFalse();

        @SuppressWarnings("unchecked")
        List<String> fallbackFromNull = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "toVariantList",
                null,
                " White ");
        assertThat(fallbackFromNull).containsExactly("White");
    }

    @Test
    void syncSizeVariants_handlesMillilitersAndDisablesStaleVariants() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 905L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setCartonSizes(new LinkedHashMap<>(Map.of("500 ml", 6)));

        SizeVariant nullLabel = new SizeVariant();
        nullLabel.setCompany(company);
        nullLabel.setProduct(product);
        nullLabel.setActive(true);

        SizeVariant stale = new SizeVariant();
        stale.setCompany(company);
        stale.setProduct(product);
        stale.setSizeLabel("1L");
        stale.setActive(true);

        when(sizeVariantRepository.findByCompanyAndProductAndSizeLabelIgnoreCase(company, product, "500 ml"))
                .thenReturn(Optional.empty());
        when(sizeVariantRepository.findByCompanyAndProductOrderBySizeLabelAsc(company, product))
                .thenReturn(List.of(nullLabel, stale));
        when(sizeVariantRepository.save(org.mockito.ArgumentMatchers.any(SizeVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "syncSizeVariants", company, product);

        ArgumentCaptor<SizeVariant> variantCaptor = ArgumentCaptor.forClass(SizeVariant.class);
        verify(sizeVariantRepository, times(2)).save(variantCaptor.capture());
        List<SizeVariant> savedVariants = variantCaptor.getAllValues();
        SizeVariant createdVariant = savedVariants.getFirst();

        assertThat(createdVariant.getSizeLabel()).isEqualTo("500 ml");
        assertThat(createdVariant.getCartonQuantity()).isEqualTo(6);
        assertThat(createdVariant.getLitersPerUnit()).isEqualByComparingTo("0.5000");
        assertThat(createdVariant.isActive()).isTrue();
        assertThat(stale.isActive()).isFalse();
    }

    @Test
    void helperMethods_coverSequenceSanitizerAndCanonicalLinkageBranches() {
        ProductionProduct latest = new ProductionProduct();
        latest.setSkuCode("BBR-PRIMER-007");

        when(productRepository.findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(company, "BBR-PRIMER"))
                .thenReturn(Optional.of(latest));
        when(productRepository.findByCompanyAndSkuCode(company, "BBR-PRIMER-008"))
                .thenReturn(Optional.of(new ProductionProduct()));
        when(productRepository.findByCompanyAndSkuCode(company, "BBR-PRIMER-009"))
                .thenReturn(Optional.empty());

        String generatedSku = ReflectionTestUtils.invokeMethod(service, "generateSku", company, brand, "Primer");
        String fallbackCode = ReflectionTestUtils.invokeMethod(service, "sanitizeCode", "***");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedNullMetadata = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "normalizeMetadata",
                (Object) null);
        String blankOptional = ReflectionTestUtils.invokeMethod(service, "normalizeOptionalText", "   ");
        String blankSegment = ReflectionTestUtils.invokeMethod(service, "sanitizeSegment", "   ");
        UUID anonymousVariantGroupId = ReflectionTestUtils.invokeMethod(
                service,
                "buildVariantGroupId",
                null,
                null,
                "Primer",
                "FINISHED_GOOD",
                "LITER",
                "320910");

        ProductionProduct legacyProduct = new ProductionProduct();
        legacyProduct.setCompany(company);
        legacyProduct.setBrand(brand);
        legacyProduct.setProductName("Legacy Product");
        legacyProduct.setCategory("FINISHED_GOOD");
        legacyProduct.setUnitOfMeasure("LITER");
        legacyProduct.setHsnCode("320910");

        ReflectionTestUtils.invokeMethod(service, "refreshCanonicalFamilyLinkage", legacyProduct, brand, null, null, "FINISHED_GOOD");

        assertThat(generatedSku).isEqualTo("BBR-PRIMER-009");
        assertThat(fallbackCode).isEqualTo("CAT");
        assertThat(normalizedNullMetadata).isEmpty();
        assertThat(blankOptional).isNull();
        assertThat(blankSegment).isEmpty();
        assertThat(anonymousVariantGroupId).isNotNull();
        assertThat(legacyProduct.getVariantGroupId()).isNull();
        assertThat(legacyProduct.getProductFamilyName()).isNull();
    }

    private ProductionProduct rawMaterialProduct(Long id,
                                                 String sku,
                                                 String name,
                                                 String unitOfMeasure,
                                                 BigDecimal gstRate) {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", id);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName(name);
        product.setSkuCode(sku);
        product.setCategory("RAW_MATERIAL");
        product.setUnitOfMeasure(unitOfMeasure);
        product.setGstRate(gstRate);
        return product;
    }

    private ProductionProduct finishedGoodProduct(Long id, String sku, String name) {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", id);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName(name);
        product.setSkuCode(sku);
        product.setCategory("FINISHED_GOOD");
        product.setActive(true);
        product.setColors(new LinkedHashSet<>(List.of("White")));
        product.setSizes(new LinkedHashSet<>(List.of("1L")));
        product.setMetadata(new LinkedHashMap<>());
        return product;
    }
}
