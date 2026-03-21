package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("critical")
class CatalogServiceCanonicalCoverageTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private ProductionBrandRepository brandRepository;
    @Mock private ProductionProductRepository productRepository;
    @Mock private SizeVariantRepository sizeVariantRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;

    private CatalogService service;
    private Company company;
    private ProductionBrand brand;

    @BeforeEach
    void setUp() {
        service = new CatalogService(
                companyContextService,
                brandRepository,
                productRepository,
                sizeVariantRepository,
                finishedGoodRepository,
                rawMaterialRepository);

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

        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", (Company) null, persistedProduct);
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, null);
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, transientProduct);

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

        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, metadataProduct);
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, defaultProduct);
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", company, preservedProduct);
        ReflectionTestUtils.invokeMethod(service, "syncInventoryTruth", companyWithoutDefault, noDefaultProduct);

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
}
