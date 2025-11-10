package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record FinishedGoodBatchRequest(
        @NotNull Long finishedGoodId,
        @NotBlank String batchCode,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitCost,
        Instant manufacturedAt,
        LocalDate expiryDate
) {}
