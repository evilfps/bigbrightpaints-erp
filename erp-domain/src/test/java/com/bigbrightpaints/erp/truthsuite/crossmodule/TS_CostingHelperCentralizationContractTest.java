package com.bigbrightpaints.erp.truthsuite.crossmodule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("reconciliation")
class TS_CostingHelperCentralizationContractTest {

  private static final String RAW_MATERIAL_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/RawMaterialService.java";
  private static final String FINISHED_GOODS_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java";
  private static final String INVENTORY_ADJUSTMENT_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/InventoryAdjustmentService.java";
  private static final String PACKAGING_MATERIAL_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackagingMaterialService.java";
  private static final String PRODUCTION_LOG_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java";
  private static final String INVENTORY_VALUATION_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/reports/service/InventoryValuationQueryService.java";

  @Test
  void inventoryAndReportsUseSharedCostingSelectionHelpers() {
    TruthSuiteFileAssert.assertContains(
        FINISHED_GOODS_SERVICE,
        "CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(",
        "CostingMethodUtils.isWeightedAverage(");
    TruthSuiteFileAssert.assertContains(
        INVENTORY_ADJUSTMENT_SERVICE,
        "CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(");
    TruthSuiteFileAssert.assertContains(
        INVENTORY_VALUATION_SERVICE,
        "CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(",
        "CostingMethodUtils.selectWeightedAverageValue(");
  }

  @Test
  void inventoryAndFactoryUseSharedRawMaterialCostingHelpers() {
    TruthSuiteFileAssert.assertContains(
        RAW_MATERIAL_SERVICE, "CostingMethodUtils.normalizeRawMaterialMethodOrDefault(");
    TruthSuiteFileAssert.assertContains(
        PACKAGING_MATERIAL_SERVICE, "CostingMethodUtils.selectWeightedAverageValue(");
    TruthSuiteFileAssert.assertContains(
        PRODUCTION_LOG_SERVICE, "CostingMethodUtils.selectWeightedAverageValue(");
  }

  @Test
  void modulesDoNotDeclareLocalWeightedAverageHelperCopies() {
    assertNoLocalWeightedAverageHelper(RAW_MATERIAL_SERVICE);
    assertNoLocalWeightedAverageHelper(FINISHED_GOODS_SERVICE);
    assertNoLocalWeightedAverageHelper(INVENTORY_ADJUSTMENT_SERVICE);
    assertNoLocalWeightedAverageHelper(INVENTORY_VALUATION_SERVICE);
    assertNoLocalWeightedAverageHelper(PACKAGING_MATERIAL_SERVICE);
    assertNoLocalWeightedAverageHelper(PRODUCTION_LOG_SERVICE);
  }

  private void assertNoLocalWeightedAverageHelper(String relativePath) {
    String source = TruthSuiteFileAssert.read(relativePath);
    assertThat(source).doesNotContain("private boolean isWeightedAverage(");
    assertThat(source).doesNotContain("private static boolean isWeightedAverage(");
    assertThat(source).doesNotContain("private String normalizeRawMaterialMethod(");
    assertThat(source).doesNotContain("private static String normalizeRawMaterialMethod(");
    assertThat(source).doesNotContain("private String normalizeFinishedGoodMethod(");
    assertThat(source).doesNotContain("private static String normalizeFinishedGoodMethod(");
  }
}
