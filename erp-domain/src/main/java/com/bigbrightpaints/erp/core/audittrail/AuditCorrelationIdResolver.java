package com.bigbrightpaints.erp.core.audittrail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

public final class AuditCorrelationIdResolver {

  private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
  private static final String HEADER_TRACE_ID = "X-Trace-Id";
  private static final String HEADER_REQUEST_ID = "X-Request-Id";
  private static final String ATTR_TRACE_ID = "traceId";
  private static final String ATTR_REQUEST_ID = "requestId";
  private static final String NAMESPACE_PREFIX = "AUDIT-CORRELATION|";

  private AuditCorrelationIdResolver() {}

  public static UUID resolveCorrelationId(HttpServletRequest request, String... fallbackKeys) {
    UUID explicit = parseUuidOrNull(header(request, HEADER_CORRELATION_ID));
    if (explicit != null) {
      return explicit;
    }

    List<String> candidates = new ArrayList<>();
    appendIfPresent(candidates, header(request, HEADER_TRACE_ID));
    appendIfPresent(candidates, header(request, HEADER_REQUEST_ID));
    appendIfPresent(candidates, requestAttribute(request, ATTR_TRACE_ID));
    appendIfPresent(candidates, requestAttribute(request, ATTR_REQUEST_ID));
    if (fallbackKeys != null) {
      for (String fallbackKey : fallbackKeys) {
        appendIfPresent(candidates, fallbackKey);
      }
    }

    for (String candidate : candidates) {
      UUID parsed = parseUuidOrNull(candidate);
      if (parsed != null) {
        return parsed;
      }
      if (StringUtils.hasText(candidate)) {
        return UUID.nameUUIDFromBytes(
            (NAMESPACE_PREFIX + candidate.trim()).getBytes(StandardCharsets.UTF_8));
      }
    }
    return null;
  }

  public static HttpServletRequest currentRequest() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes servletRequestAttributes)) {
      return null;
    }
    return servletRequestAttributes.getRequest();
  }

  public static UUID parseUuidOrNull(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return null;
    }
    try {
      return UUID.fromString(rawValue.trim());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static void appendIfPresent(List<String> target, String value) {
    if (!StringUtils.hasText(value)) {
      return;
    }
    target.add(value.trim());
  }

  private static String header(HttpServletRequest request, String headerName) {
    if (request == null || !StringUtils.hasText(headerName)) {
      return null;
    }
    String raw = request.getHeader(headerName);
    return StringUtils.hasText(raw) ? raw.trim() : null;
  }

  private static String requestAttribute(HttpServletRequest request, String attributeName) {
    if (request == null || !StringUtils.hasText(attributeName)) {
      return null;
    }
    Object raw = request.getAttribute(attributeName);
    if (raw == null) {
      return null;
    }
    String normalized = raw.toString();
    return StringUtils.hasText(normalized) ? normalized.trim() : null;
  }
}
