package com.bigbrightpaints.erp.core.util;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

public final class IdempotencyHeaderUtils {
  private static final String CANONICAL_IDEMPOTENCY_HEADER = "Idempotency-Key";

  private IdempotencyHeaderUtils() {}

  public static String resolveHeaderKey(String idempotencyKeyHeader) {
    return trimToNull(idempotencyKeyHeader);
  }

  public static String resolveBodyOrHeaderKey(String bodyKey, String idempotencyKeyHeader) {
    String resolvedHeader = resolveHeaderKey(idempotencyKeyHeader);
    String resolvedBody = trimToNull(bodyKey);
    if (resolvedBody != null) {
      if (resolvedHeader != null && !resolvedBody.equals(resolvedHeader)) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Idempotency key mismatch between header and request body")
            .withDetail("headerKey", resolvedHeader)
            .withDetail("bodyKey", resolvedBody);
      }
      return resolvedBody;
    }
    return resolvedHeader;
  }

  public static ApplicationException unsupportedLegacyHeader(
      String legacyHeader, String resourceDescription, String canonicalPath) {
    return new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            legacyHeader
                + " is not supported for "
                + resourceDescription
                + "; use "
                + CANONICAL_IDEMPOTENCY_HEADER)
        .withDetail("legacyHeader", legacyHeader)
        .withDetail("canonicalHeader", CANONICAL_IDEMPOTENCY_HEADER)
        .withDetail("canonicalPath", canonicalPath);
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
