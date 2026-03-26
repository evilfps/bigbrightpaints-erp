package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;

public record SuperAdminTenantAdminEmailChangeConfirmationDto(
    Long requestId,
    Long companyId,
    String companyCode,
    Long adminUserId,
    String updatedEmail,
    Instant verifiedAt,
    Instant confirmedAt) {}
