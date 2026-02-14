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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void currentSnapshot_fifoUsesOnHandBatchQuantityWhenAvailableIsLower() {
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
        depleted.setQuantityTotal(new BigDecimal("5"));
        depleted.setQuantityAvailable(BigDecimal.ZERO);
        depleted.setUnitCost(new BigDecimal("5"));
        depleted.setManufacturedAt(Instant.parse("2026-01-01T00:00:00Z"));

        FinishedGoodBatch remaining = new FinishedGoodBatch();
        remaining.setBatchCode("B2");
        remaining.setQuantityTotal(new BigDecimal("15"));
        remaining.setQuantityAvailable(new BigDecimal("15"));
        remaining.setUnitCost(new BigDecimal("10"));
        remaining.setManufacturedAt(Instant.parse("2026-01-02T00:00:00Z"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(depleted, remaining));

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("175.00");
        assertThat(snapshot.lowStockItems()).isZero();
    }

    @Test
    void currentSnapshot_lifoUsesNewestBatchesFirst() {
        Company company = new Company();
        company.setCode("CR-LIFO");
        company.setName("CR LIFO");
        company.setTimezone("UTC");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-LIFO");
        finishedGood.setName("FG LIFO");
        finishedGood.setCostingMethod("LIFO");
        finishedGood.setCurrentStock(new BigDecimal("20"));

        FinishedGoodBatch older = new FinishedGoodBatch();
        older.setBatchCode("L1");
        older.setQuantityTotal(new BigDecimal("20"));
        older.setQuantityAvailable(new BigDecimal("20"));
        older.setUnitCost(new BigDecimal("5"));
        older.setManufacturedAt(Instant.parse("2026-01-01T00:00:00Z"));

        FinishedGoodBatch newer = new FinishedGoodBatch();
        newer.setBatchCode("L2");
        newer.setQuantityTotal(new BigDecimal("20"));
        newer.setQuantityAvailable(new BigDecimal("20"));
        newer.setUnitCost(new BigDecimal("10"));
        newer.setManufacturedAt(Instant.parse("2026-01-02T00:00:00Z"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(older, newer));

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("200.00");
        assertThat(snapshot.lowStockItems()).isZero();
    }

    @Test
    void currentSnapshot_fifoIncludesReservedStockInValuation() {
        Company company = new Company();
        company.setCode("CR-RES");
        company.setName("CR RES");
        company.setTimezone("UTC");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-RES");
        finishedGood.setName("FG Reserved");
        finishedGood.setCostingMethod("FIFO");
        finishedGood.setCurrentStock(new BigDecimal("10"));
        finishedGood.setReservedStock(new BigDecimal("4"));

        FinishedGoodBatch first = new FinishedGoodBatch();
        first.setBatchCode("R1");
        first.setQuantityTotal(new BigDecimal("6"));
        first.setQuantityAvailable(new BigDecimal("2"));
        first.setUnitCost(new BigDecimal("10"));
        first.setManufacturedAt(Instant.parse("2026-01-01T00:00:00Z"));

        FinishedGoodBatch second = new FinishedGoodBatch();
        second.setBatchCode("R2");
        second.setQuantityTotal(new BigDecimal("4"));
        second.setQuantityAvailable(new BigDecimal("4"));
        second.setUnitCost(new BigDecimal("20"));
        second.setManufacturedAt(Instant.parse("2026-01-02T00:00:00Z"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(first, second));

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("140.00");
        assertThat(snapshot.lowStockItems()).isZero();
    }

    @Test
    void currentSnapshot_rawMaterialWacUsesWeightedAveragePath() {
        Company company = new Company();
        company.setCode("CR-RM-WAC");
        company.setName("CR RM WAC");
        company.setTimezone("UTC");

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial material =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial();
        material.setCompany(company);
        material.setName("Pigment A");
        material.setSku("RM-WAC");
        material.setCurrentStock(new BigDecimal("5"));
        material.setCostingMethod("weighted_average");

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(material));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(material)).thenReturn(new BigDecimal("3"));
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("15.00");
        assertThat(snapshot.lowStockItems()).isZero();
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);
        verify(rawMaterialBatchRepository, never()).findByRawMaterial(material);
    }
}
