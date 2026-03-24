package com.bigbrightpaints.erp.modules.production.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
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
import com.bigbrightpaints.erp.modules.production.dto.BulkVariantResponse;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
    @Mock private SkuReadinessService skuReadinessService;
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
                skuReadinessService,
                transactionManager));

        company = new Company();
        company.setCode("BBP");
        ReflectionTestUtils.setField(company, "id", 1L);
        brand = new ProductionBrand();
        ReflectionTestUtils.setField(brand, "id", 11L);
        brand.setName("BigBright");
        brand.setCode("BBP");
        brand.setCompany(company);
        company.setDefaultInventoryAccountId(101L);
        company.setDefaultCogsAccountId(102L);
        company.setDefaultRevenueAccountId(103L);
        company.setDefaultTaxAccountId(104L);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyEntityLookup.requireProductionBrand(company, 11L)).thenReturn(brand);
        lenient().when(companyDefaultAccountsService.getDefaults())
                .thenReturn(new CompanyDefaultAccountsService.DefaultAccounts(101L, 102L, 103L, null, 104L));
        lenient().when(companyDefaultAccountsService.requireDefaults())
                .thenReturn(new CompanyDefaultAccountsService.DefaultAccounts(101L, 102L, 103L, null, 104L));
    }

    @Test
    void createVariants_failsClosedWhenConcurrentInsertRaisesDataIntegrityDuplicate() {
        String sku = "FG-BBP-PRIMER-RED-20L";
        when(productRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.of(existingProduct(sku)));
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(service)
                .createProduct(any());

        assertThatThrownBy(() -> service.createVariants(variantRequest()))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getDetails()).containsKey("conflicts");
                    @SuppressWarnings("unchecked")
                    List<BulkVariantResponse.VariantItem> conflicts =
                            (List<BulkVariantResponse.VariantItem>) ex.getDetails().get("conflicts");
                    assertThat(conflicts).hasSize(1);
                    assertThat(conflicts.get(0).sku()).isEqualTo(sku);
                    assertThat(conflicts.get(0).reason()).isEqualTo("CONCURRENT_SKU_CONFLICT");
                    @SuppressWarnings("unchecked")
                    List<BulkVariantResponse.VariantItem> wouldCreate =
                            (List<BulkVariantResponse.VariantItem>) ex.getDetails().get("wouldCreate");
                    assertThat(wouldCreate).isEmpty();
                });
    }

    @Test
    void createVariants_failsClosedWhenConcurrentInsertRaisesSkuAlreadyExistsValidation() {
        String sku = "FG-BBP-PRIMER-RED-20L";
        when(productRepository.findByCompanyAndSkuCode(company, sku))
                .thenReturn(Optional.of(existingProduct(sku)));
        doThrow(new IllegalArgumentException("SKU " + sku + " already exists"))
                .when(service)
                .createProduct(any());

        assertThatThrownBy(() -> service.createVariants(variantRequest()))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getDetails()).containsKey("conflicts");
                });
    }

    @Test
    void createVariants_rethrowsValidationErrorsThatAreNotDuplicateConflicts() {
        doThrow(new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid GST rate"))
                .when(service)
                .createProduct(any());

        assertThatThrownBy(() -> service.createVariants(variantRequest()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Invalid GST rate");
    }

    @Test
    void createVariants_rejectsColorWithoutSkuSafeCharacters() {
        assertThatThrownBy(() -> service.createVariants(variantRequest("Primer", List.of("###"), List.of("20L"), null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("color")
                .hasMessageContaining("SKU character");
    }

    @Test
    void createVariants_rejectsBaseProductNameWithoutSkuSafeCharacters() {
        assertThatThrownBy(() -> service.createVariants(variantRequest("%%%", List.of("Red"), List.of("20L"), null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("baseProductName")
                .hasMessageContaining("SKU character");
    }

    @Test
    void createVariants_ignoresLegacySkuPrefixAndUsesDeterministicItemClassPrefix() {
        BulkVariantResponse response = service.createVariants(
                variantRequest("Primer", List.of("Red"), List.of("20L"), "***"),
                true);

        assertThat(response.wouldCreate()).hasSize(1);
        assertThat(response.wouldCreate().getFirst().sku()).isEqualTo("FG-BBP-PRIMER-RED-20L");
    }

    @Test
    void createVariants_dryRun_doesNotPersistBrandWhenBrandIsNew() {
        when(brandRepository.findByCompanyAndCodeIgnoreCase(company, "NBR")).thenReturn(Optional.empty());
        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "New Brand")).thenReturn(Optional.empty());

        BulkVariantRequest request = new BulkVariantRequest(
                null,
                "New Brand",
                "NBR",
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

        BulkVariantResponse response = service.createVariants(request, true);
        assertThat(response.generated()).hasSize(1);
        assertThat(response.conflicts()).isEmpty();
        assertThat(response.wouldCreate()).hasSize(1);
        assertThat(response.created()).isEmpty();
        verify(brandRepository, never()).save(any());
        verify(service, never()).createProduct(any());
    }

    @Test
    void createVariants_dryRun_doesNotMutateExistingBrandName() {
        assertThat(brand.getName()).isEqualTo("BigBright");

        BulkVariantRequest request = new BulkVariantRequest(
                11L,
                "Renamed During DryRun",
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

        service.createVariants(request, true);
        assertThat(brand.getName()).isEqualTo("BigBright");
    }

    private BulkVariantRequest variantRequest() {
        return variantRequest("Primer", List.of("Red"), List.of("20L"), null);
    }

    private BulkVariantRequest variantRequest(String baseProductName,
                                              List<String> colors,
                                              List<String> sizes,
                                              String skuPrefix) {
        return new BulkVariantRequest(
                11L,
                null,
                null,
                baseProductName,
                "FINISHED_GOOD",
                colors,
                sizes,
                null,
                "L",
                skuPrefix,
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
