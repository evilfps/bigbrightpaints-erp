package com.bigbrightpaints.erp.modules.production.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductionBrandRequest(
        @NotBlank String name,
        String code,
        String description
) {}
