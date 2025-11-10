package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesTargetRequest(
        @NotBlank String name,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @NotNull BigDecimal targetAmount,
        BigDecimal achievedAmount,
        String assignee
) {}
