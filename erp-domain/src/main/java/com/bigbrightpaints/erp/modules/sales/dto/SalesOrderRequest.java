package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record SalesOrderRequest(
        Long dealerId,
        @NotNull BigDecimal totalAmount,
        String currency,
        String notes,
        List<@Valid SalesOrderItemRequest> items
) {}
