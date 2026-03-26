package com.bigbrightpaints.erp.modules.company.dto;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 64) String timezone,
    @Size(min = 2, max = 2, message = "stateCode must be exactly 2 characters") String stateCode,
    @DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0", inclusive = true)
        BigDecimal defaultGstRate,
    @Min(value = 0, message = "quotaMaxActiveUsers must be greater than or equal to 0")
        Long quotaMaxActiveUsers,
    @Min(value = 0, message = "quotaMaxApiRequests must be greater than or equal to 0")
        Long quotaMaxApiRequests,
    @Min(value = 0, message = "quotaMaxStorageBytes must be greater than or equal to 0")
        Long quotaMaxStorageBytes,
    @Min(value = 0, message = "quotaMaxConcurrentRequests must be greater than or equal to 0")
        Long quotaMaxConcurrentRequests,
    Boolean quotaSoftLimitEnabled,
    Boolean quotaHardLimitEnabled,
    @Email @Size(max = 255) String firstAdminEmail,
    @Size(max = 255) String firstAdminDisplayName,
    Set<@NotBlank @Size(max = 64) String> enabledModules) {

  public CompanyRequest(
      String name,
      String code,
      String timezone,
      BigDecimal defaultGstRate,
      Long quotaMaxActiveUsers,
      Long quotaMaxApiRequests,
      Long quotaMaxStorageBytes,
      Long quotaMaxConcurrentRequests,
      Boolean quotaSoftLimitEnabled,
      Boolean quotaHardLimitEnabled,
      String firstAdminEmail,
      String firstAdminDisplayName) {
    this(
        name,
        code,
        timezone,
        null,
        defaultGstRate,
        quotaMaxActiveUsers,
        quotaMaxApiRequests,
        quotaMaxStorageBytes,
        quotaMaxConcurrentRequests,
        quotaSoftLimitEnabled,
        quotaHardLimitEnabled,
        firstAdminEmail,
        firstAdminDisplayName,
        null);
  }

  public CompanyRequest(
      String name,
      String code,
      String timezone,
      BigDecimal defaultGstRate,
      Long quotaMaxActiveUsers,
      Long quotaMaxApiRequests,
      Long quotaMaxStorageBytes,
      Long quotaMaxConcurrentRequests,
      Boolean quotaSoftLimitEnabled,
      Boolean quotaHardLimitEnabled,
      String firstAdminEmail,
      String firstAdminDisplayName,
      Set<@NotBlank @Size(max = 64) String> enabledModules) {
    this(
        name,
        code,
        timezone,
        null,
        defaultGstRate,
        quotaMaxActiveUsers,
        quotaMaxApiRequests,
        quotaMaxStorageBytes,
        quotaMaxConcurrentRequests,
        quotaSoftLimitEnabled,
        quotaHardLimitEnabled,
        firstAdminEmail,
        firstAdminDisplayName,
        enabledModules);
  }

  public CompanyRequest(
      String name,
      String code,
      String timezone,
      BigDecimal defaultGstRate,
      Long quotaMaxActiveUsers,
      Long quotaMaxApiRequests,
      Long quotaMaxStorageBytes,
      Long quotaMaxConcurrentRequests,
      Boolean quotaSoftLimitEnabled,
      Boolean quotaHardLimitEnabled) {
    this(
        name,
        code,
        timezone,
        null,
        defaultGstRate,
        quotaMaxActiveUsers,
        quotaMaxApiRequests,
        quotaMaxStorageBytes,
        quotaMaxConcurrentRequests,
        quotaSoftLimitEnabled,
        quotaHardLimitEnabled,
        null,
        null,
        null);
  }

  public CompanyRequest(String name, String code, String timezone, BigDecimal defaultGstRate) {
    this(
        name,
        code,
        timezone,
        null,
        defaultGstRate,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public CompanyRequest(String name, String code, String timezone) {
    this(name, code, timezone, null);
  }
}
