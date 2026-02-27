package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;

public record CompanySupportWarningDto(
        Long companyId,
        String companyCode,
        String warningId,
        String warningCategory,
        String message,
        String requestedLifecycleState,
        int gracePeriodHours,
        String issuedBy,
        Instant issuedAt
) {
}
