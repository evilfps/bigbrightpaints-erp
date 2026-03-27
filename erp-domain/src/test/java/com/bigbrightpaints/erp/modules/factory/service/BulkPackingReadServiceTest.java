package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;

@ExtendWith(MockitoExtension.class)
class BulkPackingReadServiceTest {

  @Mock private InventoryMovementRepository inventoryMovementRepository;
  @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private FinishedGoodRepository finishedGoodRepository;
  @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Mock private RawMaterialRepository rawMaterialRepository;
  @Mock private ProductionProductRepository productionProductRepository;
  @Mock private PackingProductSupport packingProductSupport;

  private BulkPackingReadService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new BulkPackingReadService(
            inventoryMovementRepository,
            rawMaterialMovementRepository,
            journalEntryRepository,
            finishedGoodRepository,
            rawMaterialBatchRepository,
            rawMaterialRepository,
            productionProductRepository,
            packingProductSupport);
    company = new Company();
    company.setCode("BULK-READ");
    company.setTimezone("UTC");
  }

  @Test
  void resolveIdempotentPack_returnsNullWhenNoInventoryOrRawMovementsExist() {
    RawMaterialBatch bulkBatch = rawBatch(10L, "BULK-10", "100", new BigDecimal("6"), null);
    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PACK-10"))
        .thenReturn(List.of());
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PACK-10"))
        .thenReturn(List.of());

    assertThat(service.resolveIdempotentPack(company, bulkBatch, "PACK-10")).isNull();
  }

  @Test
  void resolveIdempotentPack_throwsWhenRawMovementsExistWithoutInventoryMovements() {
    RawMaterialBatch bulkBatch = rawBatch(10L, "BULK-10", "100", new BigDecimal("6"), null);
    RawMaterialMovement rawIssue = new RawMaterialMovement();
    rawIssue.setMovementType("ISSUE");
    rawIssue.setRawMaterialBatch(bulkBatch);
    rawIssue.setQuantity(BigDecimal.ONE);

    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PACK-11"))
        .thenReturn(List.of());
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PACK-11"))
        .thenReturn(List.of(rawIssue));

    assertThatThrownBy(() -> service.resolveIdempotentPack(company, bulkBatch, "PACK-11"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Partial bulk pack detected");
  }

  @Test
  void resolveIdempotentPack_throwsWhenJournalEntryMissing() {
    RawMaterialBatch bulkBatch = rawBatch(11L, "BULK-11", "80", new BigDecimal("4"), null);
    InventoryMovement receipt = new InventoryMovement();
    receipt.setMovementType("RECEIPT");
    receipt.setFinishedGoodBatch(childBatch(50L, "FG-50", "B-50"));
    receipt.setQuantity(new BigDecimal("3"));
    receipt.setUnitCost(new BigDecimal("12"));

    RawMaterialMovement rawIssue = new RawMaterialMovement();
    rawIssue.setMovementType("ISSUE");
    rawIssue.setRawMaterialBatch(bulkBatch);
    rawIssue.setQuantity(new BigDecimal("3"));
    rawIssue.setUnitCost(new BigDecimal("4"));

    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PACK-12"))
        .thenReturn(List.of(receipt));
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PACK-12"))
        .thenReturn(List.of(rawIssue));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PACK-12"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolveIdempotentPack(company, bulkBatch, "PACK-12"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("inventory movements exist without journal");
  }

  @Test
  void resolveIdempotentPack_returnsDerivedResponseFromInventoryAndRawMovements() {
    RawMaterialBatch bulkBatch = rawBatch(12L, "BULK-12", "70", new BigDecimal("5"), null);

    InventoryMovement receipt = new InventoryMovement();
    receipt.setMovementType("RECEIPT");
    receipt.setFinishedGoodBatch(childBatch(51L, "FG-51", "B-51"));
    receipt.setQuantity(new BigDecimal("4"));
    receipt.setUnitCost(new BigDecimal("9"));

    InventoryMovement nonReceipt = new InventoryMovement();
    nonReceipt.setMovementType("ISSUE");
    nonReceipt.setFinishedGoodBatch(childBatch(52L, "FG-52", "B-52"));
    nonReceipt.setQuantity(new BigDecimal("2"));
    nonReceipt.setUnitCost(new BigDecimal("9"));

    RawMaterialMovement bulkIssue = new RawMaterialMovement();
    bulkIssue.setMovementType("ISSUE");
    bulkIssue.setRawMaterialBatch(bulkBatch);
    bulkIssue.setQuantity(new BigDecimal("4"));

    RawMaterialMovement packagingMovement = new RawMaterialMovement();
    packagingMovement.setMovementType("ISSUE");
    packagingMovement.setRawMaterialBatch(null);
    packagingMovement.setQuantity(new BigDecimal("2"));
    packagingMovement.setUnitCost(new BigDecimal("3.50"));

    RawMaterialBatch otherBatch = rawBatch(999L, "BULK-OTHER", "12", new BigDecimal("1"), null);
    RawMaterialMovement otherBatchMovement = new RawMaterialMovement();
    otherBatchMovement.setMovementType("ISSUE");
    otherBatchMovement.setRawMaterialBatch(otherBatch);
    otherBatchMovement.setQuantity(new BigDecimal("1"));
    otherBatchMovement.setUnitCost(new BigDecimal("2.00"));

    JournalEntry journalEntry = new JournalEntry();
    ReflectionTestUtils.setField(journalEntry, "id", 777L);

    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PACK-13"))
        .thenReturn(List.of(receipt, nonReceipt));
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PACK-13"))
        .thenReturn(List.of(bulkIssue, packagingMovement, otherBatchMovement));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PACK-13"))
        .thenReturn(Optional.of(journalEntry));

    BulkPackResponse response = service.resolveIdempotentPack(company, bulkBatch, "PACK-13");

    assertThat(response).isNotNull();
    assertThat(response.volumeDeducted()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(response.remainingBulkQuantity()).isEqualByComparingTo(new BigDecimal("70"));
    assertThat(response.packagingCost()).isEqualByComparingTo(new BigDecimal("9.00"));
    assertThat(response.journalEntryId()).isEqualTo(777L);
    assertThat(response.childBatches()).hasSize(1);
    assertThat(response.childBatches().getFirst().finishedGoodCode()).isEqualTo("FG-51");
  }

  @Test
  void resolveIdempotentPack_countsOnlyPositiveMatchingIssueVolume() {
    RawMaterialBatch bulkBatch = rawBatch(13L, "BULK-13", "45", new BigDecimal("7"), null);

    InventoryMovement receipt = new InventoryMovement();
    receipt.setMovementType("RECEIPT");
    receipt.setFinishedGoodBatch(childBatch(61L, "FG-61", "B-61"));
    receipt.setQuantity(new BigDecimal("2"));
    receipt.setUnitCost(new BigDecimal("11"));

    RawMaterialBatch nullIdBatch = new RawMaterialBatch();
    RawMaterialMovement nullIdIssue = new RawMaterialMovement();
    nullIdIssue.setMovementType("ISSUE");
    nullIdIssue.setRawMaterialBatch(nullIdBatch);
    nullIdIssue.setQuantity(new BigDecimal("6"));
    nullIdIssue.setUnitCost(new BigDecimal("1"));

    RawMaterialMovement negativeIssue = new RawMaterialMovement();
    negativeIssue.setMovementType("ISSUE");
    negativeIssue.setRawMaterialBatch(bulkBatch);
    negativeIssue.setQuantity(new BigDecimal("-3"));
    negativeIssue.setUnitCost(new BigDecimal("7"));

    RawMaterialMovement matchingPositiveIssue = new RawMaterialMovement();
    matchingPositiveIssue.setMovementType("ISSUE");
    matchingPositiveIssue.setRawMaterialBatch(bulkBatch);
    matchingPositiveIssue.setQuantity(new BigDecimal("2"));
    matchingPositiveIssue.setUnitCost(new BigDecimal("7"));

    RawMaterialMovement nonIssueMatchingBatch = new RawMaterialMovement();
    nonIssueMatchingBatch.setMovementType("RECEIPT");
    nonIssueMatchingBatch.setRawMaterialBatch(bulkBatch);
    nonIssueMatchingBatch.setQuantity(new BigDecimal("5"));
    nonIssueMatchingBatch.setUnitCost(new BigDecimal("9"));

    JournalEntry journalEntry = new JournalEntry();
    ReflectionTestUtils.setField(journalEntry, "id", 778L);

    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, "PACK-14"))
        .thenReturn(List.of(receipt));
    when(rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, "PACK-14"))
        .thenReturn(List.of(nullIdIssue, negativeIssue, matchingPositiveIssue, nonIssueMatchingBatch));
    when(journalEntryRepository.findByCompanyAndReferenceNumber(company, "PACK-14"))
        .thenReturn(Optional.of(journalEntry));

    BulkPackResponse response = service.resolveIdempotentPack(company, bulkBatch, "PACK-14");

    assertThat(response.volumeDeducted()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(response.packagingCost()).isEqualByComparingTo(new BigDecimal("6.00"));
  }

  @Test
  void listBulkBatches_returnsEmptyWhenBulkMaterialDoesNotExist() {
    FinishedGood fg = new FinishedGood();
    fg.setProductCode("FG-100");
    ReflectionTestUtils.setField(fg, "id", 100L);
    ProductionProduct parentProduct = productionProduct("FG-100");

    when(finishedGoodRepository.findByCompanyAndId(company, 100L)).thenReturn(Optional.of(fg));
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of(parentProduct));
    when(packingProductSupport.isMatchingChildSku("FG-100", "FG-100")).thenReturn(true);
    when(packingProductSupport.semiFinishedSku(parentProduct)).thenReturn("FG-100-BULK");
    when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "FG-100-BULK"))
        .thenReturn(Optional.empty());

    assertThat(service.listBulkBatches(company, 100L)).isEmpty();
  }

  @Test
  void listBulkBatches_throwsWhenFinishedGoodMissing() {
    when(finishedGoodRepository.findByCompanyAndId(company, 404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.listBulkBatches(company, 404L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Finished good not found");
  }

  @Test
  void listBulkBatches_filtersOutNonPositiveQuantityBatches() {
    FinishedGood fg = new FinishedGood();
    fg.setProductCode("FG-101-1L");
    ReflectionTestUtils.setField(fg, "id", 101L);

    ProductionProduct parentProduct = productionProduct("FG-101");
    RawMaterial bulkMaterial = new RawMaterial();
    bulkMaterial.setSku("FG-101-BULK");
    bulkMaterial.setName("FG 101 Bulk");
    bulkMaterial.setUnitType("L");

    RawMaterialBatch positive = rawBatch(201L, "RB-201", "7", new BigDecimal("8"), bulkMaterial);
    RawMaterialBatch zero = rawBatch(202L, "RB-202", "0", new BigDecimal("8"), bulkMaterial);
    RawMaterialBatch nullQty = rawBatch(203L, "RB-203", "1", new BigDecimal("8"), bulkMaterial);
    nullQty.setQuantity(null);

    when(finishedGoodRepository.findByCompanyAndId(company, 101L)).thenReturn(Optional.of(fg));
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of(parentProduct));
    when(packingProductSupport.isMatchingChildSku("FG-101-1L", "FG-101")).thenReturn(true);
    when(packingProductSupport.semiFinishedSku(parentProduct)).thenReturn("FG-101-BULK");
    when(rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, "FG-101-BULK"))
        .thenReturn(Optional.of(bulkMaterial));
    when(rawMaterialBatchRepository.findByRawMaterial(bulkMaterial))
        .thenReturn(List.of(positive, zero, nullQty));

    List<BulkPackResponse.ChildBatchDto> result = service.listBulkBatches(company, 101L);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().batchCode()).isEqualTo("RB-201");
    assertThat(result.getFirst().finishedGoodCode()).isEqualTo("FG-101-BULK");
  }

  @Test
  void listBulkBatches_returnsEmptyWhenNoCatalogProductMatchesFinishedGoodFamily() {
    FinishedGood fg = new FinishedGood();
    fg.setProductCode("FG-999-4L");
    ReflectionTestUtils.setField(fg, "id", 999L);
    ProductionProduct unrelated = productionProduct("FG-OTHER");

    when(finishedGoodRepository.findByCompanyAndId(company, 999L)).thenReturn(Optional.of(fg));
    when(productionProductRepository.findByCompanyOrderByProductNameAsc(company))
        .thenReturn(List.of(unrelated));
    when(packingProductSupport.isMatchingChildSku("FG-999-4L", "FG-OTHER")).thenReturn(false);

    assertThat(service.listBulkBatches(company, 999L)).isEmpty();
    verify(rawMaterialRepository, never()).findByCompanyAndSkuIgnoreCase(eq(company), eq("FG-999-BULK"));
  }

  @Test
  void listChildBatches_collectsUniqueReceiptChildrenAndSkipsInvalidRows() {
    RawMaterial parentMaterial = new RawMaterial();
    parentMaterial.setSku("FG-201-BULK");
    RawMaterialBatch parentBatch = rawBatch(301L, "RB-301", "22", new BigDecimal("5"), parentMaterial);

    RawMaterialMovement issueA = new RawMaterialMovement();
    issueA.setMovementType("ISSUE");
    issueA.setReferenceType(InventoryReference.PACKING_RECORD);
    issueA.setReferenceId("PACK-201-A");
    issueA.setRawMaterialBatch(parentBatch);

    RawMaterialMovement nonPackingIssue = new RawMaterialMovement();
    nonPackingIssue.setMovementType("ISSUE");
    nonPackingIssue.setReferenceType(InventoryReference.PRODUCTION_LOG);
    nonPackingIssue.setReferenceId("PROD-IGNORED");
    nonPackingIssue.setRawMaterialBatch(parentBatch);

    FinishedGoodBatch childA = childBatch(401L, "FG-401", "FG-401-B1");
    InventoryMovement receiptA = new InventoryMovement();
    receiptA.setMovementType("RECEIPT");
    receiptA.setFinishedGoodBatch(childA);
    receiptA.setQuantity(new BigDecimal("4"));
    receiptA.setUnitCost(new BigDecimal("6"));

    InventoryMovement duplicateReceiptA = new InventoryMovement();
    duplicateReceiptA.setMovementType("RECEIPT");
    duplicateReceiptA.setFinishedGoodBatch(childA);
    duplicateReceiptA.setQuantity(new BigDecimal("1"));
    duplicateReceiptA.setUnitCost(new BigDecimal("6"));

    InventoryMovement nonReceipt = new InventoryMovement();
    nonReceipt.setMovementType("ISSUE");
    nonReceipt.setFinishedGoodBatch(childBatch(402L, "FG-402", "FG-402-B1"));
    nonReceipt.setQuantity(BigDecimal.ONE);
    nonReceipt.setUnitCost(BigDecimal.ONE);

    InventoryMovement nullBatchReceipt = new InventoryMovement();
    nullBatchReceipt.setMovementType("RECEIPT");
    nullBatchReceipt.setFinishedGoodBatch(null);
    nullBatchReceipt.setQuantity(BigDecimal.ONE);
    nullBatchReceipt.setUnitCost(BigDecimal.ONE);

    FinishedGoodBatch nullIdBatch = new FinishedGoodBatch();
    nullIdBatch.setFinishedGood(childA.getFinishedGood());
    nullIdBatch.setBatchCode("FG-NULL-ID");
    InventoryMovement nullIdBatchReceipt = new InventoryMovement();
    nullIdBatchReceipt.setMovementType("RECEIPT");
    nullIdBatchReceipt.setFinishedGoodBatch(nullIdBatch);
    nullIdBatchReceipt.setQuantity(BigDecimal.ONE);
    nullIdBatchReceipt.setUnitCost(BigDecimal.ONE);

    when(rawMaterialBatchRepository.findByRawMaterial_CompanyAndId(company, 301L))
        .thenReturn(Optional.of(parentBatch));
    when(rawMaterialMovementRepository.findByRawMaterialBatchOrderByCreatedAtAsc(parentBatch))
        .thenReturn(List.of(issueA, nonPackingIssue));
    when(inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                eq(company), eq(InventoryReference.PACKING_RECORD), eq("PACK-201-A")))
        .thenReturn(List.of(receiptA, duplicateReceiptA, nonReceipt, nullBatchReceipt, nullIdBatchReceipt));

    List<BulkPackResponse.ChildBatchDto> result = service.listChildBatches(company, 301L);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().id()).isEqualTo(401L);
    assertThat(result.getFirst().finishedGoodCode()).isEqualTo("FG-401");
  }

  @Test
  void toChildBatchDto_handlesNullMovementSafely() {
    BulkPackResponse.ChildBatchDto dto = service.toChildBatchDto((InventoryMovement) null);

    assertThat(dto.id()).isNull();
    assertThat(dto.finishedGoodCode()).isNull();
    assertThat(dto.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(dto.unitCost()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(dto.totalValue()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void listChildBatches_throwsWhenParentBatchMissing() {
    when(rawMaterialBatchRepository.findByRawMaterial_CompanyAndId(company, 999L))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.listChildBatches(company, 999L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Parent batch not found");
  }

  private static RawMaterialBatch rawBatch(
      Long id, String batchCode, String quantity, BigDecimal unitCost, RawMaterial material) {
    RawMaterialBatch batch = new RawMaterialBatch();
    ReflectionTestUtils.setField(batch, "id", id);
    ReflectionTestUtils.setField(batch, "publicId", UUID.randomUUID());
    batch.setBatchCode(batchCode);
    batch.setQuantity(new BigDecimal(quantity));
    batch.setCostPerUnit(unitCost);
    batch.setRawMaterial(material);
    return batch;
  }

  private static FinishedGoodBatch childBatch(Long id, String productCode, String batchCode) {
    FinishedGood fg = new FinishedGood();
    ReflectionTestUtils.setField(fg, "id", id + 1000);
    fg.setProductCode(productCode);
    fg.setName("Child " + productCode);

    FinishedGoodBatch batch = new FinishedGoodBatch();
    ReflectionTestUtils.setField(batch, "id", id);
    ReflectionTestUtils.setField(batch, "publicId", UUID.randomUUID());
    batch.setFinishedGood(fg);
    batch.setBatchCode(batchCode);
    batch.setSizeLabel("1L");
    batch.setQuantityTotal(new BigDecimal("10"));
    batch.setQuantityAvailable(new BigDecimal("10"));
    batch.setUnitCost(new BigDecimal("6"));
    return batch;
  }

  private static ProductionProduct productionProduct(String skuCode) {
    ProductionProduct product = new ProductionProduct();
    product.setSkuCode(skuCode);
    product.setProductName("Product " + skuCode);
    return product;
  }
}
