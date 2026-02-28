package com.bigbrightpaints.erp.modules.sales.util;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import org.springframework.util.StringUtils;

public final class SalesOrderReference {

    private static final int MAX_REFERENCE_LENGTH = 64;
    private static final int HASH_LENGTH = 12;

    private SalesOrderReference() {}

    public static String invoiceReference(SalesOrder order) {
        return invoiceReference(normalizeOrderNumber(order));
    }

    public static String invoiceReference(String orderNumber) {
        return withPrefix("INV-", orderNumber);
    }

    public static String cogsReference(SalesOrder order) {
        return cogsReference(normalizeOrderNumber(order));
    }

    public static String cogsReference(String orderNumber) {
        return withPrefix("COGS-", orderNumber);
    }

    public static String cogsReferencePrefix(SalesOrder order) {
        return cogsReferencePrefix(normalizeOrderNumber(order));
    }

    public static String cogsReferencePrefix(String orderNumber) {
        return withPrefix("COGS-", orderNumber);
    }

    public static String legacySalesJournalPrefix(SalesOrder order) {
        return legacySalesJournalPrefix(normalizeOrderNumber(order));
    }

    public static String legacySalesJournalPrefix(String orderNumber) {
        return withPrefix("SALE-", orderNumber);
    }

    public static String normalizeOrderNumber(SalesOrder order) {
        if (order == null) {
            return "UNKNOWN";
        }
        String orderNumber = order.getOrderNumber();
        if (!StringUtils.hasText(orderNumber) && order.getId() != null) {
            orderNumber = order.getId().toString();
        }
        return normalizeOrderNumber(orderNumber);
    }

    public static String normalizeOrderNumber(String orderNumber) {
        if (!StringUtils.hasText(orderNumber)) {
            return "UNKNOWN";
        }
        return orderNumber.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
    }

    private static String withPrefix(String prefix, String orderNumber) {
        String normalized = normalizeOrderNumber(orderNumber);
        String candidate = prefix + normalized;
        if (candidate.length() <= MAX_REFERENCE_LENGTH) {
            return candidate;
        }
        String hash = IdempotencyUtils.sha256Hex(candidate);
        if (hash.length() < HASH_LENGTH) {
            hash = (hash + "0".repeat(HASH_LENGTH)).substring(0, HASH_LENGTH);
        } else {
            hash = hash.substring(0, HASH_LENGTH);
        }
        hash = hash.toUpperCase();
        int maxTokenLength = Math.max(1, MAX_REFERENCE_LENGTH - prefix.length() - HASH_LENGTH - 2);
        String token = normalized.substring(0, Math.min(maxTokenLength, normalized.length()));
        return prefix + token + "-H" + hash;
    }

}
