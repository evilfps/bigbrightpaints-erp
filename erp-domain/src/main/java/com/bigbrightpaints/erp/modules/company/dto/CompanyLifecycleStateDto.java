package com.bigbrightpaints.erp.modules.company.dto;

public record CompanyLifecycleStateDto(
        Long companyId,
        String companyCode,
        String previousLifecycleState,
        String lifecycleState,
        String reason
) {}
