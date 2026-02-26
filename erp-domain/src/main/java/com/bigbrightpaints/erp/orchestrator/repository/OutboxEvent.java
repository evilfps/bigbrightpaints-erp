package com.bigbrightpaints.erp.orchestrator.repository;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orchestrator_outbox")
public class OutboxEvent extends VersionedEntity {

    public enum Status {
        PENDING,
        PUBLISHING,
        PUBLISHED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int retryCount;

    @Column
    private String lastError;

    @Column(nullable = false)
    private boolean deadLetter;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.createdAt = CompanyTime.now();
        this.nextAttemptAt = this.createdAt;
        this.retryCount = 0;
        this.deadLetter = false;
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, Long companyId) {
        this(aggregateType, aggregateId, eventType, payload);
        this.companyId = companyId;
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, Long companyId,
                       String traceId, String requestId, String idempotencyKey) {
        this(aggregateType, aggregateId, eventType, payload, companyId);
        this.traceId = traceId;
        this.requestId = requestId;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isDeadLetter() {
        return deadLetter;
    }

    public void markPublishing() {
        markPublishingUntil(CompanyTime.now());
    }

    public void markPublishingUntil(Instant leaseUntil) {
        this.status = Status.PUBLISHING;
        this.deadLetter = false;
        this.lastError = null;
        this.nextAttemptAt = leaseUntil;
    }

    public void deferPublishing(String errorMessage, long delaySeconds) {
        this.status = Status.PUBLISHING;
        this.deadLetter = false;
        this.lastError = errorMessage;
        this.nextAttemptAt = CompanyTime.now().plusSeconds(Math.max(1L, delaySeconds));
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.deadLetter = false;
        this.lastError = null;
    }

    public void scheduleRetry(String errorMessage, int maxAttempts, long delaySeconds) {
        this.retryCount += 1;
        this.lastError = errorMessage;
        if (this.retryCount >= maxAttempts) {
            this.deadLetter = true;
            this.status = Status.FAILED;
        } else {
            this.status = Status.PENDING;
            this.nextAttemptAt = CompanyTime.now().plusSeconds(delaySeconds);
        }
    }
}
