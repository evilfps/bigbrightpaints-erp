package com.bigbrightpaints.erp.orchestrator.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "orchestrator_audit")
public class AuditRecord extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String traceId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(name = "company_id")
    private Long companyId;

    @Lob
    @Column(nullable = false)
    private String details;

    protected AuditRecord() {
    }

    public AuditRecord(String traceId, String eventType, Instant timestamp, String details, Long companyId) {
        this.traceId = traceId;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.details = details;
        this.companyId = companyId;
    }

    public UUID getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public String getDetails() {
        return details;
    }
}
