package com.bigbrightpaints.erp.modules.company.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CompanyRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 64) String timezone,
        @DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0", inclusive = true) BigDecimal defaultGstRate
) {}
