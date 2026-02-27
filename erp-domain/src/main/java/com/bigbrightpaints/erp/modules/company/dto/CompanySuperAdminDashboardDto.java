package com.bigbrightpaints.erp.modules.company.dto;

import java.util.List;

public record CompanySuperAdminDashboardDto(
        long totalTenants,
        long activeTenants,
        long holdTenants,
        long blockedTenants,
        long totalActiveUsers,
        long totalActiveUserQuota,
        long totalStorageBytes,
        long totalStorageQuotaBytes,
        long totalConcurrentUsers,
        long totalConcurrentUserQuota,
        List<TenantOverview> tenants
) {
    public record TenantOverview(
            Long companyId,
            String companyCode,
            String companyName,
            String region,
            String lifecycleState,
            String lifecycleReason,
            long activeUsers,
            long activeUserQuota,
            long storageBytesUsed,
            long storageQuotaBytes,
            long concurrentUsers,
            long concurrentUserQuota,
            long apiActivityCount,
            long apiRequestQuota,
            long apiErrorCount,
            long apiErrorRateInBasisPoints,
            boolean quotaSoftLimitEnabled,
            boolean quotaHardLimitEnabled,
            long activeUserUtilizationInBasisPoints,
            long storageUtilizationInBasisPoints,
            long concurrentUserUtilizationInBasisPoints) {
    }
}
