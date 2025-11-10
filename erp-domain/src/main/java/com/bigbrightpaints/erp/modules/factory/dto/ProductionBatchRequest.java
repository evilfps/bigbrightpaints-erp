package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ProductionBatchRequest(
        @NotBlank String batchNumber,
        @NotNull Double quantityProduced,
        String loggedBy,
        String notes
) {}
