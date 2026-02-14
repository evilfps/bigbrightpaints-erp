package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionLogServiceCostingFallbackTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private ProductionLogRepository logRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;

    private ProductionLogService productionLogService;

    @BeforeEach
    void setUp() {
        productionLogService = new ProductionLogService(
                companyContextService,
                companyRepository,
                logRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository,
                accountingFacade,
                companyEntityLookup,
                companyClock,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository);
    }

    @Test
    void issueFromBatches_wacNullAverageFallsBackToBatchUnitCosts() {
        RawMaterial rawMaterial = new RawMaterial();
        rawMaterial.setName("RM-BASE");
        rawMaterial.setCostingMethod("WAC");

        RawMaterialBatch batchA = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchA, "id", 801L);
        batchA.setBatchCode("RM-A");
        batchA.setQuantity(new BigDecimal("2"));
        batchA.setCostPerUnit(new BigDecimal("1.00"));

        RawMaterialBatch batchB = new RawMaterialBatch();
        ReflectionTestUtils.setField(batchB, "id", 802L);
        batchB.setBatchCode("RM-B");
        batchB.setQuantity(new BigDecimal("2"));
        batchB.setCostPerUnit(new BigDecimal("5.00"));

        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(rawMaterial)).thenReturn(List.of(batchA, batchB));
        when(rawMaterialBatchRepository.calculateWeightedAverageCost(rawMaterial)).thenReturn(null);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(801L),
                argThat(qty -> qty.compareTo(new BigDecimal("2")) == 0))).thenReturn(1);
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(eq(802L),
                argThat(qty -> qty.compareTo(BigDecimal.ONE) == 0))).thenReturn(1);

        BigDecimal totalCost = ReflectionTestUtils.invokeMethod(
                productionLogService,
                "issueFromBatches",
                rawMaterial,
                new BigDecimal("3"),
                "PROD-REF");

        assertThat(totalCost).isEqualByComparingTo("7.00");
        ArgumentCaptor<List<RawMaterialMovement>> movementCaptor = ArgumentCaptor.forClass(List.class);
        verify(rawMaterialMovementRepository).saveAll(movementCaptor.capture());
        List<RawMaterialMovement> movements = movementCaptor.getValue();
        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).getReferenceType()).isEqualTo(InventoryReference.PRODUCTION_LOG);
        assertThat(movements.get(0).getUnitCost()).isEqualByComparingTo("1.00");
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo("2");
        assertThat(movements.get(1).getUnitCost()).isEqualByComparingTo("5.00");
        assertThat(movements.get(1).getQuantity()).isEqualByComparingTo("1");
        verify(rawMaterialBatchRepository).calculateWeightedAverageCost(rawMaterial);
    }
}
