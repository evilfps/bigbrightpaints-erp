package com.bigbrightpaints.erp.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ApproveOrderRequest(
    @NotBlank String orderId,
    @NotBlank String approvedBy,
    @NotNull BigDecimal totalAmount
) {}
