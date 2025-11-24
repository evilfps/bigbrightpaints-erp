package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollBatchPaymentRequest(
        @NotNull LocalDate runDate,
        @NotNull Long cashAccountId,
        @NotNull Long expenseAccountId,
        String referenceNumber,
        String memo,
        @NotEmpty List<@Valid PayrollLine> lines
) {
    public record PayrollLine(
            @NotNull String name,
            @NotNull Integer days,
            @NotNull @DecimalMin("0.00") BigDecimal dailyWage,
            @DecimalMin("0.00") BigDecimal advances,
            String notes
    ) {}
}
