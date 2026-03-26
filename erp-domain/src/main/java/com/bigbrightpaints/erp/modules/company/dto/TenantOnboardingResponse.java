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
    boolean tenantAdminProvisioned,
    boolean systemSettingsInitialized) {}
