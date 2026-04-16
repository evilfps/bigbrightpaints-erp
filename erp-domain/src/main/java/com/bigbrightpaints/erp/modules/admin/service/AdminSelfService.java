package com.bigbrightpaints.erp.modules.admin.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.admin.dto.AdminSelfSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
public class AdminSelfService {

  private final SupportTicketAccessSupport supportTicketAccessSupport;
  private final CompanyContextService companyContextService;
  private final TenantRuntimePolicyService tenantRuntimePolicyService;
  private final AuditLogRepository auditLogRepository;

  public AdminSelfService(
      SupportTicketAccessSupport supportTicketAccessSupport,
      CompanyContextService companyContextService,
      TenantRuntimePolicyService tenantRuntimePolicyService,
      AuditLogRepository auditLogRepository) {
    this.supportTicketAccessSupport = supportTicketAccessSupport;
    this.companyContextService = companyContextService;
    this.tenantRuntimePolicyService = tenantRuntimePolicyService;
    this.auditLogRepository = auditLogRepository;
  }

  @Transactional(readOnly = true)
  public AdminSelfSettingsDto settings() {
    UserAccount actor = supportTicketAccessSupport.requireCurrentUser();
    Company company = companyContextService.requireCurrentCompany();

    List<String> roles =
        actor.getRoles().stream().map(role -> role.getName()).sorted(Comparator.naturalOrder()).toList();

    TenantRuntimeMetricsDto runtimeMetrics = tenantRuntimePolicyService.metrics();
    long activeSessionEstimate = auditLogRepository.countDistinctSessionActivityByCompanyId(company.getId());

    return new AdminSelfSettingsDto(
        actor.getEmail(),
        actor.getDisplayName(),
        company.getCode(),
        actor.isMfaEnabled(),
        actor.isMustChangePassword(),
        roles,
        runtimeMetrics,
        activeSessionEstimate);
  }
}
