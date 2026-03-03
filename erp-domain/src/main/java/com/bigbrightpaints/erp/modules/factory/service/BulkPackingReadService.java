package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class BulkPackingReadService {

    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;

    public BulkPackingReadService(InventoryMovementRepository inventoryMovementRepository,
                                  RawMaterialMovementRepository rawMaterialMovementRepository,
                                  JournalEntryRepository journalEntryRepository,
                                  FinishedGoodRepository finishedGoodRepository,
                                  FinishedGoodBatchRepository finishedGoodBatchRepository) {
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    }

    public BulkPackResponse resolveIdempotentPack(Company company,
                                                  FinishedGoodBatch bulkBatch,
                                                  String packReference) {
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        List<RawMaterialMovement> rawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        if (movements.isEmpty()) {
            if (!rawMovements.isEmpty()) {
                throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                        "Partial bulk pack detected for reference " + packReference
                                + " (packaging movements exist without inventory movements)");
            }
            return null;
        }

        BigDecimal volumeDeducted = movements.stream()
                .filter(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()))
                .map(InventoryMovement::getQuantity)
                .filter(qty -> qty != null && qty.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal packagingCost = rawMovements.stream()
                .map(movement -> safe(movement.getQuantity()).multiply(safe(movement.getUnitCost())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, COST_ROUNDING);

        List<BulkPackResponse.ChildBatchDto> childDtos = movements.stream()
                .filter(movement -> "RECEIPT".equalsIgnoreCase(movement.getMovementType()))
                .map(this::toChildBatchDto)
                .toList();

        Long journalEntryId = journalEntryRepository.findByCompanyAndReferenceNumber(company, packReference)
                .map(JournalEntry::getId)
                .orElse(null);
        if (journalEntryId == null) {
            throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                    "Partial bulk pack detected for reference " + packReference
                            + " (inventory movements exist without journal)");
        }

        return new BulkPackResponse(
                bulkBatch.getId(),
                bulkBatch.getBatchCode(),
                volumeDeducted,
                bulkBatch.getQuantityAvailable(),
                packagingCost,
                childDtos,
                journalEntryId,
                CompanyTime.now(company));
    }

    public List<BulkPackResponse.ChildBatchDto> listBulkBatches(Company company, Long finishedGoodId) {
        FinishedGood fg = finishedGoodRepository.findByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Finished good not found"));
        return finishedGoodBatchRepository.findAvailableBulkBatches(fg).stream()
                .map(this::toChildBatchDto)
                .toList();
    }

    public List<BulkPackResponse.ChildBatchDto> listChildBatches(Company company, Long parentBatchId) {
        FinishedGoodBatch parentBatch = finishedGoodBatchRepository.findByFinishedGood_CompanyAndId(company, parentBatchId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Parent batch not found"));
        return finishedGoodBatchRepository.findByParentBatch(parentBatch).stream()
                .map(this::toChildBatchDto)
                .toList();
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

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
