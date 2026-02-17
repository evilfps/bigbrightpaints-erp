package com.bigbrightpaints.erp.modules.company.dto;

public record CompanyTenantMetricsDto(
        Long companyId,
        String companyCode,
        String lifecycleState,
        String lifecycleReason,
        long activeUserCount,
        long apiActivityCount,
        long apiErrorCount,
        long apiErrorRateInBasisPoints,
        long distinctSessionCount,
        long auditStorageBytes
) {
}
