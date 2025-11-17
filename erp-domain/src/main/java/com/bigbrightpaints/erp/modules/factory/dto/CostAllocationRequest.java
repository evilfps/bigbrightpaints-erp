package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CostAllocationRequest(
        @NotNull(message = "Year is required")
        @Min(value = 2000, message = "Year must be 2000 or later")
        Integer year,

        @NotNull(message = "Month is required")
        @Min(value = 1, message = "Month must be between 1 and 12")
        Integer month,

        @NotNull(message = "Labor cost is required")
        BigDecimal laborCost,

        @NotNull(message = "Overhead cost is required")
        BigDecimal overheadCost,

        String notes
) {
    public CostAllocationRequest {
        if (month != null && (month < 1 || month > 12)) {
            throw new IllegalArgumentException("Month must be between 1 and 12");
        }
        if (laborCost != null && laborCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Labor cost cannot be negative");
        }
        if (overheadCost != null && overheadCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Overhead cost cannot be negative");
        }
    }
}
