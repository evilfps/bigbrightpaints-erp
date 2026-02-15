package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import org.springframework.util.StringUtils;

public final class IdempotencyHeaderUtils {

    private IdempotencyHeaderUtils() {
    }

    public static String resolveHeaderKey(String idempotencyKeyHeader, String legacyIdempotencyKeyHeader) {
        String primary = trimToNull(idempotencyKeyHeader);
        String legacy = trimToNull(legacyIdempotencyKeyHeader);
        if (primary != null && legacy != null && !primary.equals(legacy)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Idempotency key header mismatch between Idempotency-Key and X-Idempotency-Key")
                    .withDetail("idempotencyKeyHeader", primary)
                    .withDetail("legacyIdempotencyKeyHeader", legacy);
        }
        return primary != null ? primary : legacy;
    }

    public static String resolveBodyOrHeaderKey(String bodyKey,
                                                String idempotencyKeyHeader,
                                                String legacyIdempotencyKeyHeader) {
        String resolvedHeader = resolveHeaderKey(idempotencyKeyHeader, legacyIdempotencyKeyHeader);
        String resolvedBody = trimToNull(bodyKey);
        if (resolvedBody != null) {
            if (resolvedHeader != null && !resolvedBody.equals(resolvedHeader)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Idempotency key mismatch between header and request body")
                        .withDetail("headerKey", resolvedHeader)
                        .withDetail("bodyKey", resolvedBody);
            }
            return resolvedBody;
        }
        return resolvedHeader;
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
