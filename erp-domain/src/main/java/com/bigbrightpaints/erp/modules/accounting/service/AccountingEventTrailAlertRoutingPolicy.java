package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * Contract mapper for downstream alerting on accounting event-trail failures.
 * Keep string outputs stable to avoid silent routing drift in incident pipelines.
 */
final class AccountingEventTrailAlertRoutingPolicy {

    static final String ACCOUNTING_EVENT_TRAIL_FAILURE_CODE = "ACCOUNTING_EVENT_TRAIL_PERSISTENCE_FAILURE";
    static final String ROUTING_VERSION = "ACCOUNTING_EVENT_TRAIL_V1";
    static final String ROUTE_SEV1_PAGE = "SEV1_PAGE";
    static final String ROUTE_SEV2_URGENT = "SEV2_URGENT";
    static final String ROUTE_SEV3_TICKET = "SEV3_TICKET";
    static final String ROUTE_UNMAPPED = "UNMAPPED";

    private AccountingEventTrailAlertRoutingPolicy() {
    }

    static String resolveRoute(String failureCode, String errorCategory, String policy) {
        if (!ACCOUNTING_EVENT_TRAIL_FAILURE_CODE.equals(failureCode)) {
            return ROUTE_UNMAPPED;
        }
        String normalizedCategory = normalize(errorCategory);
        return switch (normalizedCategory) {
            case "DATA_INTEGRITY" -> ROUTE_SEV1_PAGE;
            case "VALIDATION" -> ROUTE_SEV2_URGENT;
            case "PERSISTENCE" -> isStrict(policy) ? ROUTE_SEV2_URGENT : ROUTE_SEV3_TICKET;
            default -> ROUTE_SEV3_TICKET;
        };
    }

    private static boolean isStrict(String policy) {
        return "STRICT".equals(normalize(policy));
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
