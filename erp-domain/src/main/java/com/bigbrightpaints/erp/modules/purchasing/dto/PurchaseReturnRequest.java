package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseReturnRequest(
        @NotNull Long supplierId,
        @NotNull Long rawMaterialId,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitCost,
        String referenceNumber,
        LocalDate returnDate,
        String reason) {}

