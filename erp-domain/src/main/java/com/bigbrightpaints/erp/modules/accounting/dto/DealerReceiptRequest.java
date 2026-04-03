package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record DealerReceiptRequest(
    @NotNull Long dealerId,
    @NotNull Long cashAccountId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    String referenceNumber,
    String memo,
    @Schema(hidden = true)
    String idempotencyKey,
    @NotEmpty(
            message =
                "Allocations are required for dealer receipts; use settlement endpoints or include"
                    + " allocations")
        List<@Valid SettlementAllocationRequest> allocations) {}
