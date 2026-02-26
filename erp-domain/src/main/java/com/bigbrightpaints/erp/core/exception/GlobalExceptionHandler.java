package com.bigbrightpaints.erp.core.exception;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureAlertRoutingPolicy;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.MfaChallengeResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Global exception handler that provides consistent error responses.
 * In production, sensitive details are logged but not exposed to clients.
 */
@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BULK_VARIANT_PATH = "/api/v1/accounting/catalog/products/bulk-variants";
    private static final String BULK_VARIANT_OPERATION = "catalog-bulk-variants";
    private static final List<String> BULK_VARIANT_RESPONSE_DETAIL_ALLOWLIST = List.of(
            "generated",
            "conflicts",
            "wouldCreate",
            "created",
            "operation");
    private static final Set<String> SETTLEMENT_FAILURE_DETAIL_ALLOWLIST = Set.of(
            IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY,
            IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE,
            IntegrationFailureMetadataSchema.KEY_PARTNER_ID,
            IntegrationFailureMetadataSchema.KEY_INVOICE_ID,
            IntegrationFailureMetadataSchema.KEY_PURCHASE_ID,
            IntegrationFailureMetadataSchema.KEY_OUTSTANDING_AMOUNT,
            IntegrationFailureMetadataSchema.KEY_APPLIED_AMOUNT,
            IntegrationFailureMetadataSchema.KEY_ALLOCATION_COUNT);

    @Autowired(required = false)
    private AuditService auditService;

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    private boolean isProductionMode() {
        if (activeProfile == null || activeProfile.isBlank()) {
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

    /**
     * Handles credit limit failures with a stable machine code and detailed payload.
     */
    @ExceptionHandler(CreditLimitExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleCreditLimitExceeded(
            CreditLimitExceededException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Credit limit exceeded [{}] - Path: {}, User: {}",
                traceId, request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous",
                ex);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", "CREDIT_LIMIT_EXCEEDED");
        errorResponse.put("message", ex.getUserMessage());
        errorResponse.put("traceId", traceId);
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("details", ex.getDetails());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(ex.getUserMessage(), errorResponse));
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
        errorResponse.put("reason", ex.getUserMessage());
        errorResponse.put("traceId", traceId);
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("path", request.getRequestURI());

        Map<String, Object> details = resolveResponseDetails(ex, request, ex.getDetails());
        if (!details.isEmpty()) {
            errorResponse.put("details", details);
        }

        logSettlementFailureAudit(request, traceId, ex);

        HttpStatus status = determineHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.failure(ex.getUserMessage(), errorResponse));
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

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        String reason = buildValidationReason(fieldErrors);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", reason);
        errorResponse.put("reason", reason);
        errorResponse.put("traceId", traceId);
        errorResponse.put("errors", fieldErrors);
        return ResponseEntity.badRequest().body(ApiResponse.failure(reason, errorResponse));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String traceId = UUID.randomUUID().toString();
        String reason = "Failed to read request";
        String detail = resolveMostSpecificMessage(ex);
        logger.warn("Malformed request [{}] - Path: {}", traceId, request.getDescription(false));

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", reason);
        errorResponse.put("reason", reason);
        errorResponse.put("traceId", traceId);
        if (!isProductionMode() && StringUtils.hasText(detail)) {
            errorResponse.put("details", detail);
        }

        HttpServletRequest servletRequest = request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest()
                : null;
        logMalformedRequestAudit(servletRequest, traceId, reason, detail);
        return ResponseEntity.badRequest().body(ApiResponse.failure(reason, errorResponse));
    }

    /**
     * Handles constraint violation exceptions.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Constraint violation [{}] - Path: {}", traceId, request.getRequestURI(), ex);

        Map<String, String> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String rawPath = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "value";
            String field = rawPath;
            int dot = rawPath.lastIndexOf('.');
            if (dot >= 0 && dot < rawPath.length() - 1) {
                field = rawPath.substring(dot + 1);
            }
            violations.putIfAbsent(field, violation.getMessage());
        });
        String reason = buildValidationReason(violations);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", reason);
        errorResponse.put("reason", reason);
        errorResponse.put("traceId", traceId);
        if (!violations.isEmpty()) {
            errorResponse.put("errors", violations);
        }
        return ResponseEntity.badRequest().body(ApiResponse.failure(reason, errorResponse));
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

        String reason = resolveIllegalArgumentMessage(ex.getMessage());
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        errorResponse.put("message", reason);
        errorResponse.put("reason", reason);
        errorResponse.put("traceId", traceId);

        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(reason, errorResponse));
    }

    /**
     * Handles illegal state exceptions (e.g., credit limit exceeded, missing configuration).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {

        String traceId = UUID.randomUUID().toString();

        logger.warn("Illegal state [{}] - Path: {}, Message: {}",
                traceId, request.getRequestURI(), ex.getMessage());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        errorResponse.put("message", isProductionMode()
                ? ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage()
                : ex.getMessage());
        errorResponse.put("traceId", traceId);

        String clientMessage = isProductionMode()
                ? "Invalid state"
                : ex.getMessage();

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(clientMessage, errorResponse));
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

    private String buildValidationReason(Map<String, String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Validation failed";
        }
        StringBuilder builder = new StringBuilder("Validation failed: ");
        int count = 0;
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            if (count > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append(" ").append(entry.getValue());
            count++;
            if (count >= 3) {
                break;
            }
        }
        if (errors.size() > 3) {
            builder.append("; and ").append(errors.size() - 3).append(" more");
        }
        return builder.toString();
    }

    private Map<String, Object> resolveResponseDetails(ApplicationException ex,
                                                       HttpServletRequest request,
                                                       Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        if (!isProductionMode()) {
            return details;
        }
        return sanitizeBulkVariantConflictDetails(ex, request, details);
    }

    private Map<String, Object> sanitizeBulkVariantConflictDetails(ApplicationException ex,
                                                                   HttpServletRequest request,
                                                                   Map<String, Object> details) {
        if (!isBulkVariantConflictDetailsSafeToExpose(ex, request, details)) {
            return Map.of();
        }
        Map<String, Object> sanitizedDetails = new LinkedHashMap<>();
        for (String key : BULK_VARIANT_RESPONSE_DETAIL_ALLOWLIST) {
            if (details.containsKey(key)) {
                sanitizedDetails.put(key, details.get(key));
            }
        }
        return sanitizedDetails;
    }

    private boolean isBulkVariantConflictDetailsSafeToExpose(ApplicationException ex,
                                                             HttpServletRequest request,
                                                             Map<String, Object> details) {
        if (ex == null || request == null || ex.getErrorCode() != ErrorCode.CONCURRENCY_CONFLICT) {
            return false;
        }
        if (!isBulkVariantEndpoint(request)) {
            return false;
        }
        Object operation = details.get("operation");
        return BULK_VARIANT_OPERATION.equals(operation);
    }

    private boolean isBulkVariantEndpoint(HttpServletRequest request) {
        for (String candidatePath : resolveNormalizedRequestPaths(request)) {
            if (matchesEndpointPath(candidatePath, BULK_VARIANT_PATH)) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveNormalizedRequestPaths(HttpServletRequest request) {
        if (request == null) {
            return List.of();
        }

        String servletPath = normalizeEndpointPath(request.getServletPath());
        String pathInfo = normalizeEndpointPath(request.getPathInfo());
        String combinedServletPath = normalizeEndpointPath(joinServletPathAndPathInfo(servletPath, pathInfo));
        String requestUri = normalizeEndpointPath(stripContextPath(request.getRequestURI(), request.getContextPath()));

        return List.of(
                combinedServletPath,
                servletPath,
                pathInfo,
                requestUri);
    }

    private String joinServletPathAndPathInfo(String servletPath, String pathInfo) {
        if (!StringUtils.hasText(pathInfo)) {
            return servletPath;
        }
        if (!StringUtils.hasText(servletPath) || "/".equals(servletPath)) {
            return pathInfo;
        }
        return servletPath + pathInfo;
    }

    private String stripContextPath(String requestUri, String contextPath) {
        if (!StringUtils.hasText(requestUri)) {
            return "";
        }
        if (StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)) {
            String stripped = requestUri.substring(contextPath.length());
            return StringUtils.hasText(stripped) ? stripped : "/";
        }
        return requestUri;
    }

    private boolean matchesEndpointPath(String normalizedPath, String endpointPath) {
        if (!StringUtils.hasText(normalizedPath) || !StringUtils.hasText(endpointPath)) {
            return false;
        }
        return normalizedPath.equals(endpointPath) || normalizedPath.endsWith(endpointPath);
    }

    private String normalizeEndpointPath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void logSettlementFailureAudit(HttpServletRequest request,
                                           String traceId,
                                           ApplicationException ex) {
        if (auditService == null || request == null || ex == null) {
            return;
        }
        String requestPath = request.getRequestURI();
        if (!StringUtils.hasText(requestPath) || !requestPath.startsWith("/api/v1/accounting/settlements/")) {
            return;
        }
        String failureCode = IntegrationFailureAlertRoutingPolicy.SETTLEMENT_OPERATION_FAILURE_CODE;
        String errorCategory = classifyIntegrationFailureCategory(ex.getErrorCode());

        Map<String, String> metadata = new HashMap<>();
        metadata.put("category", "settlement-failure");
        IntegrationFailureMetadataSchema.applyRequiredFields(
                metadata,
                failureCode,
                errorCategory,
                IntegrationFailureAlertRoutingPolicy.ROUTING_VERSION,
                IntegrationFailureAlertRoutingPolicy.resolveRoute(failureCode, errorCategory));
        metadata.put("errorCode", ex.getErrorCode() != null ? ex.getErrorCode().getCode() : ErrorCode.UNKNOWN_ERROR.getCode());
        metadata.put("traceId", traceId);
        metadata.put("requestMethod", request.getMethod());
        metadata.put("requestPath", requestPath);
        metadata.put("settlementType", resolveSettlementType(requestPath));
        appendSettlementFailureDetails(metadata, ex);
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }

    private void appendSettlementFailureDetails(Map<String, String> metadata, ApplicationException ex) {
        if (metadata == null || ex == null || ex.getDetails() == null || ex.getDetails().isEmpty()) {
            return;
        }
        for (String key : SETTLEMENT_FAILURE_DETAIL_ALLOWLIST) {
            putTrimmedMetadataIfPresent(metadata, key, ex.getDetails().get(key));
        }
    }

    private String resolveSettlementType(String requestPath) {
        if (!StringUtils.hasText(requestPath)) {
            return "UNKNOWN";
        }
        if (requestPath.contains("/dealers")) {
            return "DEALER";
        }
        if (requestPath.contains("/suppliers")) {
            return "SUPPLIER";
        }
        return "UNKNOWN";
    }

    private String classifyIntegrationFailureCategory(ErrorCode errorCode) {
        if (errorCode == null) {
            return "UNKNOWN";
        }
        String code = errorCode.getCode();
        if (!StringUtils.hasText(code)) {
            return "UNKNOWN";
        }
        if (code.startsWith("VAL_")) {
            return "VALIDATION";
        }
        if (code.startsWith("CONC_")) {
            return "CONCURRENCY";
        }
        if (code.startsWith("DATA_")) {
            return "DATA_INTEGRITY";
        }
        if (code.startsWith("SYS_") || code.startsWith("INT_")) {
            return "SYSTEM";
        }
        if (code.startsWith("BUS_")) {
            return "BUSINESS";
        }
        return "UNKNOWN";
    }

    private String resolveIllegalArgumentMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "Invalid input provided";
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("No enum constant ")) {
            String token = trimmed.substring("No enum constant ".length()).trim();
            if (!StringUtils.hasText(token) || token.endsWith(".")) {
                return "Invalid option provided";
            }
            int lastDot = token.lastIndexOf('.');
            String invalidValue = lastDot >= 0 && lastDot < token.length() - 1
                    ? token.substring(lastDot + 1)
                    : token;
            if (StringUtils.hasText(invalidValue)) {
                return "Invalid option '" + invalidValue + "'";
            }
            return "Invalid option provided";
        }
        return trimmed;
    }

    private void logMalformedRequestAudit(HttpServletRequest request,
                                          String traceId,
                                          String reason,
                                          String detail) {
        if (auditService == null) {
            return;
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("category", "request-parse");
        metadata.put("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode());
        metadata.put("traceId", traceId);
        metadata.put("reason", reason);
        String failureCode = IntegrationFailureAlertRoutingPolicy.MALFORMED_REQUEST_FAILURE_CODE;
        IntegrationFailureMetadataSchema.applyRequiredFields(
                metadata,
                failureCode,
                "VALIDATION",
                IntegrationFailureAlertRoutingPolicy.ROUTING_VERSION,
                IntegrationFailureAlertRoutingPolicy.resolveRoute(failureCode, "request-parse"));
        if (request != null) {
            metadata.put("requestMethod", request.getMethod());
            metadata.put("requestPath", request.getRequestURI());
        }
        if (StringUtils.hasText(detail)) {
            putTrimmedMetadataIfPresent(metadata, "detail", detail);
        }
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }

    private String resolveMostSpecificMessage(Throwable ex) {
        if (ex == null) {
            return null;
        }
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private String trimMetadata(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = sanitizeMetadataValue(value);
        if (sanitized.length() <= 500) {
            return sanitized;
        }
        return sanitized.substring(0, 500);
    }

    private void putTrimmedMetadataIfPresent(Map<String, String> metadata, String key, Object value) {
        if (metadata == null || value == null) {
            return;
        }
        String normalized = trimMetadata(String.valueOf(value));
        if (StringUtils.hasText(normalized)) {
            metadata.put(key, normalized);
        }
    }

    private String sanitizeMetadataValue(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean isControlCharacter = Character.isISOControl(current);
            boolean isWhitespaceCharacter = Character.isWhitespace(current);
            if (isControlCharacter || isWhitespaceCharacter) {
                if (!previousWhitespace) {
                    normalized.append(' ');
                    previousWhitespace = true;
                }
                continue;
            }
            normalized.append(current);
            previousWhitespace = false;
        }
        return normalized.toString().trim();
    }
}
