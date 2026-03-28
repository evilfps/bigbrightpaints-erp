package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@ExtendWith(MockitoExtension.class)
class PackingInventoryServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private PackingProductSupport packingProductSupport;

  private PackingInventoryService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new PackingInventoryService(
            companyContextService,
            rawMaterialRepository,
            rawMaterialBatchRepository,
            rawMaterialMovementRepository,
            packingProductSupport);
    company = new Company();
    company.setCode("PK-COMP");
  }

  @Test
  void consumeSemiFinishedInventory_returnsNullForNonPositiveQuantity() {
    ProductionLog log = productionLog("PROD-1", "FG-BASE");

    assertThat(service.consumeSemiFinishedInventory(log, BigDecimal.ZERO, 10L)).isNull();
    verifyNoInteractions(rawMaterialRepository, rawMaterialBatchRepository, rawMaterialMovementRepository);
  }

  @Test
  void consumeSemiFinishedInventory_consumesRawMaterialAndWritesIssueMovement() {
    ProductionLog log = productionLog("PROD-2", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "12");
    RawMaterialBatch batch = rawBatch(material, "PROD-2", "9", new BigDecimal("4.20"));

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-2"))
        .thenReturn(Optional.of(batch));

    PackingInventoryService.SemiFinishedConsumption consumed =
        service.consumeSemiFinishedInventory(log, new BigDecimal("3"), 7L);

    assertThat(consumed).isNotNull();
    assertThat(consumed.unitCost()).isEqualByComparingTo(new BigDecimal("4.20"));
    assertThat(batch.getQuantity()).isEqualByComparingTo(new BigDecimal("6"));
    assertThat(material.getCurrentStock()).isEqualByComparingTo(new BigDecimal("9"));
    verify(rawMaterialBatchRepository).save(batch);
    verify(rawMaterialRepository).save(material);

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(rawMaterialMovementRepository).save(movementCaptor.capture());
    RawMaterialMovement movement = movementCaptor.getValue();
    assertThat(movement.getMovementType()).isEqualTo("ISSUE");
    assertThat(movement.getReferenceId()).isEqualTo("PROD-2-PACK-7");
    assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(movement.getUnitCost()).isEqualByComparingTo(new BigDecimal("4.20"));
  }

  @Test
  void consumeSemiFinishedInventory_usesZeroCostWhenBatchCostMissing() {
    ProductionLog log = productionLog("PROD-3", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "5");
    RawMaterialBatch batch = rawBatch(material, "PROD-3", "5", null);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-3"))
        .thenReturn(Optional.of(batch));

    PackingInventoryService.SemiFinishedConsumption consumed =
        service.consumeSemiFinishedInventory(log, new BigDecimal("1"), 9L);

    assertThat(consumed.unitCost()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void consumeSemiFinishedInventory_throwsWhenStockIsInsufficient() {
    ProductionLog log = productionLog("PROD-4", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "2");
    RawMaterialBatch batch = rawBatch(material, "PROD-4", "2", new BigDecimal("2"));

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-4"))
        .thenReturn(Optional.of(batch));

    assertThatThrownBy(() -> service.consumeSemiFinishedInventory(log, new BigDecimal("3"), 1L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Insufficient semi-finished stock");
  }

  @Test
  void consumeSemiFinishedInventory_throwsWhenSemiFinishedSkuMissing() {
    ProductionLog log = productionLog("PROD-5", "FG-BASE");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.consumeSemiFinishedInventory(log, BigDecimal.ONE, 1L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Semi-finished SKU FG-BASE-BULK not found");
  }

  @Test
  void consumeSemiFinishedInventory_throwsWhenSemiFinishedBatchMissing() {
    ProductionLog log = productionLog("PROD-6", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "10");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-6"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.consumeSemiFinishedInventory(log, BigDecimal.ONE, 1L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Semi-finished batch PROD-6 not found");
  }

  @Test
  void consumeSemiFinishedWastage_ignoresNullOrNonPositiveQuantity() {
    ProductionLog log = productionLog("PROD-7", "FG-BASE");

    service.consumeSemiFinishedWastage(log, null);
    service.consumeSemiFinishedWastage(log, BigDecimal.ZERO);

    verifyNoInteractions(rawMaterialRepository, rawMaterialBatchRepository, rawMaterialMovementRepository);
  }

  @Test
  void consumeSemiFinishedWastage_writesWastageMovementAndUpdatesStock() {
    ProductionLog log = productionLog("PROD-8", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "11");
    RawMaterialBatch batch = rawBatch(material, "PROD-8", "11", new BigDecimal("3.10"));

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-8"))
        .thenReturn(Optional.of(batch));

    service.consumeSemiFinishedWastage(log, new BigDecimal("2"));

    assertThat(batch.getQuantity()).isEqualByComparingTo(new BigDecimal("9"));
    assertThat(material.getCurrentStock()).isEqualByComparingTo(new BigDecimal("9"));

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(rawMaterialMovementRepository).save(movementCaptor.capture());
    RawMaterialMovement movement = movementCaptor.getValue();
    assertThat(movement.getMovementType()).isEqualTo("WASTAGE");
    assertThat(movement.getReferenceId()).isEqualTo("PROD-8");
    assertThat(movement.getUnitCost()).isEqualByComparingTo(new BigDecimal("3.10"));
  }

  @Test
  void consumeSemiFinishedWastage_usesZeroCostWhenBatchCostMissing() {
    ProductionLog log = productionLog("PROD-9", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "4");
    RawMaterialBatch batch = rawBatch(material, "PROD-9", "4", null);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-9"))
        .thenReturn(Optional.of(batch));

    service.consumeSemiFinishedWastage(log, BigDecimal.ONE);

    ArgumentCaptor<RawMaterialMovement> movementCaptor =
        ArgumentCaptor.forClass(RawMaterialMovement.class);
    verify(rawMaterialMovementRepository).save(movementCaptor.capture());
    assertThat(movementCaptor.getValue().getUnitCost()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void consumeSemiFinishedWastage_throwsWhenStockIsInsufficient() {
    ProductionLog log = productionLog("PROD-10", "FG-BASE");
    RawMaterial material = rawMaterial("FG-BASE-BULK", "1");
    RawMaterialBatch batch = rawBatch(material, "PROD-10", "1", new BigDecimal("2"));

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(packingProductSupport.semiFinishedSku(log.getProduct())).thenReturn("FG-BASE-BULK");
    when(rawMaterialRepository.lockByCompanyAndSkuIgnoreCase(company, "FG-BASE-BULK"))
        .thenReturn(Optional.of(material));
    when(rawMaterialBatchRepository.lockByRawMaterialAndBatchCode(material, "PROD-10"))
        .thenReturn(Optional.of(batch));

    assertThatThrownBy(() -> service.consumeSemiFinishedWastage(log, new BigDecimal("3")))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Insufficient semi-finished stock for wastage");
  }

  private static ProductionLog productionLog(String productionCode, String skuCode) {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode(skuCode);

    ProductionLog log = new ProductionLog();
    log.setProductionCode(productionCode);
    log.setProduct(product);
    return log;
  }

  private static RawMaterial rawMaterial(String sku, String currentStock) {
    RawMaterial material = new RawMaterial();
    material.setSku(sku);
    material.setCurrentStock(new BigDecimal(currentStock));
    return material;
  }

  private static RawMaterialBatch rawBatch(
      RawMaterial material, String batchCode, String quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode(batchCode);
    batch.setQuantity(new BigDecimal(quantity));
    batch.setCostPerUnit(costPerUnit);
    return batch;
  }
}
