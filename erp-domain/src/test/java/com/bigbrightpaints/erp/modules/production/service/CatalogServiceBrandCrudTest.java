package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandDto;
import com.bigbrightpaints.erp.modules.production.dto.CatalogBrandRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceBrandCrudTest {

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
        ReflectionTestUtils.setField(company, "id", 10L);
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createBrand_companyScopedCrudFieldsAreStored() {
        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "Nova Paints")).thenReturn(Optional.empty());
        when(brandRepository.findByCompanyAndCodeIgnoreCase(company, "NOVAPAINTS")).thenReturn(Optional.empty());
        when(brandRepository.save(any(ProductionBrand.class))).thenAnswer(invocation -> {
            ProductionBrand saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 101L);
            return saved;
        });

        CatalogBrandDto response = service.createBrand(new CatalogBrandRequest(
                "Nova Paints",
                "https://cdn.example.com/logo.png",
                "Premium decorative paints",
                true
        ));

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.name()).isEqualTo("Nova Paints");
        assertThat(response.code()).isEqualTo("NOVAPAINTS");
        assertThat(response.logoUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(response.description()).isEqualTo("Premium decorative paints");
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateBrand_updatesNameLogoDescriptionAndActive() {
        ProductionBrand existing = new ProductionBrand();
        ReflectionTestUtils.setField(existing, "id", 55L);
        existing.setCompany(company);
        existing.setName("Old Brand");
        existing.setCode("OLDBRAND");
        existing.setActive(true);

        when(brandRepository.findByCompanyAndId(company, 55L)).thenReturn(Optional.of(existing));
        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "Updated Brand")).thenReturn(Optional.empty());
        when(brandRepository.save(any(ProductionBrand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogBrandDto response = service.updateBrand(55L, new CatalogBrandRequest(
                "Updated Brand",
                "https://logo.example/new.png",
                "Updated description",
                false
        ));

        assertThat(response.id()).isEqualTo(55L);
        assertThat(response.name()).isEqualTo("Updated Brand");
        assertThat(response.logoUrl()).isEqualTo("https://logo.example/new.png");
        assertThat(response.description()).isEqualTo("Updated description");
        assertThat(response.active()).isFalse();
    }

    @Test
    void deactivateBrand_marksBrandInactive() {
        ProductionBrand existing = new ProductionBrand();
        ReflectionTestUtils.setField(existing, "id", 91L);
        existing.setCompany(company);
        existing.setName("Deactivate Me");
        existing.setCode("DEACTIVATE");
        existing.setActive(true);

        when(brandRepository.findByCompanyAndId(company, 91L)).thenReturn(Optional.of(existing));
        when(brandRepository.save(any(ProductionBrand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CatalogBrandDto response = service.deactivateBrand(91L);

        assertThat(response.id()).isEqualTo(91L);
        assertThat(response.active()).isFalse();
    }
}
