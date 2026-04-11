package com.bigbrightpaints.erp.modules.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItemRepository;

import jakarta.persistence.OptimisticLockException;

@ExtendWith(MockitoExtension.class)
class ProductionCatalogServiceRetryPolicyTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionBrandRepository brandRepository;
  @Mock private ProductionProductRepository productRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private InventoryReservationRepository inventoryReservationRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private PurchaseOrderRepository purchaseOrderRepository;
  @Mock private GoodsReceiptRepository goodsReceiptRepository;
  @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Mock private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private ProductionLogMaterialRepository productionLogMaterialRepository;
  @Mock private SalesOrderItemRepository salesOrderItemRepository;
  @Mock private CompanyScopedProductionLookupService productionLookupService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private CompanyDefaultAccountsService companyDefaultAccountsService;
  @Mock private CatalogImportRepository catalogImportRepository;
  @Mock private AuditService auditService;
  @Mock private SkuReadinessService skuReadinessService;
  @Mock private PlatformTransactionManager transactionManager;

  private ProductionCatalogService service;

  @BeforeEach
  void setUp() {
    service =
        new ProductionCatalogService(
            companyContextService,
            brandRepository,
            productRepository,
            finishedGoodRepository,
            rawMaterialRepository,
            finishedGoodBatchRepository,
            inventoryMovementRepository,
            inventoryReservationRepository,
            rawMaterialBatchRepository,
            rawMaterialMovementRepository,
            purchaseOrderRepository,
            goodsReceiptRepository,
            rawMaterialPurchaseRepository,
            packagingSizeMappingRepository,
            packingRecordRepository,
            productionLogMaterialRepository,
            salesOrderItemRepository,
            productionLookupService,
            accountingLookupService,
            companyDefaultAccountsService,
            catalogImportRepository,
            auditService,
            skuReadinessService,
            transactionManager);
  }

  @Test
  void importCatalog_existingRecordWithoutIdempotencyHashFailsClosedEvenWhenFileHashMatches()
      throws Exception {
    Company company = new Company();
    MockMultipartFile file = csvFile("brand,product_name\nSafari,Emulsion White\n");
    String fileHash = IdempotencyUtils.sha256Hex(file.getBytes());
    CatalogImport existing = catalogImport("catalog-key-001", null, fileHash);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(catalogImportRepository.findByCompanyAndIdempotencyKey(company, "catalog-key-001"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.importCatalog(file, "catalog-key-001"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key already used with different payload");

    verify(catalogImportRepository, never()).save(any(CatalogImport.class));
    verifyNoInteractions(transactionManager);
  }

  @Test
  void importCatalog_existingRecordWithoutPersistedHashesFailsClosedWithoutAutoRepair() {
    Company company = new Company();
    MockMultipartFile file = csvFile("brand,product_name\nSafari,Emulsion White\n");
    CatalogImport existing = catalogImport("catalog-key-002", null, null);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(catalogImportRepository.findByCompanyAndIdempotencyKey(company, "catalog-key-002"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.importCatalog(file, "catalog-key-002"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Idempotency key already used with different payload");

    verify(catalogImportRepository, never()).save(any(CatalogImport.class));
    verifyNoInteractions(transactionManager);
  }

  @Test
  void isRetryableImportFailure_treatsOptimisticLockingFailureAsRetryable() {
    boolean retryable =
        invokeIsRetryableImportFailure(
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
    RuntimeException wrapped =
        new RuntimeException("outer", new DataIntegrityViolationException("duplicate key"));

    boolean retryable = invokeIsRetryableImportFailure(wrapped);

    assertThat(retryable).isTrue();
  }

  @Test
  void isRetryableImportFailure_doesNotRetryPlainValidationErrors() {
    boolean retryable =
        invokeIsRetryableImportFailure(new IllegalArgumentException("invalid csv row"));

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

    when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda"))
        .thenReturn(Optional.of(brandA));

    Object context =
        newImportContext(
            brandsByName, productsBySku, productsByBrandName, new HashMap<Long, Long>());
    Object importRow = newImportRow(1L, null, "branda", "shared product");

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "evictRowCache", company, importRow, context);

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

    when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda"))
        .thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.of(productA));
    when(productRepository.findByCompanyAndId(company, 202L)).thenReturn(Optional.of(productB));

    Object context =
        newImportContext(
            brandsByName, productsBySku, productsByBrandName, new HashMap<Long, Long>());
    Object importRow = newImportRow(1L, null, "branda", "shared product");

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "evictRowCache", company, importRow, context);

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

    when(brandRepository.findByCompanyAndNameIgnoreCase(company, "branda"))
        .thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.empty());
    when(productRepository.findByCompanyAndId(company, 202L)).thenReturn(Optional.of(active));

    Object context =
        newImportContext(
            brandsByName, productsBySku, productsByBrandName, new HashMap<Long, Long>());
    Object importRow = newImportRow(1L, null, "branda", "shared product");

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "evictRowCache", company, importRow, context);

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

    Object context =
        newImportContext(
            brandsByName, productsBySku, productsByBrandName, new HashMap<Long, Long>());
    Object importRow = newImportRow(1L, null, "branda", "shared product");

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "evictRowCache", company, importRow, context);
    assertThat(productsByBrandName.values())
        .extracting(product -> product.getId())
        .containsExactly(202L);

    productsByBrandName.put(newProductKey(brandA.getId(), "shared product"), replayedBrandA);
    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "evictRowCache", company, importRow, context);

    assertThat(productsByBrandName.values())
        .extracting(product -> product.getId())
        .containsExactly(202L);
  }

  @Test
  void upsertProduct_existingRawMaterialImportSkipsMirrorCleanupWithoutReclassification()
      throws Exception {
    Company company = company(10L, null);
    ProductionBrand brand = brand(company, 11L, "BrandA", "BRA");
    ProductionProduct existing =
        product(company, brand, 41L, "RM-PRIMER", "Primer", "RAW_MATERIAL", Map.of());
    RawMaterial material = rawMaterial(company, 51L, "RM-PRIMER", "Primer", 999L);

    Map<String, ProductionBrand> brandsByName = new HashMap<>();
    brandsByName.put("branda", brand);
    Map<String, ProductionProduct> productsBySku = new HashMap<>();
    productsBySku.put("RM-PRIMER", existing);
    Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
    Object context =
        newImportContext(brandsByName, productsBySku, productsByBrandName, new HashMap<>());
    Object importRow =
        newImportRow(
            1L,
            newCatalogRow("BrandA", "Primer", "RM-PRIMER", "RAW_MATERIAL", "KG", Map.of()),
            "RM-PRIMER",
            "branda",
            "primer");

    when(productRepository.findByCompanyAndId(company, 41L)).thenReturn(Optional.of(existing));
    when(productRepository.save(existing)).thenReturn(existing);
    when(rawMaterialRepository.findByCompanyAndSku(company, "RM-PRIMER"))
        .thenReturn(Optional.of(material));

    Object outcome =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "upsertProduct", company, importRow, context);

    assertThat(outcome).isNotNull();
    verifyNoInteractions(finishedGoodRepository);
  }

  @Test
  void upsertProduct_existingFinishedGoodReclassificationDeletesOppositeMirror() throws Exception {
    Company company = company(10L, null);
    ProductionBrand brand = brand(company, 11L, "BrandA", "BRA");
    ProductionProduct existing =
        product(company, brand, 42L, "FG-PRIMER", "Primer", "FINISHED_GOOD", Map.of());
    RawMaterial material = rawMaterial(company, 52L, "FG-PRIMER", "Primer", 999L);
    FinishedGood staleFinishedGood = new FinishedGood();
    staleFinishedGood.setCompany(company);
    staleFinishedGood.setProductCode("FG-PRIMER");

    Map<String, ProductionBrand> brandsByName = new HashMap<>();
    brandsByName.put("branda", brand);
    Map<String, ProductionProduct> productsBySku = new HashMap<>();
    productsBySku.put("FG-PRIMER", existing);
    Map<Object, ProductionProduct> productsByBrandName = new HashMap<>();
    Object context =
        newImportContext(brandsByName, productsBySku, productsByBrandName, new HashMap<>());
    Object importRow =
        newImportRow(
            1L,
            newCatalogRow("BrandA", "Primer", "FG-PRIMER", "RAW_MATERIAL", "KG", Map.of()),
            "FG-PRIMER",
            "branda",
            "primer");

    when(productRepository.findByCompanyAndId(company, 42L)).thenReturn(Optional.of(existing));
    when(productRepository.save(existing)).thenReturn(existing);
    when(rawMaterialRepository.findByCompanyAndSku(company, "FG-PRIMER"))
        .thenReturn(Optional.of(material));
    when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-PRIMER"))
        .thenReturn(Optional.of(staleFinishedGood));

    Object outcome =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "upsertProduct", company, importRow, context);

    assertThat(outcome).isNotNull();
    verify(finishedGoodRepository).delete(staleFinishedGood);
  }

  @Test
  void upsertProduct_newRawMaterialImportStillCleansLegacyFinishedGoodMirror() throws Exception {
    Company company = company(10L, null);
    ProductionBrand brand = brand(company, 11L, "BrandA", "BRA");
    FinishedGood staleFinishedGood = new FinishedGood();
    staleFinishedGood.setCompany(company);
    staleFinishedGood.setProductCode("RM-NEW");

    Map<String, ProductionBrand> brandsByName = new HashMap<>();
    brandsByName.put("branda", brand);
    Object context =
        newImportContext(brandsByName, new HashMap<>(), new HashMap<>(), new HashMap<>());
    Object importRow =
        newImportRow(
            1L,
            newCatalogRow("BrandA", "Primer", "RM-NEW", "RAW_MATERIAL", "KG", Map.of()),
            "RM-NEW",
            "branda",
            "primer");

    when(productRepository.findByCompanyAndSkuCode(company, "RM-NEW")).thenReturn(Optional.empty());
    when(productRepository.findByBrandAndProductNameIgnoreCase(brand, "Primer"))
        .thenReturn(Optional.empty());
    when(productRepository.save(any()))
        .thenAnswer(
            invocation -> {
              ProductionProduct saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", 99L);
              return saved;
            });
    when(rawMaterialRepository.findByCompanyAndSku(company, "RM-NEW")).thenReturn(Optional.empty());
    when(finishedGoodRepository.findByCompanyAndProductCode(company, "RM-NEW"))
        .thenReturn(Optional.of(staleFinishedGood));

    Object outcome =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "upsertProduct", company, importRow, context);

    assertThat(outcome).isNotNull();
    verify(finishedGoodRepository).delete(staleFinishedGood);
  }

  private boolean invokeIsRetryableImportFailure(Throwable error) {
    Boolean retryable =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "isRetryableImportFailure", error);
    return Boolean.TRUE.equals(retryable);
  }

  private ProductionProduct invokeRefreshCachedProduct(Company company, ProductionProduct cached) {
    return com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "refreshCachedProductFromCurrentTransaction", company, cached);
  }

  private Object newProductKey(Long brandId, String productNameKey) throws Exception {
    Class<?> productKeyClass =
        Class.forName(ProductionCatalogService.class.getName() + "$ProductKey");
    Constructor<?> constructor = productKeyClass.getDeclaredConstructor(Long.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(brandId, productNameKey);
  }

  private Object newImportContext(
      Map<String, ProductionBrand> brandsByName,
      Map<String, ProductionProduct> productsBySku,
      Map<Object, ProductionProduct> productsByBrandName,
      Map<Long, Long> validatedRawMaterialInventoryAccounts)
      throws Exception {
    Class<?> importContextClass =
        Class.forName(ProductionCatalogService.class.getName() + "$ImportContext");
    Constructor<?> constructor =
        importContextClass.getDeclaredConstructor(
            Map.class, Map.class, Map.class, Map.class, Map.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        brandsByName,
        productsBySku,
        productsByBrandName,
        validatedRawMaterialInventoryAccounts,
        new HashMap<Long, Long>());
  }

  private Object newImportRow(
      long recordNumber, Object row, String sanitizedSku, String brandKey, String productKey)
      throws Exception {
    Class<?> catalogRowClass =
        Class.forName(ProductionCatalogService.class.getName() + "$CatalogRow");
    Class<?> importRowClass =
        Class.forName(ProductionCatalogService.class.getName() + "$ImportRow");
    Constructor<?> constructor =
        importRowClass.getDeclaredConstructor(
            long.class, catalogRowClass, String.class, String.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(recordNumber, row, sanitizedSku, brandKey, productKey);
  }

  private Object newImportRow(
      long recordNumber, String sanitizedSku, String brandKey, String productKey) throws Exception {
    return newImportRow(recordNumber, null, sanitizedSku, brandKey, productKey);
  }

  private Object newCatalogRow(
      String brand,
      String productName,
      String skuCode,
      String category,
      String unitOfMeasure,
      Map<String, Object> metadata)
      throws Exception {
    Class<?> catalogRowClass =
        Class.forName(ProductionCatalogService.class.getName() + "$CatalogRow");
    Constructor<?> constructor =
        catalogRowClass.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            BigDecimal.class,
            Map.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        brand,
        productName,
        skuCode,
        category,
        null,
        unitOfMeasure,
        null,
        null,
        null,
        null,
        null,
        metadata);
  }

  private CatalogImport catalogImport(
      String idempotencyKey, String idempotencyHash, String fileHash) {
    CatalogImport record = new CatalogImport();
    record.setIdempotencyKey(idempotencyKey);
    record.setIdempotencyHash(idempotencyHash);
    record.setFileHash(fileHash);
    return record;
  }

  private MockMultipartFile csvFile(String csv) {
    return new MockMultipartFile(
        "file", "catalog.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
  }

  private Company company(Long id, Long defaultInventoryAccountId) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    company.setDefaultInventoryAccountId(defaultInventoryAccountId);
    return company;
  }

  private ProductionBrand brand(Company company, Long id, String name, String code) {
    ProductionBrand brand = new ProductionBrand();
    ReflectionTestUtils.setField(brand, "id", id);
    brand.setCompany(company);
    brand.setName(name);
    brand.setCode(code);
    return brand;
  }

  private ProductionProduct product(
      Company company,
      ProductionBrand brand,
      Long id,
      String sku,
      String name,
      String category,
      Map<String, Object> metadata) {
    ProductionProduct product = new ProductionProduct();
    ReflectionTestUtils.setField(product, "id", id);
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode(sku);
    product.setProductName(name);
    product.setCategory(category);
    product.setUnitOfMeasure("KG");
    product.setMetadata(metadata);
    return product;
  }

  private RawMaterial rawMaterial(
      Company company, Long id, String sku, String name, Long inventoryAccountId) {
    RawMaterial material = new RawMaterial();
    ReflectionTestUtils.setField(material, "id", id);
    material.setCompany(company);
    material.setSku(sku);
    material.setName(name);
    material.setUnitType("KG");
    material.setInventoryAccountId(inventoryAccountId);
    material.setMaterialType(MaterialType.PRODUCTION);
    return material;
  }
}
