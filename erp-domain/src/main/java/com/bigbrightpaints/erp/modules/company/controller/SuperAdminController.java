package com.bigbrightpaints.erp.modules.company.controller;

import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.modules.company.dto.*;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.SuperAdminTenantControlPlaneService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/superadmin")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminController {

  private final CompanyService companyService;
  private final SuperAdminTenantControlPlaneService controlPlaneService;

  public SuperAdminController(
      CompanyService companyService, SuperAdminTenantControlPlaneService controlPlaneService) {
    this.companyService = companyService;
    this.controlPlaneService = controlPlaneService;
  }

  @GetMapping("/dashboard")
  public ResponseEntity<ApiResponse<CompanySuperAdminDashboardDto>> dashboard() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Superadmin dashboard fetched", companyService.getSuperAdminDashboard()));
  }

  @GetMapping("/tenants")
  public ResponseEntity<ApiResponse<List<SuperAdminTenantSummaryDto>>> listTenants(
      @RequestParam(value = "status", required = false) String status) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Superadmin tenant list fetched", controlPlaneService.listTenants(status)));
  }

  @GetMapping("/tenants/{id}")
  public ResponseEntity<ApiResponse<SuperAdminTenantDetailDto>> getTenantDetail(
      @PathVariable("id") Long tenantId) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Superadmin tenant detail fetched", controlPlaneService.getTenantDetail(tenantId)));
  }

  @PutMapping("/tenants/{id}/lifecycle")
  public ResponseEntity<ApiResponse<CompanyLifecycleStateDto>> updateLifecycleState(
      @PathVariable("id") Long tenantId, @Valid @RequestBody CompanyLifecycleStateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant lifecycle state updated",
            controlPlaneService.updateLifecycleState(tenantId, request)));
  }

  @PutMapping("/tenants/{id}/limits")
  public ResponseEntity<ApiResponse<SuperAdminTenantLimitsDto>> updateTenantLimits(
      @PathVariable("id") Long tenantId, @Valid @RequestBody TenantLimitsUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant limits updated",
            controlPlaneService.updateLimits(
                tenantId,
                request.quotaMaxActiveUsers(),
                request.quotaMaxApiRequests(),
                request.quotaMaxStorageBytes(),
                request.quotaMaxConcurrentRequests(),
                request.quotaSoftLimitEnabled(),
                request.quotaHardLimitEnabled())));
  }

  @PutMapping("/tenants/{id}/modules")
  public ResponseEntity<ApiResponse<CompanyEnabledModulesDto>> updateTenantModules(
      @PathVariable("id") Long tenantId, @Valid @RequestBody TenantModulesUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant modules updated",
            controlPlaneService.updateModules(tenantId, request.enabledModules())));
  }

  @PostMapping("/tenants/{id}/support/warnings")
  public ResponseEntity<ApiResponse<CompanySupportWarningDto>> issueSupportWarning(
      @PathVariable("id") Long tenantId, @Valid @RequestBody TenantSupportWarningRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant warning issued",
            controlPlaneService.issueSupportWarning(
                tenantId,
                request.warningCategory(),
                request.message(),
                request.requestedLifecycleState(),
                request.gracePeriodHours())));
  }

  @PostMapping("/tenants/{id}/support/admin-password-reset")
  public ResponseEntity<ApiResponse<CompanyAdminCredentialResetDto>> resetTenantAdminPassword(
      @PathVariable("id") Long tenantId,
      @Valid @RequestBody TenantAdminPasswordResetRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Admin credentials reset and emailed",
            controlPlaneService.resetTenantAdminPassword(
                tenantId, request.adminEmail(), request.reason())));
  }

  @PutMapping("/tenants/{id}/support/context")
  public ResponseEntity<ApiResponse<SuperAdminTenantSupportContextDto>> updateSupportContext(
      @PathVariable("id") Long tenantId,
      @Valid @RequestBody TenantSupportContextUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant support context updated",
            controlPlaneService.updateSupportContext(
                tenantId, request.supportNotes(), request.supportTags())));
  }

  @GetMapping("/tenants/{id}/review-intelligence")
  public ResponseEntity<ApiResponse<SuperAdminTenantReviewIntelligenceToggleDto>>
      getReviewIntelligenceToggle(@PathVariable("id") Long tenantId) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant review intelligence toggle fetched",
            controlPlaneService.getReviewIntelligenceToggle(tenantId)));
  }

  @PutMapping("/tenants/{id}/review-intelligence")
  public ResponseEntity<ApiResponse<SuperAdminTenantReviewIntelligenceToggleDto>>
      updateReviewIntelligenceToggle(
          @PathVariable("id") Long tenantId,
          @Valid @RequestBody TenantReviewIntelligenceToggleRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant review intelligence toggle updated",
            controlPlaneService.updateReviewIntelligenceToggle(tenantId, request.enabled())));
  }

  @PostMapping("/tenants/{id}/force-logout")
  public ResponseEntity<ApiResponse<SuperAdminTenantForceLogoutDto>> forceLogout(
      @PathVariable("id") Long tenantId,
      @RequestBody(required = false) TenantForceLogoutRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant sessions revoked",
            controlPlaneService.forceLogoutAllUsers(
                tenantId, request == null ? null : request.reason())));
  }

  @PutMapping("/tenants/{id}/admins/main")
  public ResponseEntity<ApiResponse<MainAdminSummaryDto>> replaceMainAdmin(
      @PathVariable("id") Long tenantId, @Valid @RequestBody TenantMainAdminUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant main admin replaced",
            controlPlaneService.replaceMainAdmin(tenantId, request.adminUserId())));
  }

  @PostMapping("/tenants/{id}/admins/{adminId}/email-change/request")
  public ResponseEntity<ApiResponse<SuperAdminTenantAdminEmailChangeRequestDto>>
      requestAdminEmailChange(
          @PathVariable("id") Long tenantId,
          @PathVariable("adminId") Long adminId,
          @Valid @RequestBody TenantAdminEmailChangeRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant admin email change requested",
            controlPlaneService.requestAdminEmailChange(tenantId, adminId, request.newEmail())));
  }

  @PostMapping("/tenants/{id}/admins/{adminId}/email-change/confirm")
  public ResponseEntity<ApiResponse<SuperAdminTenantAdminEmailChangeConfirmationDto>>
      confirmAdminEmailChange(
          @PathVariable("id") Long tenantId,
          @PathVariable("adminId") Long adminId,
          @Valid @RequestBody TenantAdminEmailChangeConfirmRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Tenant admin email change confirmed",
            controlPlaneService.confirmAdminEmailChange(
                tenantId, adminId, request.requestId(), request.verificationToken())));
  }

  public record TenantModulesUpdateRequest(
      @NotNull Set<@NotBlank @Size(max = 64) String> enabledModules) {}

  public record TenantLimitsUpdateRequest(
      @Min(value = 0, message = "quotaMaxActiveUsers must be greater than or equal to 0")
          Long quotaMaxActiveUsers,
      @Min(value = 0, message = "quotaMaxApiRequests must be greater than or equal to 0")
          Long quotaMaxApiRequests,
      @Min(value = 0, message = "quotaMaxStorageBytes must be greater than or equal to 0")
          Long quotaMaxStorageBytes,
      @Min(value = 0, message = "quotaMaxConcurrentRequests must be greater than or equal to 0")
          Long quotaMaxConcurrentRequests,
      Boolean quotaSoftLimitEnabled,
      Boolean quotaHardLimitEnabled) {}

  public record TenantSupportWarningRequest(
      @Size(max = 100, message = "warningCategory must be at most 100 characters")
          String warningCategory,
      @NotBlank @Size(max = 500, message = "message must be at most 500 characters") String message,
      @Size(max = 32, message = "requestedLifecycleState must be at most 32 characters")
          String requestedLifecycleState,
      @Min(value = 1, message = "gracePeriodHours must be at least 1") Integer gracePeriodHours) {}

  public record TenantAdminPasswordResetRequest(
      @Email @NotBlank String adminEmail,
      @Size(max = 300, message = "reason must be at most 300 characters") String reason) {}

  public record TenantSupportContextUpdateRequest(
      @Size(max = 4000, message = "supportNotes must be at most 4000 characters")
          String supportNotes,
      Set<@NotBlank @Size(max = 64) String> supportTags) {}

  public record TenantReviewIntelligenceToggleRequest(@NotNull Boolean enabled) {}

  public record TenantForceLogoutRequest(
      @Size(max = 300, message = "reason must be at most 300 characters") String reason) {}

  public record TenantMainAdminUpdateRequest(@NotNull Long adminUserId) {}

  public record TenantAdminEmailChangeRequest(@Email @NotBlank String newEmail) {}

  public record TenantAdminEmailChangeConfirmRequest(
      @NotNull Long requestId, @NotBlank @Size(max = 255) String verificationToken) {}
}
