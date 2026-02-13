package com.bigbrightpaints.erp.core.util;

import java.util.Locale;

public final class CostingMethodUtils {

    private CostingMethodUtils() {
    }

    public static boolean isWeightedAverage(String method) {
        if (method == null || method.isBlank()) {
            return false;
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return "WAC".equals(normalized)
                || "WEIGHTED_AVERAGE".equals(normalized)
                || "WEIGHTED-AVERAGE".equals(normalized);
    }
}
