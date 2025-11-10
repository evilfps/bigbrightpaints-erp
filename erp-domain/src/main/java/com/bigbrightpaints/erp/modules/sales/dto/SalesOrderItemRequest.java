package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SalesOrderItemRequest(
        @NotBlank String productCode,
        String description,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitPrice
) {}
