package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
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

@Service
public class BulkPackingReadService {

  private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

  private final InventoryMovementRepository inventoryMovementRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final ProductionProductRepository productionProductRepository;
  private final PackingProductSupport packingProductSupport;

  public BulkPackingReadService(
      InventoryMovementRepository inventoryMovementRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      JournalEntryRepository journalEntryRepository,
      FinishedGoodRepository finishedGoodRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialRepository rawMaterialRepository,
      ProductionProductRepository productionProductRepository,
      PackingProductSupport packingProductSupport) {
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.productionProductRepository = productionProductRepository;
    this.packingProductSupport = packingProductSupport;
  }

  public BulkPackResponse resolveIdempotentPack(
      Company company, RawMaterialBatch bulkBatch, String packReference) {
    List<InventoryMovement> movements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.PACKING_RECORD, packReference);
    List<RawMaterialMovement> rawMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, packReference);
    if (movements.isEmpty()) {
      if (!rawMovements.isEmpty()) {
        throw new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Partial bulk pack detected for reference "
                + packReference
                + " (packaging movements exist without inventory movements)");
      }
      return null;
    }

    BigDecimal volumeDeducted =
        rawMovements.stream()
            .filter(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()))
            .filter(
                movement ->
                    movement.getRawMaterialBatch() != null
                        && movement.getRawMaterialBatch().getId() != null
                        && movement.getRawMaterialBatch().getId().equals(bulkBatch.getId()))
            .map(RawMaterialMovement::getQuantity)
            .filter(qty -> qty != null && qty.compareTo(BigDecimal.ZERO) > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal packagingCost =
        rawMovements.stream()
            .filter(
                movement ->
                    movement.getRawMaterialBatch() == null
                        || movement.getRawMaterialBatch().getId() == null
                        || !movement.getRawMaterialBatch().getId().equals(bulkBatch.getId()))
            .map(movement -> safe(movement.getQuantity()).multiply(safe(movement.getUnitCost())))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, COST_ROUNDING);

    List<BulkPackResponse.ChildBatchDto> childDtos =
        movements.stream()
            .filter(movement -> "RECEIPT".equalsIgnoreCase(movement.getMovementType()))
            .map(this::toChildBatchDto)
            .toList();

    Long journalEntryId =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, packReference)
            .map(JournalEntry::getId)
            .orElse(null);
    if (journalEntryId == null) {
      throw new ApplicationException(
          ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
          "Partial bulk pack detected for reference "
              + packReference
              + " (inventory movements exist without journal)");
    }

    return new BulkPackResponse(
        bulkBatch.getId(),
        bulkBatch.getBatchCode(),
        volumeDeducted,
        bulkBatch.getQuantity(),
        packagingCost,
        childDtos,
        journalEntryId,
        CompanyTime.now(company));
  }

  public List<BulkPackResponse.ChildBatchDto> listBulkBatches(
      Company company, Long finishedGoodId) {
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndId(company, finishedGoodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Finished good not found"));
    String semiFinishedSku = resolveSemiFinishedSku(company, fg.getProductCode());
    if (semiFinishedSku == null) {
      return List.of();
    }
    RawMaterial bulkMaterial =
        rawMaterialRepository.findByCompanyAndSkuIgnoreCase(company, semiFinishedSku).orElse(null);
    if (bulkMaterial == null) {
      return List.of();
    }
    return rawMaterialBatchRepository.findByRawMaterial(bulkMaterial).stream()
        .filter(
            batch ->
                batch.getQuantity() != null && batch.getQuantity().compareTo(BigDecimal.ZERO) > 0)
        .map(this::toBulkBatchDto)
        .toList();
  }

  private String resolveSemiFinishedSku(Company company, String finishedGoodCode) {
    Optional<ProductionProduct> sourceProduct =
        productionProductRepository.findByCompanyOrderByProductNameAsc(company).stream()
            .filter(
                product ->
                    packingProductSupport.isMatchingChildSku(
                        finishedGoodCode, product.getSkuCode()))
            .max(Comparator.comparingInt(product -> product.getSkuCode().length()));
    return sourceProduct.map(packingProductSupport::semiFinishedSku).orElse(null);
  }

  public List<BulkPackResponse.ChildBatchDto> listChildBatches(
      Company company, Long parentBatchId) {
    RawMaterialBatch parentBatch =
        rawMaterialBatchRepository
            .findByRawMaterial_CompanyAndId(company, parentBatchId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Parent batch not found"));
    List<RawMaterialMovement> parentIssues =
        rawMaterialMovementRepository
            .findByRawMaterialBatchOrderByCreatedAtAsc(parentBatch)
            .stream()
            .filter(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()))
            .filter(
                movement -> InventoryReference.PACKING_RECORD.equals(movement.getReferenceType()))
            .toList();
    Map<Long, BulkPackResponse.ChildBatchDto> childByBatchId = new LinkedHashMap<>();
    for (RawMaterialMovement issue : parentIssues) {
      List<InventoryMovement> movements =
          inventoryMovementRepository
              .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                  company, InventoryReference.PACKING_RECORD, issue.getReferenceId());
      for (InventoryMovement movement : movements) {
        if (!"RECEIPT".equalsIgnoreCase(movement.getMovementType())) {
          continue;
        }
        FinishedGoodBatch batch = movement.getFinishedGoodBatch();
        if (batch == null || batch.getId() == null) {
          continue;
        }
        childByBatchId.putIfAbsent(batch.getId(), toChildBatchDto(batch));
      }
    }
    return List.copyOf(childByBatchId.values());
  }

  public BulkPackResponse.ChildBatchDto toChildBatchDto(InventoryMovement movement) {
    FinishedGoodBatch batch = movement != null ? movement.getFinishedGoodBatch() : null;
    FinishedGood fg = batch != null ? batch.getFinishedGood() : null;
    BigDecimal quantity = safe(movement != null ? movement.getQuantity() : null);
    BigDecimal unitCost = safe(movement != null ? movement.getUnitCost() : null);
    return new BulkPackResponse.ChildBatchDto(
        batch != null ? batch.getId() : null,
        batch != null ? batch.getPublicId() : null,
        batch != null ? batch.getBatchCode() : null,
        fg != null ? fg.getId() : null,
        fg != null ? fg.getProductCode() : null,
        fg != null ? fg.getName() : null,
        batch != null ? batch.getSizeLabel() : null,
        quantity,
        unitCost,
        unitCost.multiply(quantity));
  }

  public BulkPackResponse.ChildBatchDto toChildBatchDto(FinishedGoodBatch batch) {
    FinishedGood fg = batch.getFinishedGood();
    BigDecimal quantity = safe(batch.getQuantityAvailable());
    BigDecimal unitCost = safe(batch.getUnitCost());
    return new BulkPackResponse.ChildBatchDto(
        batch.getId(),
        batch.getPublicId(),
        batch.getBatchCode(),
        fg.getId(),
        fg.getProductCode(),
        fg.getName(),
        batch.getSizeLabel(),
        quantity,
        unitCost,
        unitCost.multiply(quantity));
  }

  private BulkPackResponse.ChildBatchDto toBulkBatchDto(RawMaterialBatch batch) {
    RawMaterial material = batch.getRawMaterial();
    BigDecimal quantity = safe(batch.getQuantity());
    BigDecimal unitCost = safe(batch.getCostPerUnit());
    return new BulkPackResponse.ChildBatchDto(
        batch.getId(),
        batch.getPublicId(),
        batch.getBatchCode(),
        null,
        material != null ? material.getSku() : null,
        material != null ? material.getName() : null,
        material != null ? material.getUnitType() : null,
        quantity,
        unitCost,
        unitCost.multiply(quantity));
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
