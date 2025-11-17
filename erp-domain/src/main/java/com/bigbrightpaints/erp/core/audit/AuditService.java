package com.bigbrightpaints.erp.core.audit;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Service for comprehensive audit logging.
 * Logs are written asynchronously to avoid impacting performance.
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    /**
     * Logs an audit event with full context.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(AuditEvent event, AuditStatus status, Map<String, String> metadata) {
        try {
            AuditLog.Builder builder = new AuditLog.Builder()
                .eventType(event)
                .status(status)
                .timestamp(LocalDateTime.now());

            // Add user context
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                builder.username(auth.getName());
                if (auth.getPrincipal() != null) {
                    // Extract user ID if available
                    builder.userId(auth.getName());
                }
            }

            // Add company context
            String companyIdStr = CompanyContextHolder.getCompanyId();
            if (companyIdStr != null) {
                try {
                    Long companyId = Long.parseLong(companyIdStr);
                    builder.companyId(companyId);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid company ID format: {}", companyIdStr);
                }
            }

            // Add request context
            ServletRequestAttributes requestAttributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (requestAttributes != null) {
                HttpServletRequest request = requestAttributes.getRequest();
                builder.ipAddress(getClientIpAddress(request))
                       .userAgent(request.getHeader("User-Agent"))
                       .requestMethod(request.getMethod())
                       .requestPath(request.getRequestURI())
                       .sessionId(request.getSession(false) != null ?
                                 request.getSession().getId() : null);

                // Add trace ID if present
                String traceId = request.getHeader("X-Trace-Id");
                if (traceId == null) {
                    traceId = (String) request.getAttribute("traceId");
                }
                if (traceId != null) {
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

    /**
     * Logs a successful event.
     */
    public void logSuccess(AuditEvent event) {
        logEvent(event, AuditStatus.SUCCESS, null);
    }

    /**
     * Logs a successful event with metadata.
     */
    public void logSuccess(AuditEvent event, Map<String, String> metadata) {
        logEvent(event, AuditStatus.SUCCESS, metadata);
    }

    /**
     * Logs a failed event.
     */
    public void logFailure(AuditEvent event, String reason) {
        logEvent(event, AuditStatus.FAILURE, Map.of("reason", reason));
    }

    /**
     * Logs a failed event with metadata.
     */
    public void logFailure(AuditEvent event, Map<String, String> metadata) {
        logEvent(event, AuditStatus.FAILURE, metadata);
    }

    /**
     * Logs a warning event.
     */
    public void logWarning(AuditEvent event, String message) {
        logEvent(event, AuditStatus.WARNING, Map.of("message", message));
    }

    /**
     * Logs an informational event.
     */
    public void logInfo(AuditEvent event, String message) {
        logEvent(event, AuditStatus.INFO, Map.of("message", message));
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
        logEvent(AuditEvent.SECURITY_ALERT, AuditStatus.WARNING, metadata);
    }

    /**
     * Logs data access event.
     */
    public void logDataAccess(String resourceType, String resourceId, String operation) {
        AuditEvent event = switch (operation.toUpperCase()) {
            case "CREATE" -> AuditEvent.DATA_CREATE;
            case "READ" -> AuditEvent.DATA_READ;
            case "UPDATE" -> AuditEvent.DATA_UPDATE;
            case "DELETE" -> AuditEvent.DATA_DELETE;
            case "EXPORT" -> AuditEvent.DATA_EXPORT;
            default -> AuditEvent.DATA_READ;
        };

        logEvent(event, AuditStatus.SUCCESS, Map.of(
            "resourceType", resourceType,
            "resourceId", resourceId,
            "operation", operation
        ));
    }

    /**
     * Logs sensitive data access.
     */
    public void logSensitiveDataAccess(String dataType, String reason) {
        logEvent(AuditEvent.SENSITIVE_DATA_ACCESSED, AuditStatus.INFO, Map.of(
            "dataType", dataType,
            "reason", reason != null ? reason : "Not specified"
        ));
    }

    /**
     * Gets the client IP address, handling proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
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
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // Handle comma-separated IPs (when going through multiple proxies)
                int commaIndex = ip.indexOf(',');
                if (commaIndex > 0) {
                    return ip.substring(0, commaIndex).trim();
                }
                return ip;
            }
        }

        return request.getRemoteAddr();
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