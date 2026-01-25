package com.bigbrightpaints.erp.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtils {

    private MoneyUtils() {
    }

    public static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static BigDecimal safeMultiply(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return BigDecimal.ZERO;
        }
        return left.multiply(right);
    }

    public static BigDecimal safeAdd(BigDecimal left, BigDecimal right) {
        return zeroIfNull(left).add(zeroIfNull(right));
    }

    public static BigDecimal safeDivide(BigDecimal dividend, BigDecimal divisor, int scale, RoundingMode roundingMode) {
        if (dividend == null || divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return dividend.divide(divisor, scale, roundingMode);
    }

    public static BigDecimal roundCurrency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean withinTolerance(BigDecimal left, BigDecimal right, BigDecimal tolerance) {
        BigDecimal difference = zeroIfNull(left).subtract(zeroIfNull(right)).abs();
        BigDecimal allowedDelta = zeroIfNull(tolerance);
        return difference.compareTo(allowedDelta) <= 0;
    }
}
