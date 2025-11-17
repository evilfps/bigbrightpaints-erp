package com.bigbrightpaints.erp.core.exception;

import java.util.HashMap;
import java.util.Map;

/**
 * Base exception class for application-specific exceptions.
 * Uses error codes to provide consistent error responses.
 */
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;
    private final String userMessage;

    public ApplicationException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
        this.details = new HashMap<>();
    }

    public ApplicationException(ErrorCode errorCode, String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.details = new HashMap<>();
    }

    public ApplicationException(ErrorCode errorCode, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.details = new HashMap<>();
    }

    public ApplicationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.userMessage = errorCode.getDefaultMessage();
        this.details = new HashMap<>();
    }

    public ApplicationException withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return new HashMap<>(details);
    }

    public String getUserMessage() {
        return userMessage;
    }

    /**
     * Creates an error response suitable for the client.
     * In production, this should not include sensitive details.
     */
    public Map<String, Object> toErrorResponse(boolean includeDetails) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", errorCode.getCode());
        response.put("message", userMessage);

        if (includeDetails && !details.isEmpty()) {
            response.put("details", details);
        }

        return response;
    }
}