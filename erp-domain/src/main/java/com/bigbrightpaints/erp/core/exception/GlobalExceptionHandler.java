package com.bigbrightpaints.erp.core.exception;

import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.MfaChallengeResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global exception handler that provides consistent error responses.
 * In production, sensitive details are logged but not exposed to clients.
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private boolean isProductionMode() {
        return "prod".equalsIgnoreCase(activeProfile) || "production".equalsIgnoreCase(activeProfile);
    }

    /**
     * Handles application-specific exceptions with error codes.
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleApplicationException(
            ApplicationException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        // Log full details server-side
        logger.error("Application error [{}] - Code: {}, Path: {}, User: {}",
                traceId, ex.getErrorCode().getCode(), request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous",
                ex);

        // Prepare client response
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ex.getErrorCode().getCode());
        errorResponse.put("message", ex.getUserMessage());
        errorResponse.put("traceId", traceId);
        errorResponse.put("timestamp", LocalDateTime.now());

        // Only include details in non-production environments
        if (!isProductionMode() && !ex.getDetails().isEmpty()) {
            errorResponse.put("details", ex.getDetails());
        }

        HttpStatus status = determineHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.failure("Request failed", errorResponse));
    }

    /**
     * Handles validation errors.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Validation error [{}] - Path: {}", traceId, request.getDescription(false));

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", "Validation failed");
        errorResponse.put("traceId", traceId);

        if (!isProductionMode()) {
            errorResponse.put("errors", fieldErrors);
        }

        return ResponseEntity.badRequest().body(ApiResponse.failure("Validation failed", errorResponse));
    }

    /**
     * Handles constraint violation exceptions.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Constraint violation [{}] - Path: {}", traceId, request.getRequestURI(), ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", "Validation constraint violated");
        errorResponse.put("traceId", traceId);

        if (!isProductionMode()) {
            errorResponse.put("details", ex.getMessage());
        }

        return ResponseEntity.badRequest().body(ApiResponse.failure("Validation failed", errorResponse));
    }

    /**
     * Handles MFA required exceptions.
     */
    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<ApiResponse<MfaChallengeResponse>> handleMfaRequired(
            MfaRequiredException ex, HttpServletRequest request) {

        logger.info("MFA required for user at path: {}", request.getRequestURI());

        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", ErrorCode.AUTH_MFA_REQUIRED.getCode());
        errorData.put("mfaRequired", true);

        ApiResponse<MfaChallengeResponse> body = ApiResponse.failure(
                ErrorCode.AUTH_MFA_REQUIRED.getDefaultMessage(),
                new MfaChallengeResponse(true));

        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(body);
    }

    /**
     * Handles invalid MFA exceptions.
     */
    @ExceptionHandler(InvalidMfaException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleInvalidMfa(
            InvalidMfaException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Invalid MFA attempt [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "unknown");

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.AUTH_MFA_INVALID.getCode());
        errorResponse.put("message", ErrorCode.AUTH_MFA_INVALID.getDefaultMessage());
        errorResponse.put("traceId", traceId);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("Authentication failed", errorResponse));
    }

    /**
     * Handles authentication exceptions.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Authentication failed [{}] - Path: {}, IP: {}",
                traceId, request.getRequestURI(), request.getRemoteAddr());

        ErrorCode errorCode = ex instanceof BadCredentialsException
                ? ErrorCode.AUTH_INVALID_CREDENTIALS
                : ErrorCode.AUTH_TOKEN_INVALID;

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", errorCode.getCode());
        errorResponse.put("message", errorCode.getDefaultMessage());
        errorResponse.put("traceId", traceId);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("Authentication failed", errorResponse));
    }

    /**
     * Handles access denied exceptions.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Access denied [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous");

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
        errorResponse.put("message", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getDefaultMessage());
        errorResponse.put("traceId", traceId);

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.failure("Access denied", errorResponse));
    }

    /**
     * Handles data integrity violations.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.error("Data integrity violation [{}] - Path: {}", traceId, request.getRequestURI(), ex);

        ErrorCode errorCode = ErrorCode.BUSINESS_CONSTRAINT_VIOLATION;
        String message = "Data constraint violation";

        // Check for common constraint violations
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("duplicate") || ex.getMessage().contains("unique")) {
                errorCode = ErrorCode.BUSINESS_DUPLICATE_ENTRY;
                message = "Duplicate entry found";
            } else if (ex.getMessage().contains("foreign key")) {
                errorCode = ErrorCode.BUSINESS_DEPENDENCY_EXISTS;
                message = "Referenced data exists";
            }
        }

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", errorCode.getCode());
        errorResponse.put("message", message);
        errorResponse.put("traceId", traceId);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure("Operation failed", errorResponse));
    }

    /**
     * Handles illegal argument exceptions.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Illegal argument [{}] - Path: {}, Message: {}",
                traceId, request.getRequestURI(), ex.getMessage());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", isProductionMode()
                ? "Invalid input provided"
                : ex.getMessage());
        errorResponse.put("traceId", traceId);

        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("Invalid request", errorResponse));
    }

    /**
     * Handles all other runtime exceptions.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleRuntime(
            RuntimeException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        // Log full exception details
        logger.error("Unexpected error [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous",
                ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.SYSTEM_INTERNAL_ERROR.getCode());
        errorResponse.put("message", isProductionMode()
                ? "An internal error occurred. Please try again later."
                : ex.getMessage());
        errorResponse.put("traceId", traceId);
        errorResponse.put("timestamp", LocalDateTime.now());

        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("Internal error", errorResponse));
    }

    /**
     * Handles generic exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGenericException(
            Exception ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        // Log full exception details
        logger.error("Unhandled exception [{}] - Path: {}, Type: {}",
                traceId, request.getRequestURI(), ex.getClass().getName(), ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.UNKNOWN_ERROR.getCode());
        errorResponse.put("message", "An unexpected error occurred");
        errorResponse.put("traceId", traceId);
        errorResponse.put("timestamp", LocalDateTime.now());

        if (!isProductionMode()) {
            errorResponse.put("type", ex.getClass().getSimpleName());
        }

        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("Unexpected error", errorResponse));
    }

    /**
     * Determines the appropriate HTTP status code based on the error code.
     */
    private HttpStatus determineHttpStatus(ErrorCode errorCode) {
        String codePrefix = errorCode.getCode().split("_")[0];

        return switch (codePrefix) {
            case "AUTH" -> HttpStatus.UNAUTHORIZED;
            case "VAL" -> HttpStatus.BAD_REQUEST;
            case "BUS" -> HttpStatus.CONFLICT;
            case "SYS" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INT" -> HttpStatus.BAD_GATEWAY;
            case "FILE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "CONC" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}