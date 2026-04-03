package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record AutoSettlementRequest(
    Long cashAccountId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    String referenceNumber,
    String memo,
    @Schema(hidden = true)
    String idempotencyKey) {}
