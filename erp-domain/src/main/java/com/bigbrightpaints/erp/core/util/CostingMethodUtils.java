package com.bigbrightpaints.erp.core.util;

import java.util.Locale;

public final class CostingMethodUtils {

    public enum FinishedGoodBatchSelectionMethod {
        FIFO,
        LIFO,
        WAC
    }

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

    public static String normalizeRawMaterialMethodOrDefault(String method) {
        if (method == null || method.isBlank()) {
            return "FIFO";
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> "FIFO";
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> "WAC";
            default -> throw new IllegalArgumentException("Unsupported costing method " + method);
        };
    }

    public static String normalizeFinishedGoodMethodOrDefault(String method) {
        if (method == null || method.isBlank()) {
            return "FIFO";
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> "FIFO";
            case "LIFO" -> "LIFO";
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> "WAC";
            default -> throw new IllegalArgumentException("Unsupported costing method " + method);
        };
    }

    public static String canonicalizeFinishedGoodMethodForSync(String method) {
        if (method == null || method.isBlank()) {
            return "FIFO";
        }
        String trimmed = method.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> "FIFO";
            case "LIFO" -> "LIFO";
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> "WAC";
            default -> trimmed;
        };
    }

    public static String canonicalizeRawMaterialMethodForSync(String method) {
        if (method == null || method.isBlank()) {
            return "FIFO";
        }
        String trimmed = method.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> "FIFO";
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> "WAC";
            default -> trimmed;
        };
    }

    public static FinishedGoodBatchSelectionMethod resolveFinishedGoodBatchSelectionMethod(String method) {
        if (isWeightedAverage(method)) {
            return FinishedGoodBatchSelectionMethod.WAC;
        }
        if (method == null || method.isBlank()) {
            return FinishedGoodBatchSelectionMethod.FIFO;
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        if ("LIFO".equals(normalized)) {
            return FinishedGoodBatchSelectionMethod.LIFO;
        }
        return FinishedGoodBatchSelectionMethod.FIFO;
    }
}
