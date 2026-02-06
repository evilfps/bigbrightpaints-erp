package com.bigbrightpaints.erp.truthsuite.tax;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_GstRoundingDeterminismContractTest {

    private static final String SALES_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java";
    private static final String PURCHASING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";

    @Test
    void salesTaxAndDiscountMathUsesDeterministicHalfUpRounding() {
        TruthSuiteFileAssert.assertContains(
                SALES_SERVICE,
                "private static final BigDecimal DISPATCH_TOTAL_TOLERANCE = new BigDecimal(\"0.01\");",
                "BigDecimal sanitized = rate.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);",
                "return sanitized.setScale(4, RoundingMode.HALF_UP);",
                "private LineAmounts computeDispatchLineAmounts(",
                "BigDecimal divisor = BigDecimal.ONE.add(rate.divide(new BigDecimal(\"100\"), 6, RoundingMode.HALF_UP));",
                "BigDecimal net = currency(netRaw);",
                "BigDecimal tax = currency(taxRaw);");
    }

    @Test
    void purchasingTaxAllocationUsesDeterministicHalfUpRounding() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "lineTax = currency(lineNet.multiply(taxRate)",
                ".divide(new BigDecimal(\"100\"), 6, RoundingMode.HALF_UP));",
                "BigDecimal allocatedTax = (i == computedLines.size() - 1)",
                ".divide(inventoryTotal, 6, RoundingMode.HALF_UP));");
    }
}
