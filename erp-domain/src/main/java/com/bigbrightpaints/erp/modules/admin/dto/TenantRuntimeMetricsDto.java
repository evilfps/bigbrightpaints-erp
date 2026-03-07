package com.bigbrightpaints.erp.modules.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record TenantRuntimeMetricsDto(
        String companyCode,
        String holdState,
        String holdReason,
        int maxActiveUsers,
        int maxRequestsPerMinute,
        int maxConcurrentRequests,
        long enabledUsers,
        long totalUsers,
        int requestsThisMinute,
        int blockedThisMinute,
        int inFlightRequests,
        String policyReference,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant policyUpdatedAt
) {
}
