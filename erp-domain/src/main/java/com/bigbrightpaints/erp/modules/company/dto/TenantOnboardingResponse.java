package com.bigbrightpaints.erp.modules.company.dto;

public record TenantOnboardingResponse(
        Long companyId,
        String companyCode,
        String templateCode,
        Integer accountsCreated,
        Long accountingPeriodId,
        String adminEmail,
        boolean credentialsEmailSent,
        boolean systemSettingsInitialized
) {
}
