package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

public record AdminSelfSettingsDto(
    String email,
    String displayName,
    String companyCode,
    boolean mfaEnabled,
    boolean mustChangePassword,
    List<String> roles,
    TenantRuntimeMetricsDto tenantRuntime,
    long activeSessionEstimate) {}
