package com.bigbrightpaints.erp.core.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries an explicit HTTP status plus a frontend-safe auth/security error contract.
 */
public class AuthSecurityContractException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String code;
    private final String userMessage;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public AuthSecurityContractException(HttpStatus httpStatus, String code, String userMessage) {
        super(userMessage);
        this.httpStatus = httpStatus;
        this.code = code;
        this.userMessage = userMessage;
    }

    public AuthSecurityContractException withDetail(String key, Object value) {
        if (key != null && !key.isBlank() && value != null) {
            details.put(key, value);
        }
        return this;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public Map<String, Object> getDetails() {
        return new LinkedHashMap<>(details);
    }
}
