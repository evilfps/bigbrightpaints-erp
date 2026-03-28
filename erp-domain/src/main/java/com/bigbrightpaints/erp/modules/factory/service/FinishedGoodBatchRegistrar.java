package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;

@Service
public class FinishedGoodBatchRegistrar {

  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final FinishedGoodsService finishedGoodsService;
  private final BatchNumberService batchNumberService;

  public FinishedGoodBatchRegistrar(
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      FinishedGoodRepository finishedGoodRepository,
      InventoryMovementRepository inventoryMovementRepository,
      FinishedGoodsService finishedGoodsService,
      BatchNumberService batchNumberService) {
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.finishedGoodRepository = finishedGoodRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.finishedGoodsService = finishedGoodsService;
    this.batchNumberService = batchNumberService;
  }

  public ReceiptRegistrationResult registerReceipt(ReceiptRegistrationRequest request) {
    FinishedGoodBatch batch = createBatch(request);
    FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

    FinishedGood finishedGood = request.finishedGood();
    BigDecimal currentStock =
        Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
    finishedGood.setCurrentStock(currentStock.add(request.quantity()));
    finishedGoodRepository.save(finishedGood);
    finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(finishedGood);
    movement.setFinishedGoodBatch(savedBatch);
    movement.setReferenceType(
        Optional.ofNullable(request.referenceType()).orElse(InventoryReference.PACKING_RECORD));
    movement.setReferenceId(request.referenceId());
    movement.setMovementType(Optional.ofNullable(request.movementType()).orElse("RECEIPT"));
    movement.setQuantity(request.quantity());
    movement.setUnitCost(request.unitCost());
    InventoryMovement savedMovement = inventoryMovementRepository.save(movement);

    return new ReceiptRegistrationResult(savedBatch, savedMovement);
  }

  private FinishedGoodBatch createBatch(ReceiptRegistrationRequest request) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(request.finishedGood());
    batch.setBatchCode(resolveBatchCode(request));
    batch.setQuantityTotal(request.quantity());
    batch.setQuantityAvailable(request.quantity());
    batch.setUnitCost(request.unitCost());
    batch.setManufacturedAt(request.manufacturedAt());
    batch.setSource(Optional.ofNullable(request.source()).orElse(InventoryBatchSource.PRODUCTION));
    batch.setSizeLabel(request.sizeLabel());
    return batch;
  }

  private String resolveBatchCode(ReceiptRegistrationRequest request) {
    if (request.batchCode() != null && !request.batchCode().isBlank()) {
      return request.batchCode().trim();
    }
    return batchNumberService.nextFinishedGoodBatchCode(
        request.finishedGood(), request.batchDate());
  }

  public record ReceiptRegistrationRequest(
      FinishedGood finishedGood,
      String batchCode,
      BigDecimal quantity,
      BigDecimal unitCost,
      Instant manufacturedAt,
      LocalDate batchDate,
      InventoryBatchSource source,
      String sizeLabel,
      String referenceType,
      String referenceId,
      String movementType) {}

  public record ReceiptRegistrationResult(FinishedGoodBatch batch, InventoryMovement movement) {}
}
