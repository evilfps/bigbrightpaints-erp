package com.bigbrightpaints.erp.core.audit;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Service for comprehensive audit logging.
 * Logs are written asynchronously to avoid impacting performance.
 */
@Service
public class AuditService {

  private static final Logger logger = LoggerFactory.getLogger(AuditService.class);
  private static final String AUTH_COMPANY_UNRESOLVED_SENTINEL = "__AUTH_COMPANY_UNRESOLVED__";
  private static final String UNKNOWN_AUTH_ACTOR = "UNKNOWN_AUTH_ACTOR";

  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private CompanyRepository companyRepository;

  /**
   * Self-reference to ensure @Async/@Transactional proxies apply even when calling from within this class.
   * Without this, calls like logSuccess() -> logEvent() become self-invocations and run in the caller's
   * transaction, making business operations susceptible to audit logging failures under SERIALIZABLE load.
   */
  @Autowired @Lazy private AuditService self;

  /**
   * Logs an audit event with full context.
   */
  public void logEvent(AuditEvent event, AuditStatus status, Map<String, String> metadata) {
    Map<String, String> requestContext = captureRequestContextMetadata();
    self.logEventAsync(event, status, metadata, null, null, requestContext);
  }

  public void logAuthSuccess(
      AuditEvent event, String username, String companyCode, Map<String, String> metadata) {
    Map<String, String> authMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    String usernameOverride = normalizeAuthUsernameOverride(username, authMetadata);
    enrichAuthMetadataWithAuthenticatedActorPublicId(authMetadata, usernameOverride);
    String companyCodeOverride = normalizeAuthCompanyOverride(companyCode, authMetadata);
    Map<String, String> requestContext = captureRequestContextMetadata();
    self.logEventAsync(
        event,
        AuditStatus.SUCCESS,
        authMetadata,
        usernameOverride,
        companyCodeOverride,
        requestContext);
  }

  public void logAuthFailure(
      AuditEvent event, String username, String companyCode, Map<String, String> metadata) {
    Map<String, String> authMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    String usernameOverride = normalizeAuthUsernameOverride(username, authMetadata);
    enrichAuthMetadataWithAuthenticatedActorPublicId(authMetadata, usernameOverride);
    String companyCodeOverride = normalizeAuthCompanyOverride(companyCode, authMetadata);
    Map<String, String> requestContext = captureRequestContextMetadata();
    self.logEventAsync(
        event,
        AuditStatus.FAILURE,
        authMetadata,
        usernameOverride,
        companyCodeOverride,
        requestContext);
  }

  @Async
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void logEventAsync(
      AuditEvent event,
      AuditStatus status,
      Map<String, String> metadata,
      String usernameOverride,
      String companyCodeOverride,
      Map<String, String> requestContext) {
    logEventInternal(
        event, status, metadata, usernameOverride, companyCodeOverride, requestContext);
  }

  private void logEventInternal(
      AuditEvent event,
      AuditStatus status,
      Map<String, String> metadata,
      String usernameOverride,
      String companyCodeOverride,
      Map<String, String> requestContext) {
    try {
      AuditLog.Builder builder =
          new AuditLog.Builder().eventType(event).status(status).timestamp(LocalDateTime.now());

      // Add user context
      boolean hasUsernameOverride = StringUtils.hasText(usernameOverride);
      String resolvedUsername = normalizeToken(usernameOverride);
      String resolvedUserId = extractMetadataActorPublicId(metadata);
      if (!hasUsernameOverride) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
          if (!StringUtils.hasText(resolvedUsername)) {
            resolvedUsername = normalizeToken(auth.getName());
          }
          if (!StringUtils.hasText(resolvedUserId)) {
            resolvedUserId = resolveAuthenticatedPublicId(auth);
          }
          if (!StringUtils.hasText(resolvedUserId)) {
            resolvedUserId = normalizeToken(auth.getName());
          }
        }
      } else if (!StringUtils.hasText(resolvedUserId)) {
        resolvedUserId = resolvedUsername;
      }
      if (StringUtils.hasText(resolvedUsername)) {
        builder.username(resolvedUsername);
      }
      if (StringUtils.hasText(resolvedUserId)) {
        builder.userId(resolvedUserId);
      }

