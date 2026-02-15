package com.bigbrightpaints.erp.core.audittrail;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditActionEventRetryRepository extends JpaRepository<AuditActionEventRetry, Long> {

    @Query(
            value = """
                    SELECT *
                    FROM audit_action_event_retries
                    WHERE next_attempt_at <= :nextAttemptAt
                    ORDER BY next_attempt_at ASC, id ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchLimit
                    """,
            nativeQuery = true)
    List<AuditActionEventRetry> lockDueRetries(
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("batchLimit") int batchLimit);
}
