package com.bigbrightpaints.erp.modules.admin.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.modules.admin.dto.AdminNotifyRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.ModuleGatingService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSettingsController {

  private static final String AUDIT_NOT_REQUESTED = "<not_requested>";
  private static final String AUDIT_REDACTED = "<redacted>";

  private final SystemSettingsService systemSettingsService;
  private final EmailService emailService;
  private final AuditService auditService;

  @Autowired
  public AdminSettingsController(
      SystemSettingsService systemSettingsService,
      EmailService emailService,
      AuditService auditService) {
    this.systemSettingsService = systemSettingsService;
    this.emailService = emailService;
    this.auditService = auditService;
  }

  // Backward-compatible constructor shape retained for existing test scaffolds.
  public AdminSettingsController(
      SystemSettingsService systemSettingsService,
      EmailService emailService,
      CompanyContextService companyContextService,
      TenantRuntimePolicyService tenantRuntimePolicyService,
      ExportApprovalService exportApprovalService,
      CreditRequestRepository creditRequestRepository,
      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
      PayrollRunRepository payrollRunRepository,
      ModuleGatingService moduleGatingService) {
    this(systemSettingsService, emailService, null);
  }

  // Backward-compatible constructor shape retained for existing test scaffolds.
  public AdminSettingsController(
      SystemSettingsService systemSettingsService,
      EmailService emailService,
      CompanyContextService companyContextService,
      TenantRuntimePolicyService tenantRuntimePolicyService,
      ExportApprovalService exportApprovalService,
      CreditRequestRepository creditRequestRepository,
      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository
          periodCloseRequestRepository,
      PayrollRunRepository payrollRunRepository,
      AuditService auditService,
      ModuleGatingService moduleGatingService) {
    this(systemSettingsService, emailService, auditService);
  }

  @GetMapping("/settings")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_ONLY)
  public ApiResponse<SystemSettingsDto> getSettings() {
    return ApiResponse.success("Settings fetched", systemSettingsService.snapshot());
  }

  @PutMapping("/settings")
  @PreAuthorize(PortalRoleActionMatrix.SUPER_ADMIN_ONLY)
  public ApiResponse<SystemSettingsDto> updateSettings(
      @Valid @RequestBody SystemSettingsUpdateRequest request) {
    SystemSettingsDto before = systemSettingsService.snapshot();
    requireSuperAdminForPeriodLockEnforcedChange(before, request);
    SystemSettingsDto dto = systemSettingsService.update(request);
    recordSettingsUpdateAudit(before, request, dto);
    return ApiResponse.success("Settings updated", dto);
  }

  @PostMapping("/notify")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_ONLY)
  public ApiResponse<String> notifyUser(@Valid @RequestBody AdminNotifyRequest request) {
    emailService.sendSimpleEmail(request.to(), request.subject(), request.body());
    return ApiResponse.success("Notification sent", "Email dispatched");
  }

  private void requireSuperAdminForPeriodLockEnforcedChange(
      SystemSettingsDto before, SystemSettingsUpdateRequest request) {
    if (request == null || request.periodLockEnforced() == null || before == null) {
      return;
    }
    if (before.periodLockEnforced() == request.periodLockEnforced()) {
      return;
    }
    if (!isSuperAdminActor()) {
      throw new com.bigbrightpaints.erp.core.exception.ApplicationException(
          com.bigbrightpaints.erp.core.exception.ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "SUPER_ADMIN authority required to change period lock enforcement");
    }
  }

  private void recordSettingsUpdateAudit(
      SystemSettingsDto before, SystemSettingsUpdateRequest request, SystemSettingsDto after) {
    if (auditService == null) {
      return;
    }
    Map<String, String> metadata = new java.util.LinkedHashMap<>();
    metadata.put("action", "admin_settings_update");
    if (before != null) {
      metadata.put("beforeAutoApprovalEnabled", Boolean.toString(before.autoApprovalEnabled()));
      metadata.put("beforePeriodLockEnforced", Boolean.toString(before.periodLockEnforced()));
      metadata.put(
          "beforeExportApprovalRequired", Boolean.toString(before.exportApprovalRequired()));
      metadata.put("beforePlatformAuthCode", before.platformAuthCode());
    }
    metadata.put(
        "requestedAutoApprovalEnabled",
        auditRequestedBoolean(request == null ? null : request.autoApprovalEnabled()));
    metadata.put(
        "requestedPeriodLockEnforced",
        auditRequestedBoolean(request == null ? null : request.periodLockEnforced()));
    metadata.put(
        "requestedExportApprovalRequired",
        auditRequestedBoolean(request == null ? null : request.exportApprovalRequired()));
    metadata.put("requestedPlatformAuthCode", auditRequestedPlatformAuthCode());
    if (after != null) {
      metadata.put("afterAutoApprovalEnabled", Boolean.toString(after.autoApprovalEnabled()));
      metadata.put("afterPeriodLockEnforced", Boolean.toString(after.periodLockEnforced()));
      metadata.put("afterExportApprovalRequired", Boolean.toString(after.exportApprovalRequired()));
      metadata.put("afterPlatformAuthCode", after.platformAuthCode());
    }
    String actor = SecurityActorResolver.resolveActorWithSystemProcessFallback();
    if (actor != null && !actor.isBlank()) {
      metadata.put("actor", actor);
    }
    auditService.logAuthSuccess(AuditEvent.CONFIGURATION_CHANGED, actor, null, metadata);
  }

  private String auditRequestedBoolean(Boolean value) {
    return value == null ? AUDIT_NOT_REQUESTED : value.toString();
  }

  private String auditRequestedPlatformAuthCode() {
    return AUDIT_REDACTED;
  }

  private boolean isSuperAdminActor() {
    org.springframework.security.core.Authentication authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .anyMatch(authority -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(authority));
  }
}
