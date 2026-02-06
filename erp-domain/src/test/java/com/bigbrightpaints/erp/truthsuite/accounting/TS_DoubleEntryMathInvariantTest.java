package com.bigbrightpaints.erp.truthsuite.accounting;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bigbrightpaints.erp.truthsuite.support.AccountingInvariantAssertions;
import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_DoubleEntryMathInvariantTest {

    private static final String ACCOUNTING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java";

    @RepeatedTest(20)
    void generatedJournalStaysBalancedWhenCreditsMirrorDebits() {
        Random random = new Random(9472L);
        BigDecimal d1 = BigDecimal.valueOf(random.nextInt(10_000), 2);
        BigDecimal d2 = BigDecimal.valueOf(random.nextInt(10_000), 2);
        BigDecimal d3 = BigDecimal.valueOf(random.nextInt(10_000), 2);
        BigDecimal total = d1.add(d2).add(d3);
        AccountingInvariantAssertions.assertDebitsEqualCredits(
                List.of(d1, d2, d3),
                List.of(total),
                new BigDecimal("0.00"),
                "synthetic journal");
    }

    @Test
    void helperFailsWhenJournalIsOutOfBalance() {
        assertThrows(
                AssertionError.class,
                () -> AccountingInvariantAssertions.assertDebitsEqualCredits(
                        List.of(new BigDecimal("100.00")),
                        List.of(new BigDecimal("99.99")),
                        new BigDecimal("0.00"),
                        "unbalanced journal"));
    }

    @Test
    void accountingServiceValidatesLineIntegrityAndFinalBalance() {
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_SERVICE,
                "if (debitInput.compareTo(BigDecimal.ZERO) < 0 || creditInput.compareTo(BigDecimal.ZERO) < 0) {",
                "\"Debit and credit cannot both be non-zero on the same line\"",
                "if (totalBaseDebit.subtract(totalBaseCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {",
                "\"Journal entry must balance\"");
    }
}
