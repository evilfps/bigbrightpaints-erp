package com.bigbrightpaints.erp.modules.company.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantOnboardingRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 64) String timezone,
    @DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0", inclusive = true)
        BigDecimal defaultGstRate,
    @Min(value = 0, message = "maxActiveUsers must be greater than or equal to 0")
        Long maxActiveUsers,
    @Min(value = 0, message = "maxApiRequests must be greater than or equal to 0")
        Long maxApiRequests,
    @Min(value = 0, message = "maxStorageBytes must be greater than or equal to 0")
        Long maxStorageBytes,
    @Min(value = 0, message = "maxConcurrentRequests must be greater than or equal to 0")
        Long maxConcurrentRequests,
    Boolean softLimitEnabled,
    Boolean hardLimitEnabled,
    @Email @NotBlank String firstAdminEmail,
    @Size(max = 255) String firstAdminDisplayName,
    @NotBlank @Size(max = 64) String coaTemplateCode) {}
