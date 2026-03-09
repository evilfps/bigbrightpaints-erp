package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class InventoryValuationServiceTest {

    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock private InventoryMovementRepository inventoryMovementRepository;
    @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock private ProductionProductRepository productionProductRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;

    @InjectMocks private InventoryValuationService inventoryValuationService;

    @Test
    void currentSnapshot_fifoUsesAvailableBatchQuantityWhenAvailableIsLower() {
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
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("150.00");
        assertThat(snapshot.lowStockItems()).isZero();
        assertThat(snapshot.costingMethod()).isEqualTo("WEIGHTED_AVERAGE");
        assertThat(snapshot.items()).hasSize(1);
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
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("200.00");
        assertThat(snapshot.lowStockItems()).isZero();
        assertThat(snapshot.items()).hasSize(1);
    }

    @Test
    void currentSnapshot_fifoUsesAvailableStockForValuationEvenWithReservedQuantity() {
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
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("100.00");
        assertThat(snapshot.lowStockItems()).isZero();
        assertThat(snapshot.items()).hasSize(1);
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
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("15.00");
        assertThat(snapshot.lowStockItems()).isZero();
        assertThat(snapshot.items()).hasSize(1);
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);
        verify(rawMaterialBatchRepository, never()).findByRawMaterial(material);
    }

    @Test
    void currentSnapshot_rawMaterialWacFallsBackToBatchValuationWhenAverageMissing() {
        Company company = new Company();
        company.setCode("CR-RM-WAC-NULL");
        company.setName("CR RM WAC NULL");
        company.setTimezone("UTC");

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial material =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial();
        material.setCompany(company);
        material.setName("Pigment B");
        material.setSku("RM-WAC-NULL");
        material.setCurrentStock(new BigDecimal("5"));
        material.setCostingMethod("WAC");

        RawMaterialBatch early = new RawMaterialBatch();
        early.setBatchCode("RM-WAC-NULL-1");
        early.setReceivedAt(Instant.parse("2026-01-01T00:00:00Z"));
        early.setQuantity(new BigDecimal("2"));
        early.setCostPerUnit(new BigDecimal("4"));

        RawMaterialBatch late = new RawMaterialBatch();
        late.setBatchCode("RM-WAC-NULL-2");
        late.setReceivedAt(Instant.parse("2026-01-02T00:00:00Z"));
        late.setQuantity(new BigDecimal("3"));
        late.setCostPerUnit(new BigDecimal("6"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(material));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(material)).thenReturn(null);
        when(rawMaterialBatchRepository.findByRawMaterial(material)).thenReturn(List.of(late, early));
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.empty());

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.totalValue()).isEqualByComparingTo("26.00");
        assertThat(snapshot.lowStockItems()).isZero();
        assertThat(snapshot.items()).hasSize(1);
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(material);
        verify(rawMaterialBatchRepository).findByRawMaterial(material);
    }

    @Test
    void currentSnapshot_usesAccountingPeriodCostingMethodWhenPeriodExists() {
        Company company = new Company();
        company.setCode("CR-PERIOD-WAC");
        company.setName("CR Period WAC");
        company.setTimezone("UTC");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-PERIOD");
        finishedGood.setName("FG Period Method");
        finishedGood.setCostingMethod("FIFO");
        finishedGood.setCurrentStock(new BigDecimal("5"));

        FinishedGoodBatch first = new FinishedGoodBatch();
        first.setBatchCode("P1");
        first.setQuantityTotal(new BigDecimal("2"));
        first.setQuantityAvailable(new BigDecimal("2"));
        first.setUnitCost(new BigDecimal("10"));
        first.setManufacturedAt(Instant.parse("2026-01-01T00:00:00Z"));

        FinishedGoodBatch second = new FinishedGoodBatch();
        second.setBatchCode("P2");
        second.setQuantityTotal(new BigDecimal("3"));
        second.setQuantityAvailable(new BigDecimal("3"));
        second.setUnitCost(new BigDecimal("20"));
        second.setManufacturedAt(Instant.parse("2026-01-02T00:00:00Z"));

        AccountingPeriod period = new AccountingPeriod();
        period.setCostingMethod(CostingMethod.WEIGHTED_AVERAGE);

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of(finishedGood));
        when(finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood))
                .thenReturn(List.of(first, second));
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.of(period));

        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);

        assertThat(snapshot.costingMethod()).isEqualTo("WEIGHTED_AVERAGE");
        assertThat(snapshot.totalValue()).isEqualByComparingTo("80.00");
    }

    @Test
    void snapshotAsOf_appliesMovementAdjustmentsToStockAndValuation() {
        Company company = new Company();
        company.setCode("CR-ASOF");
        company.setName("CR ASOF");
        company.setTimezone("UTC");

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial material =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial();
        material.setCompany(company);
        material.setName("Pigment C");
        material.setSku("RM-ASOF");
        material.setCurrentStock(new BigDecimal("10"));
        material.setReorderLevel(new BigDecimal("9"));
        ReflectionTestUtils.setField(material, "id", 991L);

        RawMaterialBatch early = new RawMaterialBatch();
        early.setBatchCode("AS-1");
        early.setReceivedAt(Instant.parse("2026-03-01T00:00:00Z"));
        early.setQuantity(new BigDecimal("5"));
        early.setCostPerUnit(new BigDecimal("10"));

        RawMaterialBatch late = new RawMaterialBatch();
        late.setBatchCode("AS-2");
        late.setReceivedAt(Instant.parse("2026-03-02T00:00:00Z"));
        late.setQuantity(new BigDecimal("5"));
        late.setCostPerUnit(new BigDecimal("20"));

        AccountingPeriod period = new AccountingPeriod();
        period.setCostingMethod(CostingMethod.FIFO);

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement movement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        movement.setRawMaterial(material);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(new BigDecimal("2"));
        movement.setUnitCost(new BigDecimal("20"));

        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(material));
        when(rawMaterialBatchRepository.findByRawMaterial(material)).thenReturn(List.of(early, late));
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(java.util.Optional.of(period));
        when(rawMaterialMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.parse("2026-03-16T00:00:00Z")))
                .thenReturn(List.of(movement));
        when(inventoryMovementRepository.findByCompanyCreatedAtOnOrAfter(company, Instant.parse("2026-03-16T00:00:00Z")))
                .thenReturn(List.of());

        InventoryValuationService.InventorySnapshot snapshot =
                inventoryValuationService.snapshotAsOf(company, LocalDate.of(2026, 3, 15));

        assertThat(snapshot.totalValue()).isEqualByComparingTo("110.00");
        assertThat(snapshot.lowStockItems()).isEqualTo(1);
        assertThat(snapshot.items().getFirst().quantityOnHand()).isEqualByComparingTo("8");
        assertThat(snapshot.items().getFirst().unitCost()).isEqualByComparingTo("13.750000");
    }
}
