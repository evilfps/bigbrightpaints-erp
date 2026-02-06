package com.bigbrightpaints.erp.truthsuite.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public final class AccountingInvariantAssertions {

    private AccountingInvariantAssertions() {
    }

    public static void assertDebitsEqualCredits(List<BigDecimal> debits,
                                                List<BigDecimal> credits,
                                                BigDecimal tolerance,
                                                String context) {
        BigDecimal debitTotal = sum(debits);
        BigDecimal creditTotal = sum(credits);
        BigDecimal delta = debitTotal.subtract(creditTotal).abs();
        assertTrue(
                delta.compareTo(tolerance) <= 0,
                () -> context + " expected balanced entry but delta=" + delta
                        + " (debit=" + debitTotal + ", credit=" + creditTotal + ")");
    }

    public static void assertWithinTolerance(BigDecimal expected,
                                             BigDecimal actual,
                                             BigDecimal tolerance,
                                             String context) {
        BigDecimal safeExpected = expected == null ? BigDecimal.ZERO : expected;
        BigDecimal safeActual = actual == null ? BigDecimal.ZERO : actual;
        BigDecimal delta = safeExpected.subtract(safeActual).abs();
        assertTrue(
                delta.compareTo(tolerance) <= 0,
                () -> context + " expected=" + safeExpected + " actual=" + safeActual + " delta=" + delta);
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        if (values == null) {
            return BigDecimal.ZERO;
        }
        return values.stream()
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
