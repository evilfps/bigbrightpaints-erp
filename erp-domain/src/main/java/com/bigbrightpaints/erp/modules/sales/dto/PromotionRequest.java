package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PromotionRequest(
        @NotBlank String name,
        String description,
        @NotBlank String discountType,
        @NotNull BigDecimal discountValue,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String status
) {}
