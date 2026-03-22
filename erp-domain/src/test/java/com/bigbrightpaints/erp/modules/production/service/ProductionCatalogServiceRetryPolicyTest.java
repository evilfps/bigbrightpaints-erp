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
import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionCatalogServiceRetryPolicyTest {

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

    @BeforeEach
    void setUp() {
        service = new ProductionCatalogService(
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
                transactionManager);
    }

    @Test
    void isRetryableImportFailure_treatsOptimisticLockingFailureAsRetryable() {
        boolean retryable = invokeIsRetryableImportFailure(
                new ObjectOptimisticLockingFailureException(ProductionProduct.class, 42L));

        assertThat(retryable).isTrue();
    }

    @Test
    void isRetryableImportFailure_treatsNestedOptimisticLockExceptionAsRetryable() {
        RuntimeException wrapped = new RuntimeException("outer", new OptimisticLockException("stale"));

        boolean retryable = invokeIsRetryableImportFailure(wrapped);

        assertThat(retryable).isTrue();
    }

    @Test
    void isRetryableImportFailure_treatsNestedDataIntegrityViolationAsRetryable() {
        RuntimeException wrapped = new RuntimeException("outer",
                new DataIntegrityViolationException("duplicate key"));

        boolean retryable = invokeIsRetryableImportFailure(wrapped);

        assertThat(retryable).isTrue();
    }

    @Test
    void isRetryableImportFailure_doesNotRetryPlainValidationErrors() {
        boolean retryable = invokeIsRetryableImportFailure(new IllegalArgumentException("invalid csv row"));

        assertThat(retryable).isFalse();
    }

    @Test
    void refreshCachedProductFromCurrentTransaction_returnsNullWhenStaleCachedIdIsMissing() {
        Company company = new Company();
        ProductionProduct cached = new ProductionProduct();
        ReflectionTestUtils.setField(cached, "id", 99L);
        when(productRepository.findByCompanyAndId(company, 99L)).thenReturn(Optional.empty());

        ProductionProduct refreshed = invokeRefreshCachedProduct(company, cached);

        assertThat(refreshed).isNull();
    }

    @Test
    void refreshCachedProductFromCurrentTransaction_reloadsManagedProductWhenPresent() {
        Company company = new Company();
        ProductionProduct cached = new ProductionProduct();
        ReflectionTestUtils.setField(cached, "id", 100L);
        ProductionProduct managed = new ProductionProduct();
        ReflectionTestUtils.setField(managed, "id", 100L);
        when(productRepository.findByCompanyAndId(company, 100L)).thenReturn(Optional.of(managed));

        ProductionProduct refreshed = invokeRefreshCachedProduct(company, cached);

        assertThat(refreshed).isSameAs(managed);
    }

    @Test
    void refreshCachedProductFromCurrentTransaction_keepsTransientProductWithoutLookup() {
        Company company = new Company();
        ProductionProduct transientProduct = new ProductionProduct();

        ProductionProduct refreshed = invokeRefreshCachedProduct(company, transientProduct);

        assertThat(refreshed).isSameAs(transientProduct);
        verifyNoInteractions(productRepository);
    }

    @Test
    void evictRowCache_resolvesBrandIdWithoutBrandCacheAndAvoidsCrossBrandPurge() throws Exception {
        Company company = new Company();
        ProductionBrand brandA = new ProductionBrand();
        ReflectionTestUtils.setField(brandA, "id", 11L);
        brandA.setName("BrandA");
        ProductionBrand brandB = new ProductionBrand();
        ReflectionTestUtils.setField(brandB, "id", 22L);
        brandB.setName("BrandB");

        ProductionProduct productA = new ProductionProduct();
        ReflectionTestUtils.setField(productA, "id", 101L);
        productA.setBrand(brandA);
        productA.setProductName("Shared Product");
        productA.setSkuCode("SKU-A");

        ProductionProduct productB = new ProductionProduct();
        ReflectionTestUtils.setField(productB, "id", 202L);
        productB.setBrand(brandB);
        productB.setProductName("Shared Product");
        productB.setSkuCode("SKU-B");

        Map<String, ProductionBrand> brandsByName = new HashMap<>();

        Map<String, ProductionProduct> productsBySku = new HashMap<>();
        productsBySku.put("SKU-A", productA);
        productsBySku.put("SKU-B", productB);

        Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
        productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), productA);
        productsByBrandName.put(newProductKey(brandB.getId(), "shared product"), productB);

        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda")).thenReturn(Optional.of(brandA));

        Object context = newImportContext(
                brandsByName,
                productsBySku,
                productsByBrandName,
                new HashMap<Long, Long>());
        Object importRow = newImportRow(1L, null, "branda", "shared product");

        ReflectionTestUtils.invokeMethod(service, "evictRowCache", company, importRow, context);

        assertThat(productsByBrandName.values())
                .extracting(product -> product.getId())
                .containsExactly(202L);
        assertThat(productsBySku).containsKeys("SKU-A", "SKU-B");
    }

    @Test
    void evictRowCache_skipsBrandNamePurgeWhenBrandCannotBeResolved() throws Exception {
        Company company = new Company();
        ProductionBrand brandA = new ProductionBrand();
        ReflectionTestUtils.setField(brandA, "id", 11L);
        brandA.setName("BrandA");
        ProductionBrand brandB = new ProductionBrand();
        ReflectionTestUtils.setField(brandB, "id", 22L);
        brandB.setName("BrandB");

        ProductionProduct productA = new ProductionProduct();
        ReflectionTestUtils.setField(productA, "id", 101L);
        productA.setBrand(brandA);
        productA.setProductName("Shared Product");
        productA.setSkuCode("SKU-A");

        ProductionProduct productB = new ProductionProduct();
        ReflectionTestUtils.setField(productB, "id", 202L);
        productB.setBrand(brandB);
        productB.setProductName("Shared Product");
        productB.setSkuCode("SKU-B");

        Map<String, ProductionBrand> brandsByName = new HashMap<>();
        Map<String, ProductionProduct> productsBySku = new HashMap<>();
        productsBySku.put("SKU-A", productA);
        productsBySku.put("SKU-B", productB);
        Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
        productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), productA);
        productsByBrandName.put(newProductKey(brandB.getId(), "shared product"), productB);

        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda")).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.of(productA));
        when(productRepository.findByCompanyAndId(company, 202L)).thenReturn(Optional.of(productB));

        Object context = newImportContext(
                brandsByName,
                productsBySku,
                productsByBrandName,
                new HashMap<Long, Long>());
        Object importRow = newImportRow(1L, null, "branda", "shared product");

        ReflectionTestUtils.invokeMethod(service, "evictRowCache", company, importRow, context);

        assertThat(productsByBrandName.values())
                .extracting(product -> product.getId())
                .containsExactlyInAnyOrder(101L, 202L);
        assertThat(productsBySku).containsKeys("SKU-A", "SKU-B");
    }

    @Test
    void evictRowCache_prunesDriftedRowsWhenBrandCannotBeResolved() throws Exception {
        Company company = new Company();
        ProductionBrand brandA = new ProductionBrand();
        ReflectionTestUtils.setField(brandA, "id", 11L);
        brandA.setName("BrandA");
        ProductionBrand brandB = new ProductionBrand();
        ReflectionTestUtils.setField(brandB, "id", 22L);
        brandB.setName("BrandB");

        ProductionProduct drifted = new ProductionProduct();
        ReflectionTestUtils.setField(drifted, "id", 101L);
        drifted.setBrand(brandA);
        drifted.setProductName("Shared Product");
        drifted.setSkuCode("SKU-A");

        ProductionProduct active = new ProductionProduct();
        ReflectionTestUtils.setField(active, "id", 202L);
        active.setBrand(brandB);
        active.setProductName("Shared Product");
        active.setSkuCode("SKU-B");

        Map<String, ProductionBrand> brandsByName = new HashMap<>();
        Map<String, ProductionProduct> productsBySku = new HashMap<>();
        productsBySku.put("SKU-A", drifted);
        productsBySku.put("SKU-B", active);
        Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
        productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), drifted);
        productsByBrandName.put(newProductKey(brandB.getId(), "shared product"), active);

        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda")).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndId(company, 202L)).thenReturn(Optional.of(active));

        Object context = newImportContext(
                brandsByName,
                productsBySku,
                productsByBrandName,
                new HashMap<Long, Long>());
        Object importRow = newImportRow(1L, null, "branda", "shared product");

        ReflectionTestUtils.invokeMethod(service, "evictRowCache", company, importRow, context);

        assertThat(productsByBrandName.values())
                .extracting(product -> product.getId())
                .containsExactly(202L);
        assertThat(productsBySku).containsKeys("SKU-A", "SKU-B");
    }

    @Test
    void evictRowCache_retryReplayAfterDriftPruneClearsResolvedBrandEntry() throws Exception {
        Company company = new Company();
        ProductionBrand brandA = new ProductionBrand();
        ReflectionTestUtils.setField(brandA, "id", 11L);
        brandA.setName("BrandA");
        ProductionBrand brandB = new ProductionBrand();
        ReflectionTestUtils.setField(brandB, "id", 22L);
        brandB.setName("BrandB");

        ProductionProduct drifted = new ProductionProduct();
        ReflectionTestUtils.setField(drifted, "id", 101L);
        drifted.setBrand(brandA);
        drifted.setProductName("Shared Product");
        drifted.setSkuCode("SKU-A");

        ProductionProduct active = new ProductionProduct();
        ReflectionTestUtils.setField(active, "id", 202L);
        active.setBrand(brandB);
        active.setProductName("Shared Product");
        active.setSkuCode("SKU-B");

        ProductionProduct replayedBrandA = new ProductionProduct();
        ReflectionTestUtils.setField(replayedBrandA, "id", 303L);
        replayedBrandA.setBrand(brandA);
        replayedBrandA.setProductName("Shared Product");
        replayedBrandA.setSkuCode("SKU-A-REPLAY");

        Map<String, ProductionBrand> brandsByName = new HashMap<>();
        Map<String, ProductionProduct> productsBySku = new HashMap<>();
        productsBySku.put("SKU-A", drifted);
        productsBySku.put("SKU-B", active);
        Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
        productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), drifted);
        productsByBrandName.put(newProductKey(brandB.getId(), "shared product"), active);

        when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(brandA));
        when(productRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.empty());
        when(productRepository.findByCompanyAndId(company, 202L)).thenReturn(Optional.of(active));

        Object context = newImportContext(
                brandsByName,
                productsBySku,
                productsByBrandName,
                new HashMap<Long, Long>());
        Object importRow = newImportRow(1L, null, "branda", "shared product");

        ReflectionTestUtils.invokeMethod(service, "evictRowCache", company, importRow, context);
        assertThat(productsByBrandName.values())
                .extracting(product -> product.getId())
                .containsExactly(202L);

        productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), replayedBrandA);
        ReflectionTestUtils.invokeMethod(service, "evictRowCache", company, importRow, context);

        assertThat(productsByBrandName.values())
                .extracting(product -> product.getId())
                .containsExactly(202L);
    }

    private boolean invokeIsRetryableImportFailure(Throwable error) {
        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryableImportFailure", error);
        return Boolean.TRUE.equals(retryable);
    }

    private ProductionProduct invokeRefreshCachedProduct(Company company, ProductionProduct cached) {
        return ReflectionTestUtils.invokeMethod(service, "refreshCachedProductFromCurrentTransaction", company, cached);
    }

    private Object newProductKey(Long brandId, String productNameKey) throws Exception {
        Class<?> productKeyClass = Class.forName(ProductionCatalogService.class.getName() + "$ProductKey");
        Constructor<?> constructor = productKeyClass.getDeclaredConstructor(Long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(brandId, productNameKey);
    }

    private Object newImportContext(Map<String, ProductionBrand> brandsByName,
                                    Map<String, ProductionProduct> productsBySku,
                                    Map<Object, ProductionProduct> productsByBrandName,
                                    Map<Long, Long> validatedRawMaterialInventoryAccounts) throws Exception {
        Class<?> importContextClass = Class.forName(ProductionCatalogService.class.getName() + "$ImportContext");
        Constructor<?> constructor = importContextClass.getDeclaredConstructor(
                Map.class,
                Map.class,
                Map.class,
                Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                brandsByName,
                productsBySku,
                productsByBrandName,
                validatedRawMaterialInventoryAccounts);
    }

    private Object newImportRow(long recordNumber,
                                String sanitizedSku,
                                String brandKey,
                                String productKey) throws Exception {
        Class<?> catalogRowClass = Class.forName(ProductionCatalogService.class.getName() + "$CatalogRow");
        Class<?> importRowClass = Class.forName(ProductionCatalogService.class.getName() + "$ImportRow");
        Constructor<?> constructor = importRowClass.getDeclaredConstructor(
                long.class,
                catalogRowClass,
                String.class,
                String.class,
                String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(recordNumber, null, sanitizedSku, brandKey, productKey);
    }
}
