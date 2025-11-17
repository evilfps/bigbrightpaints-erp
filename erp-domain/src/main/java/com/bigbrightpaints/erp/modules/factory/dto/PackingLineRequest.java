package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record PackingLineRequest(
        @NotBlank(message = "Packaging size is required")
        String packagingSize,
        BigDecimal quantityLiters,
        Integer piecesCount,
        Integer boxesCount,
        Integer piecesPerBox
) {}
