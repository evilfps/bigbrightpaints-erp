package com.bigbrightpaints.erp.core.audit;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * Generic routing contract for non-accounting integration-failure audit markers.
 */
public final class IntegrationFailureAlertRoutingPolicy {

    public static final String ROUTING_VERSION = "INTEGRATION_FAILURE_V1";
    public static final String MALFORMED_REQUEST_FAILURE_CODE = "MALFORMED_REQUEST_PAYLOAD";
    public static final String SETTLEMENT_OPERATION_FAILURE_CODE = "SETTLEMENT_OPERATION_FAILED";
    public static final String ROUTE_SEV1_PAGE = "SEV1_PAGE";
    public static final String ROUTE_SEV2_URGENT = "SEV2_URGENT";
    public static final String ROUTE_SEV3_TICKET = "SEV3_TICKET";
    public static final String ROUTE_UNMAPPED = "UNMAPPED";

    private IntegrationFailureAlertRoutingPolicy() {
    }

    public static String resolveRoute(String failureCode, String category) {
        String normalizedFailureCode = normalize(failureCode);
        String normalizedCategory = normalize(category);
        if (MALFORMED_REQUEST_FAILURE_CODE.equals(normalizedFailureCode)
                && "REQUEST-PARSE".equals(normalizedCategory)) {
            return ROUTE_SEV3_TICKET;
        }
        if (SETTLEMENT_OPERATION_FAILURE_CODE.equals(normalizedFailureCode)) {
            return switch (normalizedCategory) {
                case "CONCURRENCY", "DATA_INTEGRITY", "SYSTEM" -> ROUTE_SEV2_URGENT;
                case "VALIDATION", "BUSINESS" -> ROUTE_SEV3_TICKET;
                default -> ROUTE_UNMAPPED;
            };
        }
        return ROUTE_UNMAPPED;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
