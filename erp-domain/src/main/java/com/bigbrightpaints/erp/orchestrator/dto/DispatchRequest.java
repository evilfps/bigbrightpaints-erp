package com.bigbrightpaints.erp.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record DispatchRequest(
    @NotBlank String batchId,
    @NotBlank String requestedBy,
    Long debitAccountId,
    Long creditAccountId,
    BigDecimal postingAmount
) {}
