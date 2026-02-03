package com.bigbrightpaints.erp.orchestrator.event;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import java.time.Instant;
import java.util.UUID;

public record DomainEvent(
    UUID id,
    String eventType,
    String companyId,
    String userId,
    String entity,
    String entityId,
    Instant timestamp,
    String traceId,
    String requestId,
    String idempotencyKey,
    Object payload
) {
    public static DomainEvent of(String eventType, String companyId, String userId, String entity,
                                 String entityId, Object payload, String traceId,
                                 String requestId, String idempotencyKey) {
        return new DomainEvent(UUID.randomUUID(), eventType, companyId, userId, entity, entityId,
            CompanyTime.now(), traceId, requestId, idempotencyKey, payload);
    }
}
