package com.bigbrightpaints.erp.modules.company.dto;

import java.util.Set;

public record SuperAdminTenantSupportContextDto(
    Long companyId, String companyCode, String supportNotes, Set<String> supportTags) {}
