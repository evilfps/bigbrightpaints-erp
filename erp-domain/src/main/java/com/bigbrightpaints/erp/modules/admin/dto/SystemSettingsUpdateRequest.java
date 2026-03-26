package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

public record SystemSettingsUpdateRequest(
    List<String> allowedOrigins,
    Boolean autoApprovalEnabled,
    Boolean periodLockEnforced,
    Boolean exportApprovalRequired,
    String platformAuthCode,
    Boolean mailEnabled,
    String mailFromAddress,
    String mailBaseUrl,
    Boolean sendCredentials,
    Boolean sendPasswordReset) {}
