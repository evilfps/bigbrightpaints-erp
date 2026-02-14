package com.bigbrightpaints.erp.modules.sales.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Central policy for which sales-order states contribute to dealer credit exposure
 * before an invoice is posted.
 */
public final class SalesOrderCreditExposurePolicy {

    private static final Set<String> PENDING_CREDIT_EXPOSURE_STATUSES = Set.of(
            "BOOKED",
            "RESERVED",
            "PENDING_PRODUCTION",
            "PENDING_INVENTORY",
            "PROCESSING",
            "READY_TO_SHIP",
            "CONFIRMED",
            "ON_HOLD"
    );

    private SalesOrderCreditExposurePolicy() {
    }

    public static Set<String> pendingCreditExposureStatuses() {
        return PENDING_CREDIT_EXPOSURE_STATUSES;
    }

    public static boolean isPendingCreditExposureStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return PENDING_CREDIT_EXPOSURE_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT));
    }
}
