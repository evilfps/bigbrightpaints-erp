package com.bigbrightpaints.erp.orchestrator.service;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;

public final class CorrelationIdentifierSanitizer {

  public static final int MAX_TRACE_ID_LENGTH = 128;
  public static final int MAX_REQUEST_ID_LENGTH = 128;
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

  private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9._:|\\-]+$");
  private static final String REQUEST_ID_HASH_PREFIX = "RIDH|";
  private static final int LOG_VISIBLE_CHARACTERS = 24;
  private static final int LOG_FINGERPRINT_CHARACTERS = 12;

  private CorrelationIdentifierSanitizer() {}

  public static String sanitizeRequiredIdempotencyKey(String idempotencyKey) {
    return sanitizeRequired("idempotencyKey", idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH);
  }

  public static String sanitizeOptionalIdempotencyKey(String idempotencyKey) {
    return sanitizeOptional("idempotencyKey", idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH);
  }

  public static String sanitizeRequiredTraceId(String traceId) {
    return sanitizeRequired("traceId", traceId, MAX_TRACE_ID_LENGTH);
  }

  public static String sanitizeOptionalTraceId(String traceId) {
    return sanitizeOptional("traceId", traceId, MAX_TRACE_ID_LENGTH);
  }

  public static String sanitizeOptionalRequestId(String requestId) {
    String normalized = sanitizeOptional("requestId", requestId, Integer.MAX_VALUE);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    if (normalized.length() <= MAX_REQUEST_ID_LENGTH) {
      return normalized;
    }
    return REQUEST_ID_HASH_PREFIX + IdempotencyUtils.sha256Hex(normalized);
  }

  public static String normalizeRequestId(String requestId, String idempotencyKey) {
    String normalizedRequestId = sanitizeOptionalRequestId(requestId);
    if (StringUtils.hasText(normalizedRequestId)) {
      return normalizedRequestId;
    }
    String normalizedIdempotencyKey = sanitizeOptionalIdempotencyKey(idempotencyKey);
    if (!StringUtils.hasText(normalizedIdempotencyKey)) {
      return null;
    }
    if (normalizedIdempotencyKey.length() <= MAX_REQUEST_ID_LENGTH) {
      return normalizedIdempotencyKey;
    }
    return REQUEST_ID_HASH_PREFIX + IdempotencyUtils.sha256Hex(normalizedIdempotencyKey);
  }

  public static String sanitizeTraceIdOrFallback(
      String candidate, Supplier<String> fallbackSupplier) {
    try {
      return sanitizeRequiredTraceId(candidate);
    } catch (RuntimeException ex) {
      if (fallbackSupplier == null) {
        throw ex;
      }
      return sanitizeRequiredTraceId(fallbackSupplier.get());
    }
  }

  public static String safeTraceForLog(String traceId) {
    return safeForLog(traceId, MAX_TRACE_ID_LENGTH);
  }

  public static String safeIdempotencyForLog(String idempotencyKey) {
    return safeForLog(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH);
  }

  public static String safeIdentifierForLog(String identifier) {
    return safeForLog(identifier, MAX_IDEMPOTENCY_KEY_LENGTH);
  }

  private static String safeForLog(String value, int maxLength) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    String fingerprint = fingerprint(normalized);
    if (containsDisallowedControlCharacter(normalized)
        || !SAFE_IDENTIFIER_PATTERN.matcher(normalized).matches()
        || normalized.length() > maxLength) {
      return "invalid#" + fingerprint;
    }
    return abbreviate(normalized, LOG_VISIBLE_CHARACTERS) + "#" + fingerprint;
  }

  private static String sanitizeRequired(String field, String value, int maxLength) {
    String normalized = sanitizeOptional(field, value, maxLength);
    if (StringUtils.hasText(normalized)) {
      return normalized;
    }
    throw new ApplicationException(
        ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, field + " is required");
  }

  private static String sanitizeOptional(String field, String value, int maxLength) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    if (containsDisallowedControlCharacter(normalized)) {
      throw invalidIdentifier(field, normalized, maxLength, "control_characters_not_allowed");
    }
    if (!SAFE_IDENTIFIER_PATTERN.matcher(normalized).matches()) {
      throw invalidIdentifier(field, normalized, maxLength, "disallowed_character_class");
    }
    if (normalized.length() > maxLength) {
      throw invalidIdentifier(field, normalized, maxLength, "length_exceeded");
    }
    return normalized;
  }

  private static boolean containsDisallowedControlCharacter(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\r' || ch == '\n' || Character.isISOControl(ch)) {
        return true;
      }
    }
    return false;
  }

  private static ApplicationException invalidIdentifier(
      String field, String value, int maxLength, String reason) {
    return new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Invalid " + field + " format")
        .withDetail("field", field)
        .withDetail("reason", reason)
        .withDetail("maxLength", maxLength)
        .withDetail("actualLength", value != null ? value.length() : 0)
        .withDetail("fingerprint", fingerprint(value));
  }

  private static String fingerprint(String value) {
    if (value == null) {
      return "000000000000";
    }
    String hex = IdempotencyUtils.sha256Hex(value);
    if (hex.length() <= LOG_FINGERPRINT_CHARACTERS) {
      return hex;
    }
    return hex.substring(0, LOG_FINGERPRINT_CHARACTERS);
  }

  private static String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, Math.max(0, maxLength));
  }
}
