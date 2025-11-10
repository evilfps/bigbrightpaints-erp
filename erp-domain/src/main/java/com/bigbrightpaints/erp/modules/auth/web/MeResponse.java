package com.bigbrightpaints.erp.modules.auth.web;

public record MeResponse(
        String email,
        String displayName,
        String companyId,
        boolean mfaEnabled
) {}
