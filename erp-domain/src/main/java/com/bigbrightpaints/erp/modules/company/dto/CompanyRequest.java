package com.bigbrightpaints.erp.modules.company.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CompanyRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 64) String timezone,
        @DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0", inclusive = true) BigDecimal defaultGstRate,
        @Min(value = 0, message = "quotaMaxActiveUsers must be greater than or equal to 0") Long quotaMaxActiveUsers,
        @Min(value = 0, message = "quotaMaxApiRequests must be greater than or equal to 0") Long quotaMaxApiRequests,
        @Min(value = 0, message = "quotaMaxStorageBytes must be greater than or equal to 0") Long quotaMaxStorageBytes,
        @Min(value = 0, message = "quotaMaxConcurrentSessions must be greater than or equal to 0") Long quotaMaxConcurrentSessions,
        Boolean quotaSoftLimitEnabled,
        Boolean quotaHardLimitEnabled,
        @Email @Size(max = 255) String firstAdminEmail,
        @Size(max = 255) String firstAdminDisplayName
) {

    public CompanyRequest(String name,
                          String code,
                          String timezone,
                          BigDecimal defaultGstRate,
                          Long quotaMaxActiveUsers,
                          Long quotaMaxApiRequests,
                          Long quotaMaxStorageBytes,
                          Long quotaMaxConcurrentSessions,
                          Boolean quotaSoftLimitEnabled,
                          Boolean quotaHardLimitEnabled) {
        this(
                name,
                code,
                timezone,
                defaultGstRate,
                quotaMaxActiveUsers,
                quotaMaxApiRequests,
                quotaMaxStorageBytes,
                quotaMaxConcurrentSessions,
                quotaSoftLimitEnabled,
                quotaHardLimitEnabled,
                null,
                null);
    }

    public CompanyRequest(String name,
                          String code,
                          String timezone,
                          BigDecimal defaultGstRate) {
        this(name, code, timezone, defaultGstRate, null, null, null, null, null, null, null, null);
    }

    public CompanyRequest(String name,
                          String code,
                          String timezone) {
        this(name, code, timezone, null);
    }
}
