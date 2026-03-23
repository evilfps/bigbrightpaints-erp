package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Static access to CompanyClock for domain/entity lifecycle hooks.
 * Defaults to a UTC CompanyClock in non-Spring contexts.
 */
@Component
public class CompanyTime {

    private static volatile CompanyClock companyClock;

    public CompanyTime(CompanyClock companyClock) {
        CompanyTime.companyClock = companyClock;
    }

    public static Instant now(Company company) {
        return firstNonNull(() -> requireClock().now(company), () -> fallbackClock().now(company));
    }

    public static Instant now() {
        return firstNonNull(() -> requireClock().now(null), () -> fallbackClock().now(null));
    }

    public static LocalDate today(Company company) {
        return firstNonNull(() -> requireClock().today(company), () -> fallbackClock().today(company));
    }

    public static LocalDate today() {
        return firstNonNull(() -> requireClock().today(null), () -> fallbackClock().today(null));
    }

    private static CompanyClock requireClock() {
        if (companyClock == null) {
            synchronized (CompanyTime.class) {
                if (companyClock == null) {
                    companyClock = fallbackClock();
                }
            }
        }
        return companyClock;
    }

    private static CompanyClock fallbackClock() {
        return new CompanyClock((java.time.Clock) null);
    }

    private static <T> T firstNonNull(Supplier<T> primary, Supplier<T> fallback) {
        T value = primary.get();
        return value != null ? value : fallback.get();
    }
}
