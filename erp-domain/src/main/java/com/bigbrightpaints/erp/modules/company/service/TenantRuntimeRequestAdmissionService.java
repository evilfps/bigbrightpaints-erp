package com.bigbrightpaints.erp.modules.company.service;

import org.springframework.stereotype.Service;

/**
 * Request-admission and auth-operation entry point for tenant runtime enforcement. Runtime policy
 * mutations and canonical snapshots remain in {@link TenantRuntimeEnforcementService}.
 */
@Service
public class TenantRuntimeRequestAdmissionService {

  private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

  public TenantRuntimeRequestAdmissionService(
      TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
    this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
  }

  public TenantRuntimeEnforcementService.TenantRequestAdmission beginRequest(
      String companyCode, String requestPath, String requestMethod, String actor) {
    return tenantRuntimeEnforcementService.admitRequest(
        companyCode, requestPath, requestMethod, actor);
  }

  public TenantRuntimeEnforcementService.TenantRequestAdmission beginRequest(
      String companyCode,
      String requestPath,
      String requestMethod,
      String actor,
      boolean policyControlPrivilegedActor) {
    return tenantRuntimeEnforcementService.admitRequest(
        companyCode, requestPath, requestMethod, actor, policyControlPrivilegedActor);
  }

  public void completeRequest(
      TenantRuntimeEnforcementService.TenantRequestAdmission admission, int responseStatus) {
    tenantRuntimeEnforcementService.completeRequestAdmission(admission, responseStatus);
  }

  public void enforceAuthOperationAllowed(String companyCode, String actor, String operation) {
    tenantRuntimeEnforcementService.enforceAuthOperation(companyCode, actor, operation);
  }

  public TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot(String companyCode) {
    return tenantRuntimeEnforcementService.snapshot(companyCode);
  }
}
