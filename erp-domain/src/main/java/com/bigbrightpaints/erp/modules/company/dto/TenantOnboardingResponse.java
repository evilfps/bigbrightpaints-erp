package com.bigbrightpaints.erp.modules.company.dto;

public record TenantOnboardingResponse(
    Long companyId,
    String companyCode,
    String templateCode,
    String bootstrapMode,
    boolean seededChartOfAccounts,
    Integer accountsCreated,
    Long accountingPeriodId,
    boolean defaultAccountingPeriodCreated,
    String adminEmail,
    Long mainAdminUserId,
    boolean tenantAdminProvisioned,
    boolean credentialsEmailSent,
    java.time.Instant credentialsEmailedAt,
    java.time.Instant onboardingCompletedAt,
    boolean systemSettingsInitialized) {}
