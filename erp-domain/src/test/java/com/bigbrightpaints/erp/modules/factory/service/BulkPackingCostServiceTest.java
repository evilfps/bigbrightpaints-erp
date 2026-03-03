package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkPackingCostServiceTest {

    @Mock private PackagingMaterialService packagingMaterialService;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock private RawMaterialMovementRepository rawMaterialMovementRepository;

    private BulkPackingCostService bulkPackingCostService;
    private Company company;

    @BeforeEach
    void setup() {
        bulkPackingCostService = new BulkPackingCostService(
                packagingMaterialService,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
    }

    @Test
    void consumePackagingMaterials_wacNullAverageFallsBackToBatchUnitCosts() {
        RawMaterial packaging = new RawMaterial();
        ReflectionTestUtils.setField(packaging, "id", 901L);
        packaging.setCompany(company);
        packaging.setSku("PKG-901");
        packaging.setCurrentStock(new BigDecimal("10"));
        packaging.setInventoryAccountId(700L);
        packaging.setCostingMethod("WAC");

        RawMaterialBatch batchA = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchA, "id", 501L);
        batchA.setBatchCode("PKG-A");
        batchA.setQuantity(new BigDecimal("2"));
        batchA.setCostPerUnit(new BigDecimal("1.00"));

        RawMaterialBatch batchB = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchB, "id", 502L);
        batchB.setBatchCode("PKG-B");
        batchB.setQuantity(new BigDecimal("2"));
        batchB.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialRepository.lockByCompanyAndId(company, 901L)).thenReturn(Optional.of(packaging));
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(packaging)).thenReturn(List.of(batchA, batchB));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(packaging)).thenReturn(null);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(501L),
                argThat(qty -> qty.compareTo(new BigDecimal("2")) == 0))).thenReturn(1);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(502L),
                argThat(qty -> qty.compareTo(BigDecimal.ONE) == 0))).thenReturn(1);
        when(rawMaterialMovementRepository.save(any(RawMaterialMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BulkPackCostSummary summary = bulkPackingCostService.consumePackagingMaterials(
                company,
                List.of(new BulkPackRequest.MaterialConsumption(901L, new BigDecimal("3"), "UNIT")),
                "PACK-REF");

        assertThat(summary.totalCost()).isEqualByComparingTo("7.00");
        assertThat(summary.accountTotals()).containsEntry(700L, new BigDecimal("7.00"));

        ArgumentCaptor<RawMaterialMovement> movementCaptor = ArgumentCaptor.forClass(RawMaterialMovement.class);
        verify(rawMaterialMovementRepository, times(2)).save(movementCaptor.capture());
        List<RawMaterialMovement> movements = movementCaptor.getAllValues();
        assertThat(movements).anySatisfy(movement -> {
            assertThat(movement.getUnitCost()).isEqualByComparingTo("1.00");
            assertThat(movement.getQuantity()).isEqualByComparingTo("2");
        });
        assertThat(movements).anySatisfy(movement -> {
            assertThat(movement.getUnitCost()).isEqualByComparingTo("5.00");
            assertThat(movement.getQuantity()).isEqualByComparingTo("1");
        });
    }

    @Test
    void createCostingContext_usesTotalPackagingCostFallbackPerPack() {
        BulkPackCostSummary summary = new BulkPackCostSummary(
                new BigDecimal("12.00"),
                Map.of(10L, new BigDecimal("12.00")),
                Map.of());

        BulkPackCostingContext context = bulkPackingCostService.createCostingContext(
                new BigDecimal("4.50"),
                summary,
                6);

        assertThat(context.bulkUnitCost()).isEqualByComparingTo("4.50");
        assertThat(context.fallbackPackagingCostPerUnit()).isEqualByComparingTo("2.000000");
        assertThat(context.hasLineCosts()).isFalse();
    }
}
