package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;

@Service
public class PackingInventoryService {

  private final CompanyContextService companyContextService;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;
  private final PackingProductSupport packingProductSupport;

  public PackingInventoryService(
      CompanyContextService companyContextService,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      PackingProductSupport packingProductSupport) {
    this.companyContextService = companyContextService;
    this.rawMaterialRepository = rawMaterialRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    this.packingProductSupport = packingProductSupport;
  }

  public SemiFinishedConsumption consumeSemiFinishedInventory(
      ProductionLog log, BigDecimal quantity, Long packingRecordId) {
    if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    Company company = companyContextService.requireCurrentCompany();
    RawMaterial semiFinished = lockSemiFinishedMaterial(company, log);
    RawMaterialBatch batch = lockSemiFinishedBatch(semiFinished, log.getProductionCode());
    if (batch.getQuantity().compareTo(quantity) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Insufficient semi-finished stock for " + log.getProductionCode());
    }

    batch.setQuantity(batch.getQuantity().subtract(quantity));
    rawMaterialBatchRepository.save(batch);

    semiFinished.setCurrentStock(
        semiFinished.getCurrentStock().subtract(quantity));
    rawMaterialRepository.save(semiFinished);

    RawMaterialMovement issue = new RawMaterialMovement();
    issue.setRawMaterial(semiFinished);
    issue.setRawMaterialBatch(batch);
    issue.setReferenceType(InventoryReference.PACKING_RECORD);
    issue.setReferenceId(log.getProductionCode() + "-PACK-" + packingRecordId);
    issue.setMovementType("ISSUE");
    issue.setQuantity(quantity);
    issue.setUnitCost(batch.getCostPerUnit() != null ? batch.getCostPerUnit() : BigDecimal.ZERO);
    RawMaterialMovement savedIssue = rawMaterialMovementRepository.save(issue);

    return new SemiFinishedConsumption(
        semiFinished, batch, savedIssue, issue.getUnitCost());
  }

  public void consumeSemiFinishedWastage(ProductionLog log, BigDecimal wastageQty) {
    if (wastageQty == null || wastageQty.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    Company company = companyContextService.requireCurrentCompany();
    RawMaterial semiFinished = lockSemiFinishedMaterial(company, log);
    RawMaterialBatch batch = lockSemiFinishedBatch(semiFinished, log.getProductionCode());
    if (batch.getQuantity().compareTo(wastageQty) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Insufficient semi-finished stock for wastage on " + log.getProductionCode());
    }

    batch.setQuantity(batch.getQuantity().subtract(wastageQty));
    rawMaterialBatchRepository.save(batch);

    semiFinished.setCurrentStock(
        semiFinished.getCurrentStock().subtract(wastageQty));
    rawMaterialRepository.save(semiFinished);

    RawMaterialMovement issue = new RawMaterialMovement();
    issue.setRawMaterial(semiFinished);
    issue.setRawMaterialBatch(batch);
    issue.setReferenceType(InventoryReference.PRODUCTION_LOG);
    issue.setReferenceId(log.getProductionCode());
    issue.setMovementType("WASTAGE");
    issue.setQuantity(wastageQty);
    issue.setUnitCost(batch.getCostPerUnit() != null ? batch.getCostPerUnit() : BigDecimal.ZERO);
    rawMaterialMovementRepository.save(issue);
  }

  private RawMaterial lockSemiFinishedMaterial(Company company, ProductionLog log) {
    String semiSku = packingProductSupport.semiFinishedSku(log.getProduct());
    return rawMaterialRepository
        .lockByCompanyAndSkuIgnoreCase(company, semiSku)
        .orElseThrow(
            () -> new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Semi-finished SKU "
                    + semiSku
                    + " not found for production "
                    + log.getProductionCode()));
  }

  private RawMaterialBatch lockSemiFinishedBatch(RawMaterial semiFinished, String productionCode) {
    return rawMaterialBatchRepository
        .lockByRawMaterialAndBatchCode(semiFinished, productionCode)
        .orElseThrow(
            () -> new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Semi-finished batch " + productionCode + " not found"));
  }

  public record SemiFinishedConsumption(
      RawMaterial semiFinished,
      RawMaterialBatch batch,
      RawMaterialMovement movement,
      BigDecimal unitCost) {
  }
}
