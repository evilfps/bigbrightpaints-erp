package com.bigbrightpaints.erp.core.audit;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;

import jakarta.persistence.*;

/**
 * Entity for storing audit log entries.
 * Provides comprehensive tracking of security and business events.
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
      @Index(name = "idx_audit_user_id", columnList = "user_id"),
      @Index(name = "idx_audit_event_type", columnList = "event_type"),
      @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
      @Index(name = "idx_audit_company_id", columnList = "company_id"),
      @Index(name = "idx_audit_ip_address", columnList = "ip_address")
    })
public class AuditLog extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_type", nullable = false)
  @Enumerated(EnumType.STRING)
  private AuditEvent eventType;

  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "username")
  private String username;

  @Column(name = "company_id")
  private Long companyId;

  @Column(name = "ip_address")
  private String ipAddress;

  @Column(name = "user_agent")
  private String userAgent;

  @Column(name = "request_method")
  private String requestMethod;

  @Column(name = "request_path")
  private String requestPath;

  @Column(name = "resource_type")
  private String resourceType;

  @Column(name = "resource_id")
  private String resourceId;

  @Column(name = "status")
  @Enumerated(EnumType.STRING)
  private AuditStatus status;

  @Column(name = "error_message", length = 500)
  private String errorMessage;

  @Column(name = "details", columnDefinition = "TEXT")
  private String details;

  @Column(name = "trace_id")
  private String traceId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "duration_ms")
  private Long durationMs;

  @ElementCollection
  @CollectionTable(name = "audit_log_metadata", joinColumns = @JoinColumn(name = "audit_log_id"))
  @MapKeyColumn(name = "metadata_key")
  @Column(name = "metadata_value")
  private Map<String, String> metadata = new HashMap<>();

  @PrePersist
  protected void onCreate() {
    if (timestamp == null) {
      timestamp = LocalDateTime.now();
    }
    if (status == null) {
      status = AuditStatus.SUCCESS;
    }
  }

  // Builder pattern for easier construction
  public static class Builder {
    private final AuditLog auditLog = new AuditLog();

    public Builder eventType(AuditEvent eventType) {
      auditLog.eventType = eventType;
      return this;
    }

    public Builder timestamp(LocalDateTime timestamp) {
      auditLog.timestamp = timestamp;
      return this;
    }

    public Builder userId(String userId) {
      auditLog.userId = userId;
      return this;
    }

    public Builder username(String username) {
      auditLog.username = username;
      return this;
    }

    public Builder companyId(Long companyId) {
      auditLog.companyId = companyId;
      return this;
    }

    public Builder ipAddress(String ipAddress) {
      auditLog.ipAddress = ipAddress;
      return this;
    }

    public Builder userAgent(String userAgent) {
      auditLog.userAgent = userAgent;
      return this;
    }

    public Builder requestMethod(String requestMethod) {
      auditLog.requestMethod = requestMethod;
      return this;
    }

    public Builder requestPath(String requestPath) {
      auditLog.requestPath = requestPath;
      return this;
    }

    public Builder resourceType(String resourceType) {
      auditLog.resourceType = resourceType;
      return this;
    }

    public Builder resourceId(String resourceId) {
      auditLog.resourceId = resourceId;
      return this;
    }

    public Builder status(AuditStatus status) {
      auditLog.status = status;
      return this;
    }

    public Builder errorMessage(String errorMessage) {
      auditLog.errorMessage = errorMessage;
      return this;
    }

    public Builder details(String details) {
      auditLog.details = details;
      return this;
    }

    public Builder traceId(String traceId) {
      auditLog.traceId = traceId;
      return this;
    }

    public Builder sessionId(String sessionId) {
      auditLog.sessionId = sessionId;
      return this;
    }

    public Builder durationMs(Long durationMs) {
      auditLog.durationMs = durationMs;
      return this;
    }

    public Builder metadata(String key, String value) {
      auditLog.metadata.put(key, value);
      return this;
    }

    public Builder metadata(Map<String, String> metadata) {
      auditLog.metadata.putAll(metadata);
      return this;
    }

    public AuditLog build() {
      return auditLog;
    }
  }

  // Getters and Setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public AuditEvent getEventType() {
    return eventType;
  }

  public void setEventType(AuditEvent eventType) {
    this.eventType = eventType;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getRequestMethod() {
    return requestMethod;
  }

  public void setRequestMethod(String requestMethod) {
    this.requestMethod = requestMethod;
  }

  public String getRequestPath() {
    return requestPath;
  }

  public void setRequestPath(String requestPath) {
    this.requestPath = requestPath;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getResourceId() {
    return resourceId;
  }

  public void setResourceId(String resourceId) {
    this.resourceId = resourceId;
  }

  public AuditStatus getStatus() {
    return status;
  }

  public void setStatus(AuditStatus status) {
    this.status = status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, String> metadata) {
    this.metadata = metadata;
  }
}
