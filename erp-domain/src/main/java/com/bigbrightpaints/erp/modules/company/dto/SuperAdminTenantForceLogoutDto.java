package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;

public record SuperAdminTenantForceLogoutDto(
    Long companyId,
    String companyCode,
    int revokedUserCount,
    String reason,
    String actor,
    Instant occurredAt) {}
