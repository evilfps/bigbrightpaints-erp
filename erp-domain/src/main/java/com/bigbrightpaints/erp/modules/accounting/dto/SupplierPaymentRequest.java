package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record SupplierPaymentRequest(
        @NotNull Long supplierId,
        @NotNull Long cashAccountId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        String referenceNumber,
        String memo,
        String idempotencyKey,
        @NotEmpty(message = "Allocations are required for supplier payments; use settlement endpoints or include allocations")
        List<@Valid SettlementAllocationRequest> allocations) {
}
