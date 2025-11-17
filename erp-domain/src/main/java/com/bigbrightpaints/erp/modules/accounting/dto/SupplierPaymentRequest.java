package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record SupplierPaymentRequest(
        @NotNull Long supplierId,
        @NotNull Long cashAccountId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        String referenceNumber,
        String memo) {
}
