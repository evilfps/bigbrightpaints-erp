package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CreditLimitOverrideDecisionRequest(
        @NotBlank String reason,
        Instant expiresAt
) {}
