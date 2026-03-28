package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Service
public class PackingBatchService {

  private final FinishedGoodBatchRegistrar finishedGoodBatchRegistrar;
  private final PackingProductSupport packingProductSupport;
  private final AccountingFacade accountingFacade;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;

  public PackingBatchService(
      FinishedGoodBatchRegistrar finishedGoodBatchRegistrar,
      PackingProductSupport packingProductSupport,
      AccountingFacade accountingFacade,
      InventoryMovementRepository inventoryMovementRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository) {
    this.finishedGoodBatchRegistrar = finishedGoodBatchRegistrar;
    this.packingProductSupport = packingProductSupport;
    this.accountingFacade = accountingFacade;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
  }

  public FinishedGoodBatch registerFinishedGoodBatch(
      ProductionLog log,
      FinishedGood finishedGood,
      PackingRecord record,
      BigDecimal quantity,
      LocalDate packedDate,
      PackagingConsumptionResult packagingResult,
      PackingInventoryService.SemiFinishedConsumption semiFinished,
      SizeVariant sizeVariant) {
    BigDecimal baseUnitCost =
        semiFinished != null && semiFinished.unitCost() != null
            ? semiFinished.unitCost()
            : Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO);

    BigDecimal packagingCostPerUnit = BigDecimal.ZERO;
    if (packagingResult.isConsumed() && quantity.compareTo(BigDecimal.ZERO) > 0) {
      packagingCostPerUnit = packagingResult.totalCost().divide(quantity, 4, RoundingMode.HALF_UP);
    }
    BigDecimal totalUnitCost = baseUnitCost.add(packagingCostPerUnit);

    FinishedGoodBatchRegistrar.ReceiptRegistrationResult registrationResult =
        finishedGoodBatchRegistrar.registerReceipt(
            new FinishedGoodBatchRegistrar.ReceiptRegistrationRequest(
                finishedGood,
                null,
                quantity,
                totalUnitCost,
                log.getProducedAt(),
                packedDate,
                InventoryBatchSource.PRODUCTION,
                sizeVariant != null ? sizeVariant.getSizeLabel() : record.getPackagingSize(),
                InventoryReference.PACKING_RECORD,
                log.getProductionCode() + "-PACK-" + record.getId(),
                "RECEIPT"));

    postPackingSessionJournal(
        log,
        finishedGood,
        quantity,
        baseUnitCost,
        packagingCostPerUnit,
        packedDate,
        registrationResult.movement(),
        semiFinished);

    return registrationResult.batch();
  }

  private void postPackingSessionJournal(
      ProductionLog log,
      FinishedGood finishedGood,
      BigDecimal quantity,
      BigDecimal productionUnitCost,
      BigDecimal packagingCostPerUnit,
      LocalDate packedDate,
      InventoryMovement movement,
      PackingInventoryService.SemiFinishedConsumption semiFinished) {
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    Long wipAccountId = packingProductSupport.requireWipAccountId(log.getProduct());
    Long semiFinishedAccountId =
        semiFinished != null
            ? semiFinished.semiFinished().getInventoryAccountId()
            : packingProductSupport.requireSemiFinishedAccountId(log.getProduct());
    if (semiFinishedAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Semi-finished account is missing for " + log.getProductionCode());
    }

    Long fgAccountId = finishedGood.getValuationAccountId();
    if (fgAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Finished good " + finishedGood.getProductCode() + " missing valuation account");
    }

    BigDecimal productionValue =
        MoneyUtils.safeMultiply(productionUnitCost, quantity).setScale(2, RoundingMode.HALF_UP);
    BigDecimal packagingValue =
        MoneyUtils.safeMultiply(packagingCostPerUnit, quantity).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalValue = productionValue.add(packagingValue);
    if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    String reference = movement.getReferenceId();
    String memo = "FG receipt for " + log.getProductionCode() + " (qty: " + quantity + ")";

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    lines.add(
        new JournalEntryRequest.JournalLineRequest(fgAccountId, memo, totalValue, BigDecimal.ZERO));
    if (productionValue.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              semiFinishedAccountId, memo + " - semi-finished", BigDecimal.ZERO, productionValue));
    }
    if (packagingValue.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              wipAccountId, memo + " - packaging", BigDecimal.ZERO, packagingValue));
    }

    JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo, lines);
    if (entry == null) {
      return;
    }

    movement.setJournalEntryId(entry.id());
    inventoryMovementRepository.save(movement);
    if (semiFinished != null && semiFinished.movement() != null) {
      semiFinished.movement().setJournalEntryId(entry.id());
      rawMaterialMovementRepository.save(semiFinished.movement());
    }
  }
}
