package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;

public record SuperAdminTenantAdminEmailChangeRequestDto(
    Long requestId,
    Long companyId,
    String companyCode,
    Long adminUserId,
    String currentEmail,
    String requestedEmail,
    Instant verificationSentAt,
    Instant expiresAt) {}
