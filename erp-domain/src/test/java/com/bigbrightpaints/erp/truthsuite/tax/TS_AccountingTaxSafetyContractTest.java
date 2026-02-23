package com.bigbrightpaints.erp.truthsuite.tax;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_AccountingTaxSafetyContractTest {

    private static final String TAX_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java";

    @Test
    void futurePeriodRequestsFailClosedWithDiagnosticContext() {
        TruthSuiteFileAssert.assertContains(
                TAX_SERVICE,
                "if (target.isAfter(currentPeriod)) {",
                "GST return period cannot be in the future",
                ".withDetail(\"requestedPeriod\", target.toString())",
                ".withDetail(\"currentPeriod\", currentPeriod.toString());");
    }

    @Test
    void nonGstModeRejectsConfiguredGstAccountsWithExplicitDetails() {
        TruthSuiteFileAssert.assertContains(
                TAX_SERVICE,
                "configured.add(\"gstInputTaxAccountId\");",
                "configured.add(\"gstOutputTaxAccountId\");",
                "configured.add(\"gstPayableAccountId\");",
                "Non-GST mode company cannot have GST tax accounts configured",
                ".withDetail(\"configured\", configured);");
    }

    @Test
    void taxSignalRoutingPreservesContraBalanceHandling() {
        TruthSuiteFileAssert.assertContains(
                TAX_SERVICE,
                "positivePortion(outputTaxBalance).add(positivePortion(inputTaxBalance.negate()))",
                "positivePortion(inputTaxBalance).add(positivePortion(outputTaxBalance.negate()))",
                "return buildGstReturn(target, start, end, outputTax, inputTax);");
    }
}
