package com.bigbrightpaints.erp.modules.company.service;

import java.util.HashMap;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;

@Service
public class TenantLifecycleService {

  private static final String LIFECYCLE_UPDATED_REASON = "tenant-lifecycle-state-updated";

  private final AuditService auditService;

  public TenantLifecycleService(AuditService auditService) {
    this.auditService = auditService;
  }

  public CompanyLifecycleStateDto transition(
      Company company,
      CompanyLifecycleState requestedState,
      String requestedReason,
      Authentication authentication) {
    if (company == null) {
      throw ValidationUtils.invalidInput("Company is required");
    }
    CompanyLifecycleState targetState =
        requestedState == null ? CompanyLifecycleState.ACTIVE : requestedState;
    String lifecycleReason = normalizeReason(requestedReason);

    CompanyLifecycleState previousState = normalizeState(company.getLifecycleState());
    validateTransition(previousState, targetState);

    company.setLifecycleState(targetState);
    company.setLifecycleReason(lifecycleReason);
    auditTransition(company, previousState, targetState, lifecycleReason, authentication);

    return new CompanyLifecycleStateDto(
        company.getId(),
        company.getCode(),
        previousState.toExternalValue(),
        targetState.toExternalValue(),
        lifecycleReason);
  }

  CompanyLifecycleState normalizeState(CompanyLifecycleState state) {
    return state == null ? CompanyLifecycleState.ACTIVE : state;
  }

  void validateTransition(CompanyLifecycleState previousState, CompanyLifecycleState nextState) {
    if (previousState == nextState) {
      return;
    }
    boolean allowed =
        switch (previousState) {
          case ACTIVE ->
              nextState == CompanyLifecycleState.SUSPENDED
                  || nextState == CompanyLifecycleState.DEACTIVATED;
          case SUSPENDED ->
              nextState == CompanyLifecycleState.ACTIVE
                  || nextState == CompanyLifecycleState.DEACTIVATED;
          case DEACTIVATED -> nextState == CompanyLifecycleState.ACTIVE;
        };
    if (!allowed) {
      throw ValidationUtils.invalidState(
          "Invalid tenant lifecycle transition from " + previousState + " to " + nextState);
    }
  }

  String normalizeReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw ValidationUtils.invalidInput("Lifecycle reason is required");
    }
    return reason.trim();
  }

  private void auditTransition(
      Company company,
      CompanyLifecycleState previousState,
      CompanyLifecycleState targetState,
      String lifecycleReason,
      Authentication authentication) {
    if (auditService == null) {
      return;
    }
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("actor", resolveActor(authentication));
    metadata.put("reason", LIFECYCLE_UPDATED_REASON);
    metadata.put("targetCompanyCode", company.getCode());
    metadata.put("targetCompanyId", String.valueOf(company.getId()));
    metadata.put("previousLifecycleState", previousState.toExternalValue());
    metadata.put("companyLifecycleState", targetState.toExternalValue());
    metadata.put("companyLifecycleReason", lifecycleReason);
    metadata.put("lifecycleEvidence", "immutable-audit-log");
    auditService.logAuthSuccess(
        AuditEvent.CONFIGURATION_CHANGED,
        resolveActor(authentication),
        company.getCode(),
        metadata);
  }

  private String resolveActor(Authentication authentication) {
    if (authentication == null || !StringUtils.hasText(authentication.getName())) {
      return "anonymous";
    }
    return authentication.getName().trim();
  }
}
