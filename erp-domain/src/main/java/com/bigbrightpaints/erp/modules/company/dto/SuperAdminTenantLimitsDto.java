package com.bigbrightpaints.erp.modules.company.dto;

public record SuperAdminTenantLimitsDto(
    Long companyId,
    String companyCode,
    long quotaMaxActiveUsers,
    long quotaMaxApiRequests,
    long quotaMaxStorageBytes,
    long quotaMaxConcurrentRequests,
    boolean quotaSoftLimitEnabled,
    boolean quotaHardLimitEnabled) {}
