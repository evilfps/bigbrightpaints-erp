package com.bigbrightpaints.erp.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;

public final class IdempotencyHeaderUtils {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyHeaderUtils.class);

  private IdempotencyHeaderUtils() {}

  public static String resolveHeaderKey(
      String idempotencyKeyHeader, String legacyIdempotencyKeyHeader) {
    String primary = trimToNull(idempotencyKeyHeader);
    String legacy = trimToNull(legacyIdempotencyKeyHeader);
    if (primary != null && legacy != null && !primary.equals(legacy)) {
      log.warn(
          "Idempotency header mismatch detected between Idempotency-Key and X-Idempotency-Key");
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers")
          .withDetail("idempotencyKeyHeader", primary)
          .withDetail("legacyIdempotencyKeyHeader", legacy);
    }
    return primary != null ? primary : legacy;
  }

  public static String resolveBodyOrHeaderKey(
      String bodyKey, String idempotencyKeyHeader, String legacyIdempotencyKeyHeader) {
    String resolvedHeader = resolveHeaderKey(idempotencyKeyHeader, legacyIdempotencyKeyHeader);
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

  public static void rejectLegacyHeader(
      String legacyIdempotencyKeyHeader, String operationLabel, String canonicalPath) {
    String normalizedLegacyHeader = trimToNull(legacyIdempotencyKeyHeader);
    if (normalizedLegacyHeader == null) {
      return;
    }
    throw unsupportedLegacyHeader(
        "X-Idempotency-Key",
        normalizedLegacyHeader,
        "Idempotency-Key",
        canonicalPath,
        "X-Idempotency-Key is not supported for " + operationLabel + "; use Idempotency-Key");
  }

  public static ApplicationException unsupportedLegacyHeader(
      String legacyHeader,
      String legacyHeaderValue,
      String canonicalHeader,
      String canonicalPath,
      String message) {
    return new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, message)
        .withDetail("legacyHeader", legacyHeader)
        .withDetail("legacyHeaderValue", legacyHeaderValue)
        .withDetail("canonicalHeader", canonicalHeader)
        .withDetail("canonicalPath", canonicalPath);
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
