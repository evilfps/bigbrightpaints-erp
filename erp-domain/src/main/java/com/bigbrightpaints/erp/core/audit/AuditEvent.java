package com.bigbrightpaints.erp.core.audit;

/**
 * Enumeration of audit event types for comprehensive security logging.
 */
public enum AuditEvent {
  // Authentication Events
  LOGIN_SUCCESS("User logged in successfully"),
  LOGIN_FAILURE("Failed login attempt"),
  LOGOUT("User logged out"),
  TOKEN_REFRESH("JWT token refreshed"),
  TOKEN_REVOKED("JWT token revoked"),
  PASSWORD_CHANGED("Password changed"),
  PASSWORD_RESET_REQUESTED("Password reset requested"),
  PASSWORD_RESET_COMPLETED("Password reset completed"),

  // MFA Events
  MFA_ENROLLED("MFA enrolled"),
  MFA_ACTIVATED("MFA activated"),
  MFA_DISABLED("MFA disabled"),
  MFA_SUCCESS("MFA verification successful"),
  MFA_FAILURE("MFA verification failed"),
  MFA_RECOVERY_CODE_USED("MFA recovery code used"),

  // Authorization Events
  ACCESS_GRANTED("Access granted to resource"),
  ACCESS_DENIED("Access denied to resource"),
  PERMISSION_CHANGED("User permissions changed"),
  ROLE_ASSIGNED("Role assigned to user"),
  ROLE_REMOVED("Role removed from user"),

  // Data Access Events
  DATA_CREATE("Data created"),
  DATA_READ("Data accessed"),
  DATA_UPDATE("Data updated"),
  DATA_DELETE("Data deleted"),
  DATA_EXPORT("Data exported"),
  SENSITIVE_DATA_ACCESSED("Sensitive data accessed"),

  // Administrative Events
  USER_CREATED("User account created"),
  USER_UPDATED("User account updated"),
  USER_DELETED("User account deleted"),
  USER_ACTIVATED("User account activated"),
  USER_DEACTIVATED("User account deactivated"),
  USER_LOCKED("User account locked"),
  USER_UNLOCKED("User account unlocked"),

  // System Events
  SYSTEM_STARTUP("System started"),
  SYSTEM_SHUTDOWN("System shutdown"),
  CONFIGURATION_CHANGED("System configuration changed"),
  SECURITY_ALERT("Security alert triggered"),
  INTEGRATION_SUCCESS("External integration successful"),
  INTEGRATION_FAILURE("External integration failed"),

  // Business Operations
  REFERENCE_GENERATED("Business reference generated"),
  ORDER_NUMBER_GENERATED("Order number generated"),
  INVENTORY_ADJUSTMENT("Inventory adjustment posted"),
  GOODS_RECEIPT("Goods receipt recorded"),
  JOURNAL_ENTRY_POSTED("Journal entry posted"),
  JOURNAL_ENTRY_REVERSED("Journal entry reversed"),
  DISPATCH_CONFIRMED("Dispatch confirmed"),
  SETTLEMENT_RECORDED("Settlement recorded"),
  PAYROLL_POSTED("Payroll posted to accounting"),

  // Financial Events
  TRANSACTION_CREATED("Financial transaction created"),
  TRANSACTION_APPROVED("Transaction approved"),
  TRANSACTION_REJECTED("Transaction rejected"),
  PAYMENT_PROCESSED("Payment processed"),
  REFUND_ISSUED("Refund issued"),

  // Compliance Events
  AUDIT_LOG_ACCESSED("Audit log accessed"),
  AUDIT_LOG_EXPORTED("Audit log exported"),
  COMPLIANCE_CHECK("Compliance check performed"),
  DATA_RETENTION_ACTION("Data retention action performed");

  private final String description;

  AuditEvent(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }
}
