package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_P2PGstDecisioningFailClosedTest {

    private static final String PURCHASING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";

    @Test
    void purchaseFlowRejectsMixedManualAndLineTaxSignals() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "if (taxProvided && (lineRequest.taxRate() != null || lineRequest.taxInclusive() != null))",
                "taxAmount cannot be combined with line-level taxRate or taxInclusive");
    }

    @Test
    void purchaseFlowRejectsTaxInclusiveLinesWithoutPositiveRate() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "if (Boolean.TRUE.equals(lineRequest.taxInclusive())",
                "Tax-inclusive purchase line requires a positive GST rate");
    }
}
