package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;
import java.util.Set;

public record SuperAdminTenantSummaryDto(
    Long companyId,
    String companyCode,
    String companyName,
    String timezone,
    String lifecycleState,
    String lifecycleReason,
    long activeUserCount,
    long quotaMaxActiveUsers,
    long apiActivityCount,
    long quotaMaxApiRequests,
    long auditStorageBytes,
    long quotaMaxStorageBytes,
    long currentConcurrentRequests,
    long quotaMaxConcurrentRequests,
    Set<String> enabledModules,
    MainAdminSummaryDto mainAdmin,
    Instant lastActivityAt) {}
