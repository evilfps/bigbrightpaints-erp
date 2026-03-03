package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class PackingInventoryService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final PackingProductSupport packingProductSupport;

    public PackingInventoryService(CompanyContextService companyContextService,
                                  FinishedGoodRepository finishedGoodRepository,
                                  FinishedGoodBatchRepository finishedGoodBatchRepository,
                                  InventoryMovementRepository inventoryMovementRepository,
                                  FinishedGoodsService finishedGoodsService,
                                  PackingProductSupport packingProductSupport) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.finishedGoodsService = finishedGoodsService;
        this.packingProductSupport = packingProductSupport;
    }

    public SemiFinishedConsumption consumeSemiFinishedInventory(ProductionLog log,
                                                                BigDecimal quantity,
                                                                Long packingRecordId) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Company company = companyContextService.requireCurrentCompany();
        String semiSku = packingProductSupport.semiFinishedSku(log.getProduct());
        FinishedGood semiFinished = finishedGoodRepository.lockByCompanyAndProductCode(company, semiSku)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished SKU " + semiSku + " not found for production " + log.getProductionCode()));
        FinishedGoodBatch batch = finishedGoodBatchRepository
                .lockByFinishedGoodAndBatchCode(semiFinished, log.getProductionCode())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished batch " + log.getProductionCode() + " not found"));
        if (batch.getQuantityAvailable().compareTo(quantity) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Insufficient semi-finished stock for " + log.getProductionCode());
        }

        batch.allocate(quantity);
        BigDecimal total = Optional.ofNullable(batch.getQuantityTotal()).orElse(BigDecimal.ZERO);
        batch.setQuantityTotal(total.subtract(quantity));
        finishedGoodBatchRepository.save(batch);

        semiFinished.adjustStock(quantity.negate(), "PACKING");
        finishedGoodRepository.save(semiFinished);
        finishedGoodsService.invalidateWeightedAverageCost(semiFinished.getId());

        InventoryMovement issue = new InventoryMovement();
        issue.setFinishedGood(semiFinished);
        issue.setFinishedGoodBatch(batch);
        issue.setReferenceType(InventoryReference.PACKING_RECORD);
        issue.setReferenceId(log.getProductionCode() + "-PACK-" + packingRecordId);
        issue.setMovementType("ISSUE");
        issue.setQuantity(quantity);
        issue.setUnitCost(batch.getUnitCost());
        InventoryMovement savedIssue = inventoryMovementRepository.save(issue);

        return new SemiFinishedConsumption(semiFinished, batch, savedIssue, batch.getUnitCost());
    }

    public void consumeSemiFinishedWastage(ProductionLog log, BigDecimal wastageQty) {
        if (wastageQty == null || wastageQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Company company = companyContextService.requireCurrentCompany();
        String semiSku = packingProductSupport.semiFinishedSku(log.getProduct());
        FinishedGood semiFinished = finishedGoodRepository.lockByCompanyAndProductCode(company, semiSku)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished SKU " + semiSku + " not found for production " + log.getProductionCode()));
        FinishedGoodBatch batch = finishedGoodBatchRepository
                .lockByFinishedGoodAndBatchCode(semiFinished, log.getProductionCode())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished batch " + log.getProductionCode() + " not found"));
        if (batch.getQuantityAvailable().compareTo(wastageQty) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Insufficient semi-finished stock for wastage on " + log.getProductionCode());
        }

        batch.allocate(wastageQty);
        BigDecimal total = Optional.ofNullable(batch.getQuantityTotal()).orElse(BigDecimal.ZERO);
        batch.setQuantityTotal(total.subtract(wastageQty));
        finishedGoodBatchRepository.save(batch);

        semiFinished.adjustStock(wastageQty.negate(), "PACKING_WASTAGE");
        finishedGoodRepository.save(semiFinished);
        finishedGoodsService.invalidateWeightedAverageCost(semiFinished.getId());

        InventoryMovement issue = new InventoryMovement();
        issue.setFinishedGood(semiFinished);
        issue.setFinishedGoodBatch(batch);
        issue.setReferenceType(InventoryReference.PRODUCTION_LOG);
        issue.setReferenceId(log.getProductionCode());
        issue.setMovementType("WASTAGE");
        issue.setQuantity(wastageQty);
        issue.setUnitCost(batch.getUnitCost());
        inventoryMovementRepository.save(issue);
    }

    public record SemiFinishedConsumption(FinishedGood semiFinished,
                                          FinishedGoodBatch batch,
                                          InventoryMovement movement,
                                          BigDecimal unitCost) {
    }
}
