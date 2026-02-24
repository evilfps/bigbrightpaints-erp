package com.bigbrightpaints.erp.modules.company.dto;

public record CompanyAdminCredentialResetDto(
        Long companyId,
        String companyCode,
        String adminEmail,
        String status
) {
}
