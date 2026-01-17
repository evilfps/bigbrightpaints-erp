package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class CompanyClock {

    private static final String DEFAULT_TIMEZONE = "UTC";

    /**
     * Override date for benchmark mode. When set, all calls to today() and now()
     * will return this date instead of the actual system date.
     * Format: yyyy-MM-dd (e.g., "2026-02-28")
     */
    @Value("${erp.benchmark.override-date:#{null}}")
    private String overrideDateString;

    public LocalDate today(Company company) {
        if (StringUtils.hasText(overrideDateString)) {
            return LocalDate.parse(overrideDateString);
        }
        return LocalDate.now(zoneId(company));
    }

    public Instant now(Company company) {
        if (StringUtils.hasText(overrideDateString)) {
            LocalDate overrideDate = LocalDate.parse(overrideDateString);
            return overrideDate.atStartOfDay(zoneId(company)).toInstant();
        }
        return ZonedDateTime.now(zoneId(company)).toInstant();
    }

    public ZoneId zoneId(Company company) {
        String timezone = company != null ? company.getTimezone() : null;
        return ZoneId.of(StringUtils.hasText(timezone) ? timezone : DEFAULT_TIMEZONE);
    }
}