      // Add company context
      String companyToken;
      boolean allowNumericIdFallback;
      if (AUTH_COMPANY_UNRESOLVED_SENTINEL.equals(companyCodeOverride)) {
        companyToken = null;
        allowNumericIdFallback = false;
      } else if (companyCodeOverride != null && !companyCodeOverride.isBlank()) {
        companyToken = companyCodeOverride;
        allowNumericIdFallback = false;
      } else {
        companyToken = CompanyContextHolder.getCompanyCode();
        allowNumericIdFallback = true;
      }
      Long companyId = resolveCompanyId(companyToken, allowNumericIdFallback);
      if (companyId != null) {
        builder.companyId(companyId);
      } else if (companyToken != null) {
        if (!allowNumericIdFallback && metadata != null) {
          metadata.putIfAbsent("authCompanyToken", companyToken.trim());
          metadata.put("authCompanyResolution", "UNRESOLVED");
        }
        logger.warn("Unable to resolve company token");
      }

      if (requestContext != null && !requestContext.isEmpty()) {
        builder
            .ipAddress(requestContext.get("ipAddress"))
            .userAgent(requestContext.get("userAgent"))
            .requestMethod(requestContext.get("requestMethod"))
            .requestPath(requestContext.get("requestPath"))
            .sessionId(requestContext.get("sessionId"));
        String traceId = requestContext.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
          builder.traceId(traceId);
        }
      }

      // Add metadata
      if (metadata != null && !metadata.isEmpty()) {
        builder.metadata(metadata);
      }

      AuditLog auditLog = builder.build();
      auditLogRepository.save(auditLog);

      logger.debug("Audit event logged: {} - Status: {}", event, status);

    } catch (Exception e) {
      // Don't let audit logging failures impact the main application
      logger.error("Failed to log audit event: {} - Status: {}", event, status, e);
    }
  }

  private String resolveAuthenticatedPublicId(Authentication authentication) {
    if (authentication == null) {
      return null;
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserPrincipal userPrincipal
        && userPrincipal.getUser() != null
        && userPrincipal.getUser().getPublicId() != null) {
      return userPrincipal.getUser().getPublicId().toString();
    }
    return normalizeUuidToken(authentication.getName());
  }

  private String extractMetadataActorPublicId(Map<String, String> metadata) {
    if (metadata == null) {
      return null;
    }
    return normalizeUuidToken(metadata.get("actorPublicId"));
  }

  private String normalizeUuidToken(String token) {
    String normalized = normalizeToken(token);
    if (!StringUtils.hasText(normalized) || !looksLikeUuid(normalized)) {
      return null;
    }
    try {
      return UUID.fromString(normalized).toString();
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private String normalizeToken(String token) {
    if (!StringUtils.hasText(token)) {
      return null;
    }
    return token.trim();
  }

  private boolean looksLikeUuid(String token) {
    if (!StringUtils.hasText(token) || token.length() != 36) {
      return false;
    }
    return token.charAt(8) == '-'
        && token.charAt(13) == '-'
        && token.charAt(18) == '-'
        && token.charAt(23) == '-';
  }

  private boolean shouldUseAuthenticatedIdentityForOverride(
      String resolvedUsername, Authentication authentication) {
    if (authentication == null) {
      return false;
    }
    String normalizedOverride = normalizeToken(resolvedUsername);
    String authName = normalizeToken(authentication.getName());
    return StringUtils.hasText(normalizedOverride)
        && StringUtils.hasText(authName)
        && authName.equalsIgnoreCase(normalizedOverride);
  }

  private void enrichAuthMetadataWithAuthenticatedActorPublicId(
      Map<String, String> metadata, String usernameOverride) {
    if (metadata == null || StringUtils.hasText(extractMetadataActorPublicId(metadata))) {
      return;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || !shouldUseAuthenticatedIdentityForOverride(usernameOverride, auth)) {
      return;
    }
    String actorPublicId = resolveAuthenticatedPublicId(auth);
    if (StringUtils.hasText(actorPublicId)) {
      metadata.put("actorPublicId", actorPublicId);
    }
  }

  private Map<String, String> captureRequestContextMetadata() {
    Map<String, String> context = new HashMap<>();
    try {
      ServletRequestAttributes requestAttributes =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (requestAttributes == null) {
        return context;
      }
      HttpServletRequest request = requestAttributes.getRequest();
      if (request == null) {
        return context;
      }
      String ipAddress = getClientIpAddress(request);
      if (ipAddress != null && !ipAddress.isBlank()) {
        context.put("ipAddress", ipAddress);
      }
      String userAgent = safeHeader(request, "User-Agent");
      if (userAgent != null && !userAgent.isBlank()) {
        context.put("userAgent", userAgent);
      }
      String requestMethod = safeRequestMethod(request);
      if (requestMethod != null && !requestMethod.isBlank()) {
        context.put("requestMethod", requestMethod);
      }
      String requestPath = safeRequestPath(request);
      if (requestPath != null && !requestPath.isBlank()) {
        context.put("requestPath", requestPath);
      }
      String sessionId = safeSessionId(request);
      if (sessionId != null && !sessionId.isBlank()) {
        context.put("sessionId", sessionId);
      }
      String traceId = safeHeader(request, "X-Trace-Id");
      if ((traceId == null || traceId.isBlank())) {
        traceId = safeTraceAttribute(request, "traceId");
      }
      if (traceId != null && !traceId.isBlank()) {
        context.put("traceId", traceId);
      }
    } catch (RuntimeException ex) {
      logger.debug(
          "Skipping request context enrichment for audit event due to request lifecycle state", ex);
    }
    return context;
  }

  private Long resolveCompanyId(String companyToken, boolean allowNumericIdFallback) {
    if (companyToken == null || companyToken.isBlank()) {
      return null;
    }
    String normalizedToken = companyToken.trim();
    Long numericToken = parseNumericToken(normalizedToken);
    Company byCode = companyRepository.findByCodeIgnoreCase(normalizedToken).orElse(null);
    if (byCode != null) {
      if (allowNumericIdFallback && numericToken != null) {
        Long idCandidate =
            companyRepository.findById(numericToken).map(Company::getId).orElse(null);
        if (idCandidate != null && !byCode.getId().equals(idCandidate)) {
          logger.warn(
              "Ambiguous numeric company token maps to code-id {} and entity-id {}; failing"
                  + " closed",
              byCode.getId(),
              idCandidate);
          return null;
        }
      }
      return byCode.getId();
    }
    if (!allowNumericIdFallback) {
      return null;
    }
    if (numericToken == null) {
      return null;
    }
    return companyRepository.findById(numericToken).map(Company::getId).orElse(null);
  }

  /**
   * Logs a successful event.
   */
  public void logSuccess(AuditEvent event) {
    self.logEvent(event, AuditStatus.SUCCESS, null);
  }

  /**
   * Logs a successful event with metadata.
   */
  public void logSuccess(AuditEvent event, Map<String, String> metadata) {
    self.logEvent(event, AuditStatus.SUCCESS, metadata);
  }

  /**
   * Logs a failed event.
   */
  public void logFailure(AuditEvent event, String reason) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("reason", safeString(reason));
    self.logEvent(event, AuditStatus.FAILURE, metadata);
  }

  /**
   * Logs a failed event with metadata.
   */
  public void logFailure(AuditEvent event, Map<String, String> metadata) {
    self.logEvent(event, AuditStatus.FAILURE, metadata);
  }

  /**
   * Logs a warning event.
   */
  public void logWarning(AuditEvent event, String message) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("message", safeString(message));
    self.logEvent(event, AuditStatus.WARNING, metadata);
  }

  /**
   * Logs an informational event.
   */
  public void logInfo(AuditEvent event, String message) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("message", safeString(message));
    self.logEvent(event, AuditStatus.INFO, metadata);
  }

  /**
   * Logs a security alert.
   */
  public void logSecurityAlert(String alertType, String description, Map<String, String> details) {
    Map<String, String> metadata = new java.util.HashMap<>();
    metadata.put("alertType", alertType);
    metadata.put("description", description);
    if (details != null) {
      metadata.putAll(details);
    }
    self.logEvent(AuditEvent.SECURITY_ALERT, AuditStatus.WARNING, metadata);
  }

  /**
   * Logs data access event.
   */
  public void logDataAccess(String resourceType, String resourceId, String operation) {
    String normalizedOperation = operation == null ? "" : operation.trim();
    if (normalizedOperation.isEmpty()) {
      normalizedOperation = "READ";
    }
    AuditEvent event =
        switch (normalizedOperation.toUpperCase()) {
          case "CREATE" -> AuditEvent.DATA_CREATE;
          case "READ" -> AuditEvent.DATA_READ;
          case "UPDATE" -> AuditEvent.DATA_UPDATE;
          case "DELETE" -> AuditEvent.DATA_DELETE;
          case "EXPORT" -> AuditEvent.DATA_EXPORT;
          default -> AuditEvent.DATA_READ;
        };

    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", safeString(resourceType));
    metadata.put("resourceId", safeString(resourceId));
    metadata.put("operation", normalizedOperation);
    self.logEvent(event, AuditStatus.SUCCESS, metadata);
  }

  /**
   * Logs sensitive data access.
   */
  public void logSensitiveDataAccess(String dataType, String reason) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("dataType", safeString(dataType));
    metadata.put("reason", reason != null ? reason : "Not specified");
    self.logEvent(AuditEvent.SENSITIVE_DATA_ACCESSED, AuditStatus.INFO, metadata);
  }

  private String safeString(String value) {
    return value == null ? "" : value;
  }

  private String normalizeAuthUsernameOverride(String username, Map<String, String> metadata) {
    if (username != null && !username.isBlank()) {
      return username.trim();
    }
    if (metadata != null) {
      metadata.putIfAbsent("authActorResolution", "UNRESOLVED");
    }
    return UNKNOWN_AUTH_ACTOR;
  }

  private String normalizeAuthCompanyOverride(String companyCode, Map<String, String> metadata) {
    if (companyCode != null && !companyCode.isBlank()) {
      String normalized = companyCode.trim();
      if (metadata != null) {
        metadata.putIfAbsent("authCompanyToken", normalized);
      }
      return normalized;
    }
    if (metadata != null) {
      metadata.putIfAbsent("authCompanyResolution", "UNRESOLVED");
    }
    return AUTH_COMPANY_UNRESOLVED_SENTINEL;
  }

  private Long parseNumericToken(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (!normalized.chars().allMatch(Character::isDigit)) {
      return null;
    }
    try {
      return Long.parseLong(normalized);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Gets the client IP address, handling proxy headers.
   */
  private String getClientIpAddress(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String[] headers = {
      "X-Forwarded-For",
      "Proxy-Client-IP",
      "WL-Proxy-Client-IP",
      "HTTP_X_FORWARDED_FOR",
      "HTTP_X_FORWARDED",
      "HTTP_X_CLUSTER_CLIENT_IP",
      "HTTP_CLIENT_IP",
      "HTTP_FORWARDED_FOR",
      "HTTP_FORWARDED",
      "X-Real-IP"
    };

    for (String header : headers) {
      String ip = safeHeader(request, header);
      if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
        // Handle comma-separated IPs (when going through multiple proxies)
        int commaIndex = ip.indexOf(',');
        if (commaIndex > 0) {
          return ip.substring(0, commaIndex).trim();
        }
        return ip;
      }
    }

    try {
      return request.getRemoteAddr();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeHeader(HttpServletRequest request, String header) {
    try {
      return request.getHeader(header);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeRequestMethod(HttpServletRequest request) {
    try {
      return request.getMethod();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeRequestPath(HttpServletRequest request) {
    try {
      return request.getRequestURI();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeSessionId(HttpServletRequest request) {
    try {
      var session = request.getSession(false);
      return session != null ? session.getId() : null;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private String safeTraceAttribute(HttpServletRequest request, String attributeName) {
    try {
      Object value = request.getAttribute(attributeName);
      return value != null ? value.toString() : null;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * Creates an audit context for a new request.
   */
  public AuditContext createContext() {
    return new AuditContext(UUID.randomUUID().toString(), System.currentTimeMillis());
  }

  /**
   * Context for tracking audit information across a request.
   */
  public static class AuditContext {
    private final String traceId;
    private final long startTime;

    public AuditContext(String traceId, long startTime) {
      this.traceId = traceId;
      this.startTime = startTime;
    }

    public String getTraceId() {
      return traceId;
    }

    public long getStartTime() {
      return startTime;
    }

    public long getDuration() {
      return System.currentTimeMillis() - startTime;
    }
  }
}
