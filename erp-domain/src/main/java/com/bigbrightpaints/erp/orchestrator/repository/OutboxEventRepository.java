package com.bigbrightpaints.erp.orchestrator.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxEvent.Status status, Instant nextAttemptAt);

    long countByStatusAndDeadLetterTrue(OutboxEvent.Status status);

    long countByStatusAndDeadLetterFalse(OutboxEvent.Status status);

    long countByStatusAndDeadLetterFalseAndRetryCountGreaterThan(OutboxEvent.Status status, int retryCount);

    long countByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqual(OutboxEvent.Status status, Instant nextAttemptAt);

    long countByStatusAndDeadLetterFalseAndLastErrorStartingWith(OutboxEvent.Status status, String lastErrorPrefix);

    @QueryHints(@QueryHint(name = "org.hibernate.fetchSize", value = "1"))
    @Query("""
            select count(e)
            from OutboxEvent e
            where e.status = :status
              and e.deadLetter = false
              and (
                e.lastError like concat(:ambiguousPrefix, '%')
                or e.lastError like concat(:finalizePrefix, '%')
                or e.lastError like concat(:stalePrefix, '%')
              )
            """)
    long countAmbiguousPublishingEvents(@Param("status") OutboxEvent.Status status,
                                        @Param("ambiguousPrefix") String ambiguousPrefix,
                                        @Param("finalizePrefix") String finalizePrefix,
                                        @Param("stalePrefix") String stalePrefix);

    List<OutboxEvent> findByCompanyIdAndTraceId(Long companyId, String traceId);

    List<OutboxEvent> findByCompanyIdAndRequestId(Long companyId, String requestId);

    List<OutboxEvent> findByCompanyIdAndIdempotencyKey(Long companyId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") UUID id);
}
