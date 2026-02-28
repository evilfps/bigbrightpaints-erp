package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.Instant;
import java.util.Map;

public record AccountingAuditTrailEntryDto(
        Long id,
        Instant timestamp,
        Long companyId,
        String companyCode,
        Long actorUserId,
        String actorIdentifier,
        String actionType,
        String entityType,
        String entityId,
        String referenceNumber,
        String traceId,
        String ipAddress,
        String beforeState,
        String afterState,
        boolean sensitiveOperation,
        Map<String, String> metadata
) {
}
