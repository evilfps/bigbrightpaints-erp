package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreditRequestRequest(
        Long dealerId,
        @NotNull BigDecimal amountRequested,
        String reason,
        String status
) {}
