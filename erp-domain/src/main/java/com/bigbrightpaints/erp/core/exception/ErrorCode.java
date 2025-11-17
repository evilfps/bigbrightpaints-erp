package com.bigbrightpaints.erp.core.exception;

/**
 * Centralized error codes for the application.
 * These codes are used to provide consistent error responses without exposing internal details.
 */
public enum ErrorCode {
    // Authentication & Authorization (1000-1999)
    AUTH_INVALID_CREDENTIALS("AUTH_001", "Invalid credentials provided"),
    AUTH_TOKEN_EXPIRED("AUTH_002", "Authentication token has expired"),
    AUTH_TOKEN_INVALID("AUTH_003", "Invalid authentication token"),
    AUTH_INSUFFICIENT_PERMISSIONS("AUTH_004", "Insufficient permissions for this operation"),
    AUTH_ACCOUNT_LOCKED("AUTH_005", "Account is locked"),
    AUTH_ACCOUNT_DISABLED("AUTH_006", "Account is disabled"),
    AUTH_MFA_REQUIRED("AUTH_007", "Multi-factor authentication required"),
    AUTH_MFA_INVALID("AUTH_008", "Invalid MFA code"),
    AUTH_PASSWORD_POLICY_VIOLATION("AUTH_009", "Password does not meet security requirements"),
    AUTH_SESSION_EXPIRED("AUTH_010", "Session has expired"),

    // Business Logic Errors (2000-2999)
    BUSINESS_INVALID_STATE("BUS_001", "Operation not allowed in current state"),
    BUSINESS_DUPLICATE_ENTRY("BUS_002", "Duplicate entry found"),
    BUSINESS_ENTITY_NOT_FOUND("BUS_003", "Requested resource not found"),
    BUSINESS_CONSTRAINT_VIOLATION("BUS_004", "Business rule violation"),
    BUSINESS_INSUFFICIENT_FUNDS("BUS_005", "Insufficient funds for operation"),
    BUSINESS_LIMIT_EXCEEDED("BUS_006", "Operation limit exceeded"),
    BUSINESS_INVALID_OPERATION("BUS_007", "Invalid operation"),
    BUSINESS_DEPENDENCY_EXISTS("BUS_008", "Cannot delete due to existing dependencies"),

    // Validation Errors (3000-3999)
    VALIDATION_INVALID_INPUT("VAL_001", "Invalid input provided"),
    VALIDATION_MISSING_REQUIRED_FIELD("VAL_002", "Required field is missing"),
    VALIDATION_INVALID_FORMAT("VAL_003", "Invalid data format"),
    VALIDATION_OUT_OF_RANGE("VAL_004", "Value is out of acceptable range"),
    VALIDATION_INVALID_DATE("VAL_005", "Invalid date or time value"),
    VALIDATION_INVALID_REFERENCE("VAL_006", "Invalid reference to another resource"),

    // System Errors (4000-4999)
    SYSTEM_INTERNAL_ERROR("SYS_001", "An internal error occurred"),
    SYSTEM_SERVICE_UNAVAILABLE("SYS_002", "Service temporarily unavailable"),
    SYSTEM_DATABASE_ERROR("SYS_003", "Database operation failed"),
    SYSTEM_EXTERNAL_SERVICE_ERROR("SYS_004", "External service error"),
    SYSTEM_CONFIGURATION_ERROR("SYS_005", "System configuration error"),
    SYSTEM_RATE_LIMIT_EXCEEDED("SYS_006", "Rate limit exceeded"),
    SYSTEM_MAINTENANCE_MODE("SYS_007", "System is under maintenance"),

    // Integration Errors (5000-5999)
    INTEGRATION_CONNECTION_FAILED("INT_001", "Failed to connect to external service"),
    INTEGRATION_TIMEOUT("INT_002", "External service timeout"),
    INTEGRATION_INVALID_RESPONSE("INT_003", "Invalid response from external service"),
    INTEGRATION_AUTHENTICATION_FAILED("INT_004", "External service authentication failed"),

    // File Operations (6000-6999)
    FILE_NOT_FOUND("FILE_001", "File not found"),
    FILE_UPLOAD_FAILED("FILE_002", "File upload failed"),
    FILE_INVALID_TYPE("FILE_003", "Invalid file type"),
    FILE_SIZE_EXCEEDED("FILE_004", "File size limit exceeded"),

    // Concurrency Errors (7000-7999)
    CONCURRENCY_CONFLICT("CONC_001", "Resource was modified by another user"),
    CONCURRENCY_LOCK_TIMEOUT("CONC_002", "Could not acquire resource lock"),

    // Unknown Error (9999)
    UNKNOWN_ERROR("ERR_999", "An unexpected error occurred");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return code + ": " + defaultMessage;
    }
}