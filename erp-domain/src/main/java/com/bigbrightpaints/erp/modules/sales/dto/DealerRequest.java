package com.bigbrightpaints.erp.modules.sales.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record DealerRequest(
        @NotBlank String name,
        @NotBlank String code,
        String email,
        String phone,
        @NotNull BigDecimal creditLimit
) {}
