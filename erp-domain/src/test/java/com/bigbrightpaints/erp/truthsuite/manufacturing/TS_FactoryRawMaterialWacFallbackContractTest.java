package com.bigbrightpaints.erp.truthsuite.manufacturing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("reconciliation")
class TS_FactoryRawMaterialWacFallbackContractTest {

  private static final String PACKAGING_MATERIAL_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackagingMaterialService.java";
  private static final String PRODUCTION_LOG_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java";

  @Test
  void packagingAndProductionUseSharedWacSelectorWithNullFallback() {
    assertWacNullFallbackPattern(PACKAGING_MATERIAL_SERVICE);
    assertWacNullFallbackPattern(PRODUCTION_LOG_SERVICE);
  }

  @Test
  void packagingAndProductionFallbackToBatchCostWhenWacUnavailable() {
    TruthSuiteFileAssert.assertContainsInOrder(
        PACKAGING_MATERIAL_SERVICE,
        "BigDecimal unitCost = weightedAverageCost != null",
        ": Optional.ofNullable(batch.getCostPerUnit()).orElse(BigDecimal.ZERO);");
    TruthSuiteFileAssert.assertContainsInOrder(
        PRODUCTION_LOG_SERVICE,
        "BigDecimal unitCost = weightedAverageCost != null",
        ": Optional.ofNullable(batch.getCostPerUnit()).orElse(BigDecimal.ZERO);");
  }

  private void assertWacNullFallbackPattern(String relativePath) {
    TruthSuiteFileAssert.assertContainsInOrder(
        relativePath,
        "BigDecimal weightedAverageCost = CostingMethodUtils.selectWeightedAverageValue(",
        "CostingMethodUtils.selectWeightedAverageValue(",
        "() -> rawMaterialBatchRepository.calculateWeightedAverageCost(",
        "() -> null);",
        "BigDecimal unitCost = weightedAverageCost != null");
  }
}
