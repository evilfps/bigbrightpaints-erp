package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BulkPackingInventoryService {

    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final FinishedGoodsService finishedGoodsService;

    public BulkPackingInventoryService(FinishedGoodBatchRepository finishedGoodBatchRepository,
                                       FinishedGoodRepository finishedGoodRepository,
                                       InventoryMovementRepository inventoryMovementRepository,
                                       FinishedGoodsService finishedGoodsService) {
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.finishedGoodsService = finishedGoodsService;
    }

    public void consumeBulkInventory(FinishedGoodBatch bulkBatch,
                                     BigDecimal totalVolume,
                                     String packReference) {
        bulkBatch.setQuantityAvailable(bulkBatch.getQuantityAvailable().subtract(totalVolume));
        bulkBatch.setQuantityTotal(bulkBatch.getQuantityTotal().subtract(totalVolume));
        finishedGoodBatchRepository.save(bulkBatch);

        FinishedGood bulkFg = bulkBatch.getFinishedGood();
        bulkFg.setCurrentStock(bulkFg.getCurrentStock().subtract(totalVolume));
        finishedGoodRepository.save(bulkFg);

        InventoryMovement bulkIssue = new InventoryMovement();
        bulkIssue.setFinishedGood(bulkFg);
        bulkIssue.setFinishedGoodBatch(bulkBatch);
        bulkIssue.setReferenceType(InventoryReference.PACKING_RECORD);
        bulkIssue.setReferenceId(packReference);
        bulkIssue.setMovementType("ISSUE");
        bulkIssue.setQuantity(totalVolume);
        bulkIssue.setUnitCost(bulkBatch.getUnitCost());
        inventoryMovementRepository.save(bulkIssue);

        finishedGoodsService.invalidateWeightedAverageCost(bulkFg.getId());
    }
}
