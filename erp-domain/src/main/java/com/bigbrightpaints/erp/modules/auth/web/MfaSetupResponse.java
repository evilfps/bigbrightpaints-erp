package com.bigbrightpaints.erp.modules.auth.web;

import java.util.List;

public record MfaSetupResponse(
        String secret,
        String qrUri,
        List<String> recoveryCodes
) {}

