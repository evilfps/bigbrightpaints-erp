package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;

public record CreditRequestDecisionRequest(
        @NotBlank String reason
) {}
