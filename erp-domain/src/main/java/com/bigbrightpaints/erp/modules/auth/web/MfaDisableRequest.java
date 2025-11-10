package com.bigbrightpaints.erp.modules.auth.web;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.util.StringUtils;

public record MfaDisableRequest(String code, String recoveryCode) {

    @AssertTrue(message = "Provide either code or recoveryCode")
    public boolean hasVerifier() {
        return StringUtils.hasText(code) || StringUtils.hasText(recoveryCode);
    }
}

