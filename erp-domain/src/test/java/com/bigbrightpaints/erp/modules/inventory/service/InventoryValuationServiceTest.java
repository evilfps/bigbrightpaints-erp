package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class InventoryValuationServiceTest {

    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    private InventoryValuationService service;

    @BeforeEach
    void setUp() {
        service = new InventoryValuationService(finishedGoodBatchRepository);
    }

    @Test
    void resolveDispatchUnitCost_prefersReservedBatchActualCost() {
        FinishedGood finishedGood = new FinishedGood();
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setUnitCost(new BigDecimal("31.25"));

        assertThat(service.resolveDispatchUnitCost(finishedGood, batch)).isEqualByComparingTo("31.25");
    }

    @Test
    void resolveDispatchUnitCost_fallsBackToWeightedAverageWhenBatchCostMissing() {
        FinishedGood finishedGood = new FinishedGood();
        setId(finishedGood, 44L);
        when(finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood)).thenReturn(new BigDecimal("19.75"));

        assertThat(service.resolveDispatchUnitCost(finishedGood, null)).isEqualByComparingTo("19.75");
    }

    @Test
    void resolveDispatchUnitCost_fallsBackToWeightedAverageWhenBatchExistsWithoutUnitCost() {
        FinishedGood finishedGood = new FinishedGood();
        setId(finishedGood, 45L);
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setUnitCost(null);
        when(finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood)).thenReturn(new BigDecimal("14.25"));

        assertThat(service.resolveDispatchUnitCost(finishedGood, batch)).isEqualByComparingTo("14.25");
    }

    @Test
    void resolveDispatchUnitCost_returnsZeroWhenFinishedGoodMissing() {
        assertThat(service.resolveDispatchUnitCost(null, null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static void setId(FinishedGood finishedGood, Long id) {
        try {
            java.lang.reflect.Field field = FinishedGood.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(finishedGood, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
