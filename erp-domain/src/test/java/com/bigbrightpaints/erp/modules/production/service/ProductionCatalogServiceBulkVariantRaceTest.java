package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionCatalogServiceBulkVariantRaceTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private ProductionBrandRepository brandRepository;
    @Mock private ProductionProductRepository productRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private CompanyDefaultAccountsService companyDefaultAccountsService;
    @Mock private CatalogImportRepository catalogImportRepository;
    @Mock private AuditService auditService;
    @Mock private PlatformTransactionManager transactionManager;

    private ProductionCatalogService service;
    private Company company;
    private ProductionBrand brand;

    @BeforeEach
    void setUp() {
        service = spy(new ProductionCatalogService(
                companyContextService,
                brandRepository,
                productRepository,
                finishedGoodRepository,
                rawMaterialRepository,
                companyEntityLookup,
                companyDefaultAccountsService,
                catalogImportRepository,
                auditService,
                transactionManager));

        company = new Company();
        company.setCode("BBP");
        ReflectionTestUtils.setField(company, "id", 1L);
        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setName("BigBright");
        brand.setCode("BBP");
        brand.setCompany(company);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireProductionBrand(company, 11L)).thenReturn(brand);
    }

    @Test
    void createVariants_skipsWhenConcurrentInsertRaisesDataIntegrityDuplicate() {
        String sku = "BBP-PRIMER-RED-20L";
        when(productRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingProduct(sku)));
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(service)
                .createProduct(any());

        var response = service.createVariants(variantRequest());

        assertThat(response.created()).isZero();
        assertThat(response.skippedExisting()).isEqualTo(1);
        assertThat(response.variants()).isEmpty();
    }

    @Test
    void createVariants_skipsWhenConcurrentInsertRaisesSkuAlreadyExistsValidation() {
        String sku = "BBP-PRIMER-RED-20L";
        when(productRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingProduct(sku)));
        doThrow(new IllegalArgumentException("SKU " + sku + " already exists"))
                .when(service)
                .createProduct(any());

        var response = service.createVariants(variantRequest());

        assertThat(response.created()).isZero();
        assertThat(response.skippedExisting()).isEqualTo(1);
        assertThat(response.variants()).isEmpty();
    }

    @Test
    void createVariants_rethrowsValidationErrorsThatAreNotDuplicateConflicts() {
        String sku = "BBP-PRIMER-RED-20L";
        when(productRepository.findByCompanyAndSkuCode(company, sku)).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("Invalid GST rate"))
                .when(service)
                .createProduct(any());

        assertThatThrownBy(() -> service.createVariants(variantRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GST rate");
    }

    private BulkVariantRequest variantRequest() {
        return new BulkVariantRequest(
                11L,
                null,
                null,
                "Primer",
                "FINISHED_GOOD",
                List.of("Red"),
                List.of("20L"),
                null,
                "L",
                null,
                new BigDecimal("1200.00"),
                new BigDecimal("18"),
                BigDecimal.ZERO,
                new BigDecimal("1100.00"),
                Map.of()
        );
    }

    private ProductionProduct existingProduct(String sku) {
        ProductionProduct product = new ProductionProduct();
        product.setCompany(company);
        product.setBrand(brand);
        product.setSkuCode(sku);
        return product;
    }
}
