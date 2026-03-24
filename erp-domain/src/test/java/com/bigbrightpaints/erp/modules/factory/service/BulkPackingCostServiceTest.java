package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
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
    void consumePackagingIfRequired_usesPackagingMappingsAndTracksPerLineCosts() {
        when(packagingMaterialService.consumePackagingMaterial("1L", 3, "PACK-REF"))
                .thenReturn(new PackagingConsumptionResult(
                        true,
                        new BigDecimal("7.00"),
                        new BigDecimal("3"),
                        Map.of(700L, new BigDecimal("7.00")),
                        null));

        BulkPackCostSummary summary = bulkPackingCostService.consumePackagingIfRequired(
                company,
                new BulkPackRequest(
                        41L,
                        List.of(new BulkPackRequest.PackLine(901L, new BigDecimal("3"), "1L", "L")),
                        LocalDate.of(2026, 2, 1),
                        "packer",
                        null,
                        null),
                "PACK-REF");

        assertThat(summary.totalCost()).isEqualByComparingTo("7.00");
        assertThat(summary.accountTotals()).containsEntry(700L, new BigDecimal("7.00"));
        assertThat(summary.lineCosts()).containsEntry(0, new BigDecimal("7.00"));
        verify(packagingMaterialService).consumePackagingMaterial("1L", 3, "PACK-REF");
    }

    @Test
    void consumePackagingIfRequired_skipsConsumptionWhenAlreadyConsumedUpstream() {
        BulkPackCostSummary summary = bulkPackingCostService.consumePackagingIfRequired(
                company,
                new BulkPackRequest(
                        41L,
                        List.of(new BulkPackRequest.PackLine(901L, new BigDecimal("3"), "1L", "L")),
                        LocalDate.of(2026, 2, 1),
                        "packer",
                        null,
                        null,
                        true),
                "PACK-REF");

        assertThat(summary.totalCost()).isEqualByComparingTo("0.00");
        assertThat(summary.accountTotals()).isEmpty();
        assertThat(summary.lineCosts()).isEmpty();
        verifyNoInteractions(packagingMaterialService);
    }

    @Test
    void consumePackagingIfRequired_returnsEmptySummaryWhenRequestMissing() {
        BulkPackCostSummary summary = bulkPackingCostService.consumePackagingIfRequired(company, null, "PACK-REF");

        assertThat(summary.totalCost()).isEqualByComparingTo("0.00");
        assertThat(summary.accountTotals()).isEmpty();
        assertThat(summary.lineCosts()).isEmpty();
        verifyNoInteractions(packagingMaterialService);
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
