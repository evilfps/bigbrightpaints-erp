package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkItemRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductBulkResponse;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductCartonSizeRequest;
import com.bigbrightpaints.erp.modules.production.dto.CatalogProductRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogServiceBulkUpsertTest {

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
        ReflectionTestUtils.setField(company, "id", 301L);
        company.setCode("BBP");

        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 1L);
        brand.setCompany(company);
        brand.setName("BigBright");
        brand.setCode("BBR");
        brand.setActive(true);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(brandRepository.findByCompanyAndId(company, 1L)).thenReturn(Optional.of(brand));
        when(productRepository.findByBrandAndProductNameIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
        when(productRepository.findTopByCompanyAndSkuCodeStartingWithOrderBySkuCodeDesc(any(), anyString())).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndSkuCode(any(), anyString())).thenReturn(Optional.empty());
        when(finishedGoodRepository.findByCompanyAndProductCode(any(), anyString())).thenReturn(Optional.empty());
        when(rawMaterialRepository.findByCompanyAndSku(any(), anyString())).thenReturn(Optional.empty());

        AtomicLong idSequence = new AtomicLong(900L);
        when(productRepository.save(any(ProductionProduct.class))).thenAnswer(invocation -> {
            ProductionProduct saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", idSequence.incrementAndGet());
            }
            return saved;
        });
    }

    @Test
    void bulkUpsertProducts_mixedValidAndInvalidItems_returnsPerItemResults() {
        CatalogProductBulkItemRequest validCreate = new CatalogProductBulkItemRequest(
                null,
                null,
                new CatalogProductRequest(
                        1L,
                        "Weather Shield",
                        "FINISHED_GOOD",
                        List.of("Red"),
                        List.of("1L"),
                        List.of(new CatalogProductCartonSizeRequest("1L", 24)),
                        "LITER",
                        "320910",
                        null,
                        new BigDecimal("18.00"),
                        null,
                        null,
                        null,
                        true
                ));

        CatalogProductBulkItemRequest invalidCreate = new CatalogProductBulkItemRequest(
                null,
                null,
                new CatalogProductRequest(
                        1L,
                        "Bad Product",
                        "FINISHED_GOOD",
                        List.of("Blue"),
                        List.of("4L"),
                        List.of(),
                        "LITER",
                        "320910",
                        null,
                        new BigDecimal("18.00"),
                        null,
                        null,
                        null,
                        true
                ));

        CatalogProductBulkResponse response = service.bulkUpsertProducts(List.of(validCreate, invalidCreate));

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.succeeded()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);

        assertThat(response.results().get(0).success()).isTrue();
        assertThat(response.results().get(0).action()).isEqualTo("CREATED");
        assertThat(response.results().get(0).product()).isNotNull();
        assertThat(response.results().get(0).product().sku()).startsWith("BBR-");

        assertThat(response.results().get(1).success()).isFalse();
        assertThat(response.results().get(1).action()).isEqualTo("FAILED");
        assertThat(response.results().get(1).message()).contains("cartonSizes mapping is required");
    }

    @Test
    void bulkUpsertProducts_blankOrNullRequiredFields_returnsFieldLevelValidationMessages() {
        CatalogProductBulkItemRequest invalidCreate = new CatalogProductBulkItemRequest(
                null,
                null,
                new CatalogProductRequest(
                        null,
                        "   ",
                        null,
                        List.of("Blue"),
                        List.of("4L"),
                        List.of(new CatalogProductCartonSizeRequest("4L", 12)),
                        "   ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true
                ));

        CatalogProductBulkResponse response = service.bulkUpsertProducts(List.of(invalidCreate));

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.succeeded()).isEqualTo(0);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().success()).isFalse();
        assertThat(response.results().getFirst().message())
                .contains("brandId")
                .contains("name")
                .contains("unitOfMeasure")
                .contains("hsnCode")
                .contains("gstRate");
    }
}
