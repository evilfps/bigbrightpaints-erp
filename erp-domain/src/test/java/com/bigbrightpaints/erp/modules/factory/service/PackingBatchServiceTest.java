package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@ExtendWith(MockitoExtension.class)
class PackingBatchServiceTest {

  @Mock private FinishedGoodBatchRegistrar finishedGoodBatchRegistrar;
  @Mock private PackingProductSupport packingProductSupport;
  @Mock private AccountingFacade accountingFacade;
  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;

  private PackingBatchService service;

  @BeforeEach
  void setUp() {
    service =
        new PackingBatchService(
            finishedGoodBatchRegistrar,
            packingProductSupport,
            accountingFacade,
            inventoryMovementRepository,
            rawMaterialMovementRepository);
  }

  @Test
  void registerFinishedGoodBatch_usesFallbackSemiFinishedAccountWhenConsumptionMissing() {
    ProductionLog log = productionLog("PROD-201", "5.00");
    FinishedGood finishedGood = finishedGood("FG-201", 710L);
    PackingRecord record = packingRecord(201L, "1L");
    InventoryMovement movement = inventoryMovement("PROD-201-PACK-201");
    FinishedGoodBatch savedBatch = new FinishedGoodBatch();

    when(finishedGoodBatchRegistrar.registerReceipt(any()))
        .thenReturn(new FinishedGoodBatchRegistrar.ReceiptRegistrationResult(savedBatch, movement));
    when(packingProductSupport.requireWipAccountId(log.getProduct())).thenReturn(610L);
    when(packingProductSupport.requireSemiFinishedAccountId(log.getProduct())).thenReturn(620L);
    when(accountingFacade.postPackingJournal(any(), any(), any(), any()))
        .thenReturn(journalEntry(9901L));

    FinishedGoodBatch result =
        service.registerFinishedGoodBatch(
            log,
            finishedGood,
            record,
            new BigDecimal("2"),
            LocalDate.of(2026, 3, 27),
            new PackagingConsumptionResult(
                true, new BigDecimal("4.00"), new BigDecimal("2"), Map.of(), null),
            null,
            null);

    assertThat(result).isSameAs(savedBatch);
    verify(inventoryMovementRepository).save(movement);
    verify(rawMaterialMovementRepository, never()).save(any());
  }

  @Test
  void registerFinishedGoodBatch_linksSemiFinishedMovementWhenConsumptionPresent() {
    ProductionLog log = productionLog("PROD-202", "3.00");
    FinishedGood finishedGood = finishedGood("FG-202", 720L);
    PackingRecord record = packingRecord(202L, "1L");
    InventoryMovement movement = inventoryMovement("PROD-202-PACK-202");
    FinishedGoodBatch savedBatch = new FinishedGoodBatch();

    RawMaterial semiFinishedMaterial = new RawMaterial();
    semiFinishedMaterial.setInventoryAccountId(630L);
    RawMaterialMovement semiFinishedMovement = new RawMaterialMovement();
    PackingInventoryService.SemiFinishedConsumption semiFinishedConsumption =
        new PackingInventoryService.SemiFinishedConsumption(
            semiFinishedMaterial, null, semiFinishedMovement, new BigDecimal("3.00"));

    when(finishedGoodBatchRegistrar.registerReceipt(any()))
        .thenReturn(new FinishedGoodBatchRegistrar.ReceiptRegistrationResult(savedBatch, movement));
    when(packingProductSupport.requireWipAccountId(log.getProduct())).thenReturn(640L);
    when(accountingFacade.postPackingJournal(any(), any(), any(), any()))
        .thenReturn(journalEntry(9902L));

    service.registerFinishedGoodBatch(
        log,
        finishedGood,
        record,
        new BigDecimal("2"),
        LocalDate.of(2026, 3, 27),
        new PackagingConsumptionResult(true, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null),
        semiFinishedConsumption,
        null);

    ArgumentCaptor<FinishedGoodBatchRegistrar.ReceiptRegistrationRequest> requestCaptor =
        ArgumentCaptor.forClass(FinishedGoodBatchRegistrar.ReceiptRegistrationRequest.class);
    verify(finishedGoodBatchRegistrar).registerReceipt(requestCaptor.capture());
    assertThat(requestCaptor.getValue().unitCost()).isEqualByComparingTo(new BigDecimal("3.00"));
    verify(inventoryMovementRepository).save(movement);
    verify(rawMaterialMovementRepository).save(semiFinishedMovement);
  }

  @Test
  void registerFinishedGoodBatch_rejectsMissingSemiFinishedAccountFromConsumption() {
    ProductionLog log = productionLog("PROD-203", "3.00");
    FinishedGood finishedGood = finishedGood("FG-203", 730L);
    PackingRecord record = packingRecord(203L, "1L");
    InventoryMovement movement = inventoryMovement("PROD-203-PACK-203");
    FinishedGoodBatch savedBatch = new FinishedGoodBatch();

    RawMaterial semiFinishedMaterial = new RawMaterial();
    semiFinishedMaterial.setInventoryAccountId(null);
    PackingInventoryService.SemiFinishedConsumption semiFinishedConsumption =
        new PackingInventoryService.SemiFinishedConsumption(
            semiFinishedMaterial, null, new RawMaterialMovement(), new BigDecimal("3.00"));

    when(finishedGoodBatchRegistrar.registerReceipt(any()))
        .thenReturn(new FinishedGoodBatchRegistrar.ReceiptRegistrationResult(savedBatch, movement));
    when(packingProductSupport.requireWipAccountId(log.getProduct())).thenReturn(650L);

    assertThatThrownBy(
            () ->
                service.registerFinishedGoodBatch(
                    log,
                    finishedGood,
                    record,
                    new BigDecimal("2"),
                    LocalDate.of(2026, 3, 27),
                    new PackagingConsumptionResult(
                        true, new BigDecimal("1.00"), new BigDecimal("1"), Map.of(), null),
                    semiFinishedConsumption,
                    null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Semi-finished account is missing");
  }

  private static ProductionLog productionLog(String code, String unitCost) {
    ProductionProduct product = new ProductionProduct();
    product.setProductName("Product " + code);
    product.setSkuCode("SKU-" + code);
    product.setMetadata(Map.of("wipAccountId", 610L, "semiFinishedAccountId", 620L));

    ProductionLog log = new ProductionLog();
    log.setProductionCode(code);
    log.setProduct(product);
    log.setProducedAt(Instant.parse("2026-03-27T08:00:00Z"));
    log.setUnitCost(new BigDecimal(unitCost));
    return log;
  }

  private static FinishedGood finishedGood(String sku, Long valuationAccountId) {
    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setProductCode(sku);
    finishedGood.setValuationAccountId(valuationAccountId);
    finishedGood.setName("Finished " + sku);
    return finishedGood;
  }

  private static PackingRecord packingRecord(Long id, String packagingSize) {
    PackingRecord record = new PackingRecord();
    ReflectionTestUtils.setField(record, "id", id);
    record.setPackagingSize(packagingSize);
    return record;
  }

  private static InventoryMovement inventoryMovement(String referenceId) {
    InventoryMovement movement = new InventoryMovement();
    movement.setReferenceId(referenceId);
    return movement;
  }

  private static JournalEntryDto journalEntry(Long id) {
    return new JournalEntryDto(
        id,
        null,
        "PACK-" + id,
        LocalDate.of(2026, 3, 27),
        "memo",
        "POSTED",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
