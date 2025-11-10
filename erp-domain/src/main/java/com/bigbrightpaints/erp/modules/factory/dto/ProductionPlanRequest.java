package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ProductionPlanRequest(
        @NotBlank String planNumber,
        @NotBlank String productName,
        @NotNull Double quantity,
        @NotNull LocalDate plannedDate,
        String notes
) {}
