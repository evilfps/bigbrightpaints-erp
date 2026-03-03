package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class PackingJournalLinkHelper {

    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public PackingJournalLinkHelper(RawMaterialMovementRepository rawMaterialMovementRepository,
                                    InventoryMovementRepository inventoryMovementRepository) {
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
    }

    public void linkPackagingMovementsToJournal(Company company, String referenceId, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        linkRawMaterialMovements(company, referenceId, journalEntryId);
        linkInventoryMovements(company, referenceId, journalEntryId);
    }

    private void linkRawMaterialMovements(Company company, String referenceId, Long journalEntryId) {
        List<RawMaterialMovement> movements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.PACKING_RECORD,
                        referenceId);
        if (movements.isEmpty()) {
            return;
        }
        List<RawMaterialMovement> toUpdate = new ArrayList<>();
        for (RawMaterialMovement movement : movements) {
            Long existingJournalId = movement.getJournalEntryId();
            if (existingJournalId == null) {
                movement.setJournalEntryId(journalEntryId);
                toUpdate.add(movement);
                continue;
            }
            if (!Objects.equals(existingJournalId, journalEntryId)) {
                throw new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Packing reference " + referenceId + " already linked to journal " + existingJournalId);
            }
        }
        if (!toUpdate.isEmpty()) {
            rawMaterialMovementRepository.saveAll(toUpdate);
        }
    }

    private void linkInventoryMovements(Company company, String referenceId, Long journalEntryId) {
        List<InventoryMovement> inventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        referenceId);
        if (inventoryMovements.isEmpty()) {
            return;
        }
        List<InventoryMovement> toUpdate = new ArrayList<>();
        for (InventoryMovement movement : inventoryMovements) {
            Long existingJournalId = movement.getJournalEntryId();
            if (existingJournalId == null) {
                movement.setJournalEntryId(journalEntryId);
                toUpdate.add(movement);
                continue;
            }
            if (!Objects.equals(existingJournalId, journalEntryId)) {
                throw new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Packing reference " + referenceId + " already linked to journal " + existingJournalId);
            }
        }
        if (!toUpdate.isEmpty()) {
            inventoryMovementRepository.saveAll(toUpdate);
        }
    }
}
