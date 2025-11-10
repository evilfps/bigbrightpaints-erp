package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.constraints.NotBlank;

public record FinishedGoodRequest(
        @NotBlank String productCode,
        @NotBlank String name,
        String unit,
        String costingMethod,
        Long valuationAccountId,
        Long cogsAccountId
) {}
