package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryValuationServiceTest {

    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;
    @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;

    @InjectMocks private InventoryValuationService inventoryValuationService;

    @Test
    void currentSnapshot_fifoUsesRemainingBatchQuantity() {
        Company company = new Company();
        company.setCode("CR-FIFO");
        company.setName("CR FIFO");
        company.setTimezone("UTC");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-FIFO");
        finishedGood.setName("FG FIFO");
        finishedGood.setCostingMethod("FIFO");
        finishedGood.setCurrentStock(new BigDecimal("20"));

        FinishedGoodBatch depleted = new FinishedGoodBatch();
        depleted.setBatchCode("B1");
        depleted.setQuantityTotal(new BigDecimal("100"));
        depleted.setQuantityAvailable(BigDecimal.ZERO);
        depleted.setUnitCost(new BigDecimal("5"));
        depleted.setManufacturedAt(Instant.parse("2026-01-01T00:00:00Z"));

        FinishedGoodBatch remaining = new FinishedGoodBatch();
        remaining.setBatchCode("B2");
        remaining.setQuantityTotal(new BigDecimal("20"));
        remaining.setQuantityAvailable(new BigDecimal("20"));
        remaining.setUnitCost(new BigDecimal("10"));
        remaining.setManufacturedAt(Instant.parse("2026-01-02T00:00:00Z"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(depleted, remaining));

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("200.00");
        assertThat(snapshot.lowStockItems()).isZero();
    }
}
