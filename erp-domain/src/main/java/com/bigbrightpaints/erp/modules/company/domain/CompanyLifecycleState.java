package com.bigbrightpaints.erp.modules.company.domain;

import java.util.Locale;
import java.util.Optional;
import org.springframework.util.StringUtils;

public enum CompanyLifecycleState {
    ACTIVE,
    HOLD,
    BLOCKED;

    public static CompanyLifecycleState fromRequestValue(String rawValue) {
        return fromStoredValue(rawValue)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported company lifecycle state: " + rawValue));
    }

    public static Optional<CompanyLifecycleState> fromStoredValue(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return Optional.empty();
        }
        try {
            return Optional.of(CompanyLifecycleState.valueOf(rawValue.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
