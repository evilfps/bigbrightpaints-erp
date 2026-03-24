package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;

public record CreditLimitRequestDecisionRequest(
        @NotBlank String reason
) {}
