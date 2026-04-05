package com.bigbrightpaints.erp.core.auditaccess;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;

@Component
public class AuditEventClassifier {

  private static final String ACCOUNTING_EVENT_TRAIL_OPERATION_KEY = "eventTrailOperation";
  private static final Set<AuditEvent> ACCOUNTING_EVENTS =
      Collections.unmodifiableSet(
          EnumSet.of(
              AuditEvent.JOURNAL_ENTRY_POSTED,
              AuditEvent.JOURNAL_ENTRY_REVERSED,
              AuditEvent.SETTLEMENT_RECORDED,
              AuditEvent.PAYROLL_POSTED,
              AuditEvent.TRANSACTION_CREATED,
              AuditEvent.TRANSACTION_APPROVED,
              AuditEvent.TRANSACTION_REJECTED,
              AuditEvent.PAYMENT_PROCESSED,
              AuditEvent.REFUND_ISSUED));
  private static final List<String> SUBJECT_ID_KEYS =
      List.of("subjectUserId", "targetUserId", "requesterUserId", "adminUserId", "userId");
  private static final List<String> SUBJECT_IDENTIFIER_KEYS =
      List.of(
          "subjectIdentifier",
          "targetUserEmail",
          "requesterEmail",
          "adminEmail",
          "userEmail",
          "subjectEmail");

  public String categoryFor(AuditLog log) {
    AuditEvent event = log.getEventType();
    if (event == null) {
      return "GENERAL";
    }
    return switch (event) {
      case LOGIN_SUCCESS,
              LOGIN_FAILURE,
              LOGOUT,
              TOKEN_REFRESH,
              TOKEN_REVOKED,
              PASSWORD_CHANGED,
              PASSWORD_RESET_REQUESTED,
              PASSWORD_RESET_COMPLETED,
              MFA_ENROLLED,
              MFA_ACTIVATED,
              MFA_DISABLED,
              MFA_SUCCESS,
              MFA_FAILURE,
              MFA_RECOVERY_CODE_USED ->
          "AUTH";
      case ACCESS_GRANTED, ACCESS_DENIED, SECURITY_ALERT -> "SECURITY";
      case USER_CREATED,
              USER_UPDATED,
              USER_DELETED,
              USER_ACTIVATED,
              USER_DEACTIVATED,
              USER_LOCKED,
              USER_UNLOCKED,
              PERMISSION_CHANGED,
              ROLE_ASSIGNED,
              ROLE_REMOVED,
              CONFIGURATION_CHANGED ->
          "ADMIN";
      case DATA_CREATE, DATA_READ, DATA_UPDATE, DATA_DELETE, DATA_EXPORT, SENSITIVE_DATA_ACCESSED ->
          "DATA";
      case AUDIT_LOG_ACCESSED, AUDIT_LOG_EXPORTED, COMPLIANCE_CHECK, DATA_RETENTION_ACTION ->
          "COMPLIANCE";
      case SYSTEM_STARTUP, SYSTEM_SHUTDOWN, INTEGRATION_SUCCESS, INTEGRATION_FAILURE -> "SYSTEM";
      default -> "BUSINESS";
    };
  }

  public String categoryFor(AuditActionEvent event) {
    return normalizeOrDefault(event.getModule(), "BUSINESS");
  }

  public String moduleFor(AuditLog log) {
    String path = normalize(log.getRequestPath());
    if (path != null) {
      if (path.startsWith("/api/v1/superadmin")) {
        return "SUPERADMIN";
      }
      if (path.startsWith("/api/v1/admin")) {
        return "ADMIN";
      }
      if (path.startsWith("/api/v1/accounting")) {
        return "ACCOUNTING";
      }
      if (path.startsWith("/api/v1/auth")) {
        return "AUTH";
      }
      if (path.startsWith("/api/v1/changelog")) {
        return "CHANGELOG";
      }
      if (path.startsWith("/api/v1/companies")) {
        return "COMPANIES";
      }
    }
    String resourceType = normalize(log.getResourceType());
    if (resourceType != null) {
      return resourceType.toUpperCase(Locale.ROOT);
    }
    Map<String, String> metadata = log.getMetadata();
    String metadataResourceType = metadata == null ? null : normalize(metadata.get("resourceType"));
    if (metadataResourceType != null) {
      return metadataResourceType.toUpperCase(Locale.ROOT);
    }
    if (isAccountingEvent(log)) {
      return "ACCOUNTING";
    }
    return categoryFor(log);
  }

  public String moduleFor(AuditActionEvent event) {
    return normalizeOrDefault(event.getModule(), "BUSINESS");
  }

  public boolean isAccountingEvent(AuditEvent event) {
    return event != null && ACCOUNTING_EVENTS.contains(event);
  }

  public boolean isAccountingEvent(AuditLog log) {
    if (log == null) {
      return false;
    }
    if (isAccountingEvent(log.getEventType())) {
      return true;
    }
    if (log.getEventType() != AuditEvent.INTEGRATION_FAILURE) {
      return false;
    }
    Map<String, String> metadata = log.getMetadata();
    return metadata != null
        && StringUtils.hasText(metadata.get(ACCOUNTING_EVENT_TRAIL_OPERATION_KEY));
  }

  public Set<AuditEvent> accountingEventTypes() {
    return ACCOUNTING_EVENTS;
  }

  public Long subjectUserId(Map<String, String> metadata) {
    String value = firstMetadataValue(metadata, SUBJECT_ID_KEYS);
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Long.valueOf(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  public String subjectIdentifier(Map<String, String> metadata) {
    return firstMetadataValue(metadata, SUBJECT_IDENTIFIER_KEYS);
  }

  private String firstMetadataValue(Map<String, String> metadata, List<String> keys) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    for (String key : keys) {
      String value = metadata.get(key);
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String normalizeOrDefault(String value, String fallback) {
    if (!StringUtils.hasText(value)) {
      return fallback;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
