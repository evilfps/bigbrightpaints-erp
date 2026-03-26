package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

public record SystemSettingsDto(
    List<String> allowedOrigins,
    boolean autoApprovalEnabled,
    boolean periodLockEnforced,
    boolean exportApprovalRequired,
    String platformAuthCode,
    boolean mailEnabled,
    String mailFromAddress,
    String mailBaseUrl,
    boolean sendCredentials,
    boolean sendPasswordReset) {}
