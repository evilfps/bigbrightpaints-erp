package com.bigbrightpaints.erp.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxEvent.Status status, Instant nextAttemptAt);

    long countByStatusAndDeadLetterTrue(OutboxEvent.Status status);

    long countByStatusAndDeadLetterFalse(OutboxEvent.Status status);
}
