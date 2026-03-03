package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceProductCrudTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private ProductionBrandRepository brandRepository;
    @Mock private ProductionProductRepository productRepository;
    @Mock private SizeVariantRepository sizeVariantRepository;

    private CatalogService service;
    private Company company;
    private ProductionBrand brand;

    @BeforeEach
    void setUp() {
        service = new CatalogService(companyContextService, brandRepository, productRepository, sizeVariantRepository);
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 200L);
        company.setCode("BBP");

        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setCompany(company);
        brand.setName("BigBright");
        brand.setCode("BBR");
        brand.setActive(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
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
                new BigDecimal("18.00"),
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
        assertThat(response.gstRate()).isEqualByComparingTo("18.00");
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateProduct_updatesFullCatalogAttributes() {
        ProductionProduct existing = new ProductionProduct();
        ReflectionTestUtils.setField(existing, "id", 502L);
        existing.setCompany(company);
        existing.setBrand(brand);
        existing.setProductName("Old Name");
        existing.setSkuCode("BBR-OLDNAME-001");
        existing.setActive(true);
        existing.setColors(new LinkedHashSet<>(List.of("White")));
        existing.setSizes(new LinkedHashSet<>(List.of("1L")));
        existing.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("1L", 12)));

        CatalogProductRequest request = new CatalogProductRequest(
                11L,
                "Interior Emulsion",
                List.of("Ivory"),
                List.of("10L"),
                List.of(new CatalogProductCartonSizeRequest("10L", 2)),
                "LITER",
                "320990",
                new BigDecimal("12.00"),
                false
        );

        when(brandRepository.findByCompanyAndId(company, 11L)).thenReturn(Optional.of(brand));
        when(productRepository.findByCompanyAndId(company, 502L)).thenReturn(Optional.of(existing));
        when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Interior Emulsion")).thenReturn(Optional.of(existing));
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogProductDto response = service.updateProduct(502L, request);

        assertThat(response.id()).isEqualTo(502L);
        assertThat(response.name()).isEqualTo("Interior Emulsion");
        assertThat(response.sku()).isEqualTo("BBR-OLDNAME-001");
        assertThat(response.colors()).containsExactly("Ivory");
        assertThat(response.sizes()).containsExactly("10L");
        assertThat(response.cartonSizes())
                .extracting(mapping -> mapping.size() + ":" + mapping.piecesPerCarton())
                .containsExactly("10L:2");
        assertThat(response.hsnCode()).isEqualTo("320990");
        assertThat(response.gstRate()).isEqualByComparingTo("12.00");
        assertThat(response.active()).isFalse();
    }

    @Test
    void searchProducts_returnsPaginatedFilteredResponse() {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", 701L);
        product.setCompany(company);
        product.setBrand(brand);
        product.setProductName("Primer");
        product.setSkuCode("BBR-PRIMER-001");
        product.setActive(true);
        product.setColors(new LinkedHashSet<>(List.of("Red")));
        product.setSizes(new LinkedHashSet<>(List.of("20L")));
        product.setCartonSizes(new LinkedHashMap<>(java.util.Map.of("20L", 1)));
        product.setUnitOfMeasure("LITER");
        product.setHsnCode("320810");
        product.setGstRate(new BigDecimal("18.00"));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(productRepository.findAll(any(Specification.class), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(product), org.springframework.data.domain.PageRequest.of(1, 5), 11));

        PageResponse<CatalogProductDto> response = service.searchProducts(11L, "red", "20l", true, 1, 5);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(11);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).sku()).isEqualTo("BBR-PRIMER-001");
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("productName")).isNotNull();
    }
}
