package com.bigbrightpaints.erp.core.idempotency;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;

public final class IdempotencyUtils {

    private IdempotencyUtils() {
    }

    public static String normalizeKey(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    public static String normalizeToken(String value) {
        return value != null ? value.trim() : "";
    }

    public static String normalizeUpperToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public static String normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public static String sha256Hex(String value) {
        return DigestUtils.sha256Hex(value == null ? "" : value);
    }

    public static String sha256Hex(String value, int length) {
        String full = sha256Hex(value);
        return full.substring(0, Math.min(Math.max(length, 0), full.length()));
    }

    public static boolean isDataIntegrityViolation(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof DataIntegrityViolationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
