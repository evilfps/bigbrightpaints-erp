package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record DealerReceiptRequest(
    @NotNull Long dealerId,
    @NotNull Long cashAccountId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    String referenceNumber,
    String memo,
    String idempotencyKey,
    List<@Valid SettlementAllocationRequest> allocations) {}
