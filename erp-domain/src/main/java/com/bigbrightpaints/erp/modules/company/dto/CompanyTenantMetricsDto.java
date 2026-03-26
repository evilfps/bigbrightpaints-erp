package com.bigbrightpaints.erp.modules.company.dto;

public record CompanyTenantMetricsDto(
    Long companyId,
    String companyCode,
    String lifecycleState,
    String lifecycleReason,
    long quotaMaxActiveUsers,
    long quotaMaxApiRequests,
    long quotaMaxStorageBytes,
    long quotaMaxConcurrentRequests,
    boolean quotaSoftLimitEnabled,
    boolean quotaHardLimitEnabled,
    long activeUserCount,
    long apiActivityCount,
    long apiErrorCount,
    long apiErrorRateInBasisPoints,
    long currentConcurrentRequests,
    long auditStorageBytes) {}
