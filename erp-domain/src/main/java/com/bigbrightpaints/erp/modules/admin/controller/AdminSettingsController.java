package com.bigbrightpaints.erp.modules.admin.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/superadmin/settings")
public class AdminSettingsController {

  private static final String AUDIT_NOT_REQUESTED = "<not_requested>";
  private static final String AUDIT_REDACTED = "<redacted>";

  private final SystemSettingsService systemSettingsService;
  private final AuditService auditService;

  @Autowired
  public AdminSettingsController(
      SystemSettingsService systemSettingsService, AuditService auditService) {
    this.systemSettingsService = systemSettingsService;
    this.auditService = auditService;
  }

  @GetMapping
  @PreAuthorize(PortalRoleActionMatrix.SUPER_ADMIN_ONLY)
  public ApiResponse<SystemSettingsDto> getSettings() {
    return ApiResponse.success("Settings fetched", systemSettingsService.snapshot());
  }

  @PutMapping
  @PreAuthorize(PortalRoleActionMatrix.SUPER_ADMIN_ONLY)
  public ApiResponse<SystemSettingsDto> updateSettings(
      @Valid @RequestBody SystemSettingsUpdateRequest request) {
    SystemSettingsDto before = systemSettingsService.snapshot();
    SystemSettingsDto dto = systemSettingsService.update(request);
    recordSettingsUpdateAudit(before, request, dto);
    return ApiResponse.success("Settings updated", dto);
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
}
