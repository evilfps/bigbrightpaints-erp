package com.bigbrightpaints.erp.modules.company.dto;

import java.time.Instant;

public record SuperAdminTenantReviewIntelligenceToggleDto(
    Long companyId, String companyCode, boolean reviewIntelligenceEnabled, Instant updatedAt) {}
