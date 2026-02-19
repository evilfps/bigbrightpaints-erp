package com.bigbrightpaints.erp.modules.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record TenantRuntimePolicyUpdateRequest(
        @Min(value = 1, message = "maxActiveUsers must be at least 1")
        Integer maxActiveUsers,
        @Min(value = 1, message = "maxRequestsPerMinute must be at least 1")
        Integer maxRequestsPerMinute,
        @Min(value = 1, message = "maxConcurrentRequests must be at least 1")
        Integer maxConcurrentRequests,
        String holdState,
        @Size(max = 300, message = "holdReason must be at most 300 characters")
        String holdReason,
        @Size(max = 300, message = "changeReason must be at most 300 characters")
        String changeReason
) {
}
