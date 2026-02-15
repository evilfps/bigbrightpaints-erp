package com.bigbrightpaints.erp.core.audittrail;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditActionEventRetryRepository extends JpaRepository<AuditActionEventRetry, Long> {

    List<AuditActionEventRetry> findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Instant nextAttemptAt,
            Pageable pageable);
}
