package com.bigbrightpaints.erp.core.auditaccess.dto;

import java.time.Instant;
import java.util.Map;

public record AuditFeedItemDto(
    Long sourceId,
    String sourceKind,
    String category,
    Instant occurredAt,
    Long companyId,
    String companyCode,
    String module,
    String action,
    String status,
    Long actorUserId,
    String actorIdentifier,
    Long subjectUserId,
    String subjectIdentifier,
    String entityType,
    String entityId,
    String referenceNumber,
    String requestMethod,
    String requestPath,
    String traceId,
    Map<String, String> metadata) {}
