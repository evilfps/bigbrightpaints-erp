package com.bigbrightpaints.erp.core.exception;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.MfaChallengeResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CoreFallbackExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(CoreFallbackExceptionHandler.class);

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    private boolean isProductionMode() {
        if (!StringUtils.hasText(activeProfile)) {
            return true;
        }
        for (String profile : activeProfile.split(",")) {
            String normalized = profile.trim();
            if ("prod".equalsIgnoreCase(normalized) || "production".equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(CreditLimitExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleCreditLimitExceeded(
            CreditLimitExceededException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Credit limit exceeded [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous", ex);
        Map<String, Object> data = new HashMap<>();
        data.put("code", "CREDIT_LIMIT_EXCEEDED");
        data.put("message", ex.getUserMessage());
        data.put("traceId", traceId);
        data.put("timestamp", LocalDateTime.now());
        data.put("details", ex.getDetails());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(ex.getUserMessage(), data));
    }

    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<ApiResponse<MfaChallengeResponse>> handleMfaRequired(MfaRequiredException ex,
                                                                                HttpServletRequest request) {
        logger.info("MFA required for user at path: {}", request.getRequestURI());
        ApiResponse<MfaChallengeResponse> body = ApiResponse.failure(
                ErrorCode.AUTH_MFA_REQUIRED.getDefaultMessage(),
                new MfaChallengeResponse(true));
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(body);
    }

    @ExceptionHandler(InvalidMfaException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleInvalidMfa(InvalidMfaException ex,
                                                                              HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Invalid MFA attempt [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "unknown");
        Map<String, Object> data = new HashMap<>();
        data.put("code", ErrorCode.AUTH_MFA_INVALID.getCode());
        data.put("message", ErrorCode.AUTH_MFA_INVALID.getDefaultMessage());
        data.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("Authentication failed", data));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAuthenticationException(AuthenticationException ex,
                                                                                           HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Authentication failed [{}] - Path: {}, IP: {}", traceId, request.getRequestURI(), request.getRemoteAddr());
        ErrorCode errorCode;
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        String responseMessage = "Authentication failed";
        if (ex instanceof LockedException) {
            errorCode = ErrorCode.AUTH_ACCOUNT_LOCKED;
            responseMessage = errorCode.getDefaultMessage();
        } else if (ex instanceof BadCredentialsException) {
            errorCode = ErrorCode.AUTH_INVALID_CREDENTIALS;
        } else {
            errorCode = ErrorCode.AUTH_TOKEN_INVALID;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", errorCode.getCode());
        data.put("message", errorCode.getDefaultMessage());
        data.put("traceId", traceId);
        return ResponseEntity.status(status).body(ApiResponse.failure(responseMessage, data));
    }

    @ExceptionHandler(AuthSecurityContractException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAuthSecurityContract(
            AuthSecurityContractException ex,
            HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Auth/security contract failure [{}] - Path: {}, status: {}, code: {}",
                traceId,
                request.getRequestURI(),
                ex.getHttpStatus().value(),
                ex.getCode());
        Map<String, Object> data = new HashMap<>();
        data.put("code", ex.getCode());
        data.put("message", ex.getUserMessage());
        data.put("traceId", traceId);
        data.putAll(ex.getDetails());
        return ResponseEntity.status(ex.getHttpStatus()).body(ApiResponse.failure(ex.getUserMessage(), data));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAccessDenied(AccessDeniedException ex,
                                                                                HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Access denied [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous");
        String userMessage = PortalRoleActionMatrix.resolveAccessDeniedMessage(
                SecurityContextHolder.getContext().getAuthentication(),
                request);
        if (!StringUtils.hasText(userMessage)) {
            userMessage = "Access denied";
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
        data.put("message", userMessage);
        data.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure(userMessage, data));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                                          HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.error("Data integrity violation [{}] - Path: {}", traceId, request.getRequestURI(), ex);
        ErrorCode errorCode = ErrorCode.BUSINESS_CONSTRAINT_VIOLATION;
        String message = "Data constraint violation";
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("duplicate") || ex.getMessage().contains("unique")) {
                errorCode = ErrorCode.BUSINESS_DUPLICATE_ENTRY;
                message = "Duplicate entry found";
            } else if (ex.getMessage().contains("foreign key")) {
                errorCode = ErrorCode.BUSINESS_DEPENDENCY_EXISTS;
                message = "Referenced data exists";
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", errorCode.getCode());
        data.put("message", message);
        data.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("Operation failed", data));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalState(IllegalStateException ex,
                                                                                HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.warn("Illegal state [{}] - Path: {}, Message: {}", traceId, request.getRequestURI(), ex.getMessage());
        String message = isProductionMode() ? ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage() : ex.getMessage();
        Map<String, Object> data = new HashMap<>();
        data.put("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        data.put("message", message);
        data.put("traceId", traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(isProductionMode() ? "Invalid state" : ex.getMessage(), data));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleRuntime(RuntimeException ex,
                                                                          HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.error("Unexpected error [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous", ex);
        Map<String, Object> data = new HashMap<>();
        data.put("code", ErrorCode.SYSTEM_INTERNAL_ERROR.getCode());
        data.put("message", isProductionMode() ? "An internal error occurred. Please try again later." : ex.getMessage());
        data.put("traceId", traceId);
        data.put("timestamp", LocalDateTime.now());
        return ResponseEntity.internalServerError().body(ApiResponse.failure("Internal error", data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGenericException(Exception ex,
                                                                                   HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        logger.error("Unhandled exception [{}] - Path: {}, Type: {}", traceId, request.getRequestURI(), ex.getClass().getName(), ex);
        Map<String, Object> data = new HashMap<>();
        data.put("code", ErrorCode.UNKNOWN_ERROR.getCode());
        data.put("message", "An unexpected error occurred");
        data.put("traceId", traceId);
        data.put("timestamp", LocalDateTime.now());
        if (!isProductionMode()) {
            data.put("type", ex.getClass().getSimpleName());
        }
        return ResponseEntity.internalServerError().body(ApiResponse.failure("Unexpected error", data));
    }
}
