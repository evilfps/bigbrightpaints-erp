package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
}
