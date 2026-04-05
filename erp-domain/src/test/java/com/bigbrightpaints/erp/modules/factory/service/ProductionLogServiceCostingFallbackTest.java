package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class ProductionLogServiceCostingFallbackTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyRepository companyRepository;
  @Mock private ProductionLogRepository logRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private AccountingFacade accountingFacade;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
  @Mock private CompanyClock companyClock;
  @Mock private PackingAllowedSizeService packingAllowedSizeService;

  private ProductionLogService productionLogService;

  @BeforeEach
  void setUp() {
    productionLogService =
        new ProductionLogService(
            companyContextService,
            companyRepository,
            logRepository,
            rawMaterialRepository,
            rawMaterialBatchRepository,
            rawMaterialMovementRepository,
            accountingFacade,
            companyEntityLookup,
            inventoryLookupService,
            companyClock,
            packingAllowedSizeService);
  }

  @Test
  void issueFromBatches_wacNullAverageFallsBackToBatchUnitCosts() {
    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setName("RM-BASE");
    rawMaterial.setCostingMethod("WAC");

    RawMaterialBatch batchA = new RawMaterialBatch();
    ReflectionTestUtils.setField(batchA, "id", 801L);
    batchA.setBatchCode("RM-A");
    batchA.setQuantity(new BigDecimal("2"));
    batchA.setCostPerUnit(new BigDecimal("1.00"));

    RawMaterialBatch batchB = new RawMaterialBatch();
    ReflectionTestUtils.setField(batchB, "id", 802L);
    batchB.setBatchCode("RM-B");
    batchB.setQuantity(new BigDecimal("2"));
    batchB.setCostPerUnit(new BigDecimal("5.00"));

    when(rawMaterialBatchRepository.findAvailableBatchesFIFO(rawMaterial))
        .thenReturn(List.of(batchA, batchB));
    when(rawMaterialBatchRepository.calculateWeightedAverageCost(rawMaterial)).thenReturn(null);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(
            eq(801L), argThat(qty -> qty.compareTo(new BigDecimal("2")) == 0)))
        .thenReturn(1);
    when(rawMaterialBatchRepository.deductQuantityIfSufficient(
            eq(802L), argThat(qty -> qty.compareTo(BigDecimal.ONE) == 0)))
        .thenReturn(1);

    List<?> issues =
        ReflectionTestUtils.invokeMethod(
            productionLogService, "issueFromBatches", rawMaterial, new BigDecimal("3"), "PROD-REF");

    assertThat(issues).hasSize(2);
    BigDecimal totalCost =
        issues.stream()
            .map(issue -> (BigDecimal) ReflectionTestUtils.getField(issue, "totalCost"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalCost).isEqualByComparingTo("7.00");

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(rawMaterialMovementRepository, times(2)).save(movementCaptor.capture());
    List<RawMaterialMovement> movements = new ArrayList<>(movementCaptor.getAllValues());
    assertThat(movements).hasSize(2);
    assertThat(movements)
        .allSatisfy(
            movement ->
                assertThat(movement.getReferenceType())
                    .isEqualTo(InventoryReference.PRODUCTION_LOG));
    assertThat(movements)
        .anySatisfy(
            movement -> {
              assertThat(movement.getUnitCost()).isEqualByComparingTo("1.00");
              assertThat(movement.getQuantity()).isEqualByComparingTo("2");
            });
    assertThat(movements)
        .anySatisfy(
            movement -> {
              assertThat(movement.getUnitCost()).isEqualByComparingTo("5.00");
              assertThat(movement.getQuantity()).isEqualByComparingTo("1");
            });
    verify(rawMaterialBatchRepository).calculateWeightedAverageCost(rawMaterial);
  }

  @Test
  void toDetailDto_allowsMissingProductFamilyContext() {
    Company company = new Company();
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 77L);
    log.setCompany(company);
    log.setProductionCode("PROD-077");
    ReflectionTestUtils.setField(log, "materials", new ArrayList<>());
    ReflectionTestUtils.setField(log, "packingRecords", new ArrayList<>());

    when(packingAllowedSizeService.listAllowedSellableSizes(company, log)).thenReturn(List.of());

    ProductionLogDetailDto dto =
        ReflectionTestUtils.invokeMethod(productionLogService, "toDetailDto", log);

    assertThat(dto.id()).isEqualTo(77L);
    assertThat(dto.productFamilyName()).isNull();
    assertThat(dto.allowedSellableSizes()).isEmpty();
  }

  @Test
  void initializeSemiFinishedRawMaterial_usesFgValuationFallbackAccount() {
    Company company = new Company();
    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer");
    product.setSkuCode("FG-PRIMER");
    product.setUnitOfMeasure("L");
    product.setMetadata(Map.of("fgValuationAccountId", "811"));

    when(rawMaterialRepository.save(org.mockito.ArgumentMatchers.any(RawMaterial.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    RawMaterial created =
        ReflectionTestUtils.invokeMethod(
            productionLogService,
            "initializeSemiFinishedRawMaterial",
            company,
            product,
            "FG-PRIMER-BULK");

    assertThat(created.getSku()).isEqualTo("FG-PRIMER-BULK");
    assertThat(created.getInventoryAccountId()).isEqualTo(811L);
    assertThat(created.getUnitType()).isEqualTo("L");
    assertThat(created.getMaterialType()).isEqualTo(MaterialType.PRODUCTION);
  }

  @Test
  void ensureSemiFinishedRawMaterial_returnsLockedExistingMaterial() {
    Company company = new Company();
    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer");
    product.setSkuCode("FG-LOCK");
    product.setMetadata(Map.of("semiFinishedAccountId", 900L));

    RawMaterial existing = new RawMaterial();
    existing.setSku("FG-LOCK-BULK");
    existing.setInventoryAccountId(900L);
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-LOCK-BULK"))
        .thenReturn(java.util.Optional.of(existing));

    RawMaterial resolved =
        ReflectionTestUtils.invokeMethod(
            productionLogService, "ensureSemiFinishedRawMaterial", company, product);

    assertThat(resolved).isSameAs(existing);
    verify(rawMaterialRepository, never())
        .save(org.mockito.ArgumentMatchers.any(RawMaterial.class));
  }

  @Test
  void initializeSemiFinishedRawMaterial_throwsWhenAccountMetadataMissing() {
    Company company = new Company();
    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer");
    product.setSkuCode("FG-NO-ACC");
    product.setMetadata(Map.of());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    productionLogService,
                    "initializeSemiFinishedRawMaterial",
                    company,
                    product,
                    "FG-NO-ACC-BULK"))
        .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
        .hasMessageContaining("missing semi-finished account metadata");
  }
}
