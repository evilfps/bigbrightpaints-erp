package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record DealerPortalCreditRequestCreateRequest(
        @NotNull @Positive BigDecimal amountRequested,
        String reason
) {}
