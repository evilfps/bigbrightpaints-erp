package com.bigbrightpaints.erp.orchestrator.event;

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
    Object payload
) {
    public static DomainEvent of(String eventType, String companyId, String userId, String entity,
                                 String entityId, Object payload) {
        return new DomainEvent(UUID.randomUUID(), eventType, companyId, userId, entity, entityId,
            Instant.now(), payload);
    }
}
