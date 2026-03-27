package com.bigbrightpaints.erp.modules.admin.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.admin.dto.*;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.ModuleGatingService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSettingsController {
  private static final String CREDIT_REQUEST_APPROVAL_ACTION =
      "APPROVE_DEALER_CREDIT_LIMIT_REQUEST";
  private static final String CREDIT_OVERRIDE_APPROVAL_ACTION = "APPROVE_DISPATCH_CREDIT_OVERRIDE";
  private static final String PAYROLL_APPROVAL_ACTION = "APPROVE_PAYROLL_RUN";
  private static final String CREDIT_REQUEST_APPROVE_ENDPOINT =
      "/api/v1/credit/limit-requests/{id}/approve";
  private static final String CREDIT_REQUEST_REJECT_ENDPOINT =
      "/api/v1/credit/limit-requests/{id}/reject";
  private static final String CREDIT_OVERRIDE_APPROVE_ENDPOINT =
      "/api/v1/credit/override-requests/{id}/approve";
  private static final String CREDIT_OVERRIDE_REJECT_ENDPOINT =
      "/api/v1/credit/override-requests/{id}/reject";
  private static final String PAYROLL_APPROVE_ENDPOINT = "/api/v1/payroll/runs/{id}/approve";
  private static final String EXPORT_REQUEST_APPROVAL_ACTION = "APPROVE_EXPORT_REQUEST";
  private static final String PERIOD_CLOSE_APPROVAL_ACTION = "APPROVE_ACCOUNTING_PERIOD_CLOSE";
  private static final String EXPORT_REQUEST_APPROVE_ENDPOINT =
      "/api/v1/admin/exports/{id}/approve";
  private static final String EXPORT_REQUEST_REJECT_ENDPOINT = "/api/v1/admin/exports/{id}/reject";
  private static final String PERIOD_CLOSE_APPROVE_ENDPOINT =
      "/api/v1/accounting/periods/{id}/approve-close";
  private static final String PERIOD_CLOSE_REJECT_ENDPOINT =
      "/api/v1/accounting/periods/{id}/reject-close";
  private static final String AUDIT_NOT_REQUESTED = "<not_requested>";
  private static final String AUDIT_REDACTED = "<redacted>";

  private final SystemSettingsService systemSettingsService;
  private final EmailService emailService;
  private final CompanyContextService companyContextService;
  private final TenantRuntimePolicyService tenantRuntimePolicyService;
  private final ExportApprovalService exportApprovalService;
  private final CreditRequestRepository creditRequestRepository;
  private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
  private final PeriodCloseRequestRepository periodCloseRequestRepository;
  private final PayrollRunRepository payrollRunRepository;
  private final AuditService auditService;
  private final ModuleGatingService moduleGatingService;

  @Autowired
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
    this(
        systemSettingsService,
        emailService,
        companyContextService,
        tenantRuntimePolicyService,
        exportApprovalService,
        creditRequestRepository,
        creditLimitOverrideRequestRepository,
        null,
        payrollRunRepository,
        null,
        moduleGatingService);
  }

  public AdminSettingsController(
      SystemSettingsService systemSettingsService,
      EmailService emailService,
      CompanyContextService companyContextService,
      TenantRuntimePolicyService tenantRuntimePolicyService,
      ExportApprovalService exportApprovalService,
      CreditRequestRepository creditRequestRepository,
      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
      PeriodCloseRequestRepository periodCloseRequestRepository,
      PayrollRunRepository payrollRunRepository,
      AuditService auditService,
      ModuleGatingService moduleGatingService) {
    this.systemSettingsService = systemSettingsService;
    this.emailService = emailService;
    this.companyContextService = companyContextService;
    this.tenantRuntimePolicyService = tenantRuntimePolicyService;
    this.exportApprovalService = exportApprovalService;
    this.creditRequestRepository = creditRequestRepository;
    this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
    this.periodCloseRequestRepository = periodCloseRequestRepository;
    this.payrollRunRepository = payrollRunRepository;
    this.auditService = auditService;
    this.moduleGatingService = moduleGatingService;
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

  @PutMapping("/exports/{requestId}/approve")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
  public ApiResponse<ExportRequestDto> approveExportRequest(@PathVariable Long requestId) {
    return ApiResponse.success("Export request approved", exportApprovalService.approve(requestId));
  }

  @PutMapping("/exports/{requestId}/reject")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
  public ApiResponse<ExportRequestDto> rejectExportRequest(
      @PathVariable Long requestId,
      @RequestBody(required = false) ExportRequestDecisionRequest request) {
    return ApiResponse.success(
        "Export request rejected",
        exportApprovalService.reject(requestId, request != null ? request.reason() : null));
  }

  @PostMapping("/notify")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_ONLY)
  public ApiResponse<String> notifyUser(@Valid @RequestBody AdminNotifyRequest request) {
    emailService.sendSimpleEmail(request.to(), request.subject(), request.body());
    return ApiResponse.success("Notification sent", "Email dispatched");
  }

  @GetMapping("/approvals")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_ACCOUNTING_SUPER_ADMIN)
  @Transactional(readOnly = true)
  public ApiResponse<AdminApprovalsResponse> approvals() {
    Company company = companyContextService.requireCurrentCompany();
    boolean includeSensitiveApprovalRequesterDetails = canViewSensitiveApprovalRequesterDetails();
    List<AdminApprovalItemDto> creditRequestApprovals =
        creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company).stream()
            .map(
                request ->
                    toCreditRequestApprovalItem(request, includeSensitiveApprovalRequesterDetails))
            .toList();

    List<AdminApprovalItemDto> creditOverrideApprovals =
        creditLimitOverrideRequestRepository
            .findPendingByCompanyOrderByCreatedAtDesc(company)
            .stream()
            .map(this::toCreditOverrideApprovalItem)
            .toList();

    List<AdminApprovalItemDto> creditApprovals =
        Stream.concat(creditRequestApprovals.stream(), creditOverrideApprovals.stream())
            .sorted(
                Comparator.comparing(
                    AdminApprovalItemDto::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

    List<AdminApprovalItemDto> payrollApprovals =
        isHrPayrollEnabled(company)
            ? payrollRunRepository
                .findByCompanyAndStatusOrderByCreatedAtDesc(
                    company, PayrollRun.PayrollStatus.CALCULATED)
                .stream()
                .map(this::toPayrollApprovalItem)
                .toList()
            : List.of();

    List<AdminApprovalItemDto> periodCloseApprovals =
        periodCloseRequestRepository == null
            ? List.of()
            : periodCloseRequestRepository
                .findPendingByCompanyOrderByRequestedAtDesc(company)
                .stream()
                .map(this::toPeriodCloseApprovalItem)
                .toList();

    List<AdminApprovalItemDto> exportApprovals =
        exportApprovalService.listPending().stream()
            .map(request -> toExportApprovalItem(request, includeSensitiveApprovalRequesterDetails))
            .toList();

    AdminApprovalsResponse response =
        new AdminApprovalsResponse(
            creditApprovals, payrollApprovals, periodCloseApprovals, exportApprovals);
    return ApiResponse.success("Approvals fetched", response);
  }

  private AdminApprovalItemDto approvalItem(
      AdminApprovalItemDto.OriginType originType,
      AdminApprovalItemDto.OwnerType ownerType,
      Long id,
      UUID publicId,
      String reference,
      String status,
      String summary,
      String actionType,
      String actionLabel,
      String approveEndpoint,
      String rejectEndpoint,
      Instant createdAt) {
    return approvalItem(
        originType,
        ownerType,
        id,
        publicId,
        reference,
        status,
        summary,
        null,
        null,
        actionType,
        actionLabel,
        approveEndpoint,
        rejectEndpoint,
        createdAt);
  }

  private AdminApprovalItemDto approvalItem(
      AdminApprovalItemDto.OriginType originType,
      AdminApprovalItemDto.OwnerType ownerType,
      Long id,
      UUID publicId,
      String reference,
      String status,
      String summary,
      Long requesterUserId,
      String requesterEmail,
      String actionType,
      String actionLabel,
      String approveEndpoint,
      String rejectEndpoint,
      Instant createdAt) {
    return new AdminApprovalItemDto(
        originType,
        ownerType,
        id,
        publicId,
        reference,
        status,
        summary,
        null,
        null,
        requesterUserId,
        requesterEmail,
        actionType,
        actionLabel,
        approveEndpoint,
        rejectEndpoint,
        createdAt);
  }

  private AdminApprovalItemDto toCreditRequestApprovalItem(
      CreditRequest request, boolean includeSensitiveRequesterDetails) {
    String reference = "CLR-" + request.getId();
    String dealerLabel =
        request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
            ? request.getDealer().getName()
            : "Unknown dealer";
    String summary =
        "Approve permanent dealer credit-limit request "
            + reference
            + " for "
            + dealerLabel
            + " amount "
            + toAmountString(request.getAmountRequested());
    if (StringUtils.hasText(request.getReason())) {
      summary = summary + " (reason: " + request.getReason().trim() + ")";
    }
    Long requesterUserId = includeSensitiveRequesterDetails ? request.getRequesterUserId() : null;
    String requesterEmail =
        includeSensitiveRequesterDetails && StringUtils.hasText(request.getRequesterEmail())
            ? request.getRequesterEmail().trim()
            : null;
    if (requesterEmail != null) {
      summary = summary + " (requested by " + requesterEmail + ")";
    }
    return approvalItem(
        AdminApprovalItemDto.OriginType.CREDIT_REQUEST,
        AdminApprovalItemDto.OwnerType.SALES,
        request.getId(),
        request.getPublicId(),
        reference,
        normalizeStatus(request.getStatus()),
        summary,
        requesterUserId,
        requesterEmail,
        CREDIT_REQUEST_APPROVAL_ACTION,
        "Approve permanent credit limit",
        CREDIT_REQUEST_APPROVE_ENDPOINT,
        CREDIT_REQUEST_REJECT_ENDPOINT,
        request.getCreatedAt());
  }

  private AdminApprovalItemDto toCreditOverrideApprovalItem(CreditLimitOverrideRequest request) {
    String reference = overrideReference(request);
    String dealerLabel =
        request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
            ? request.getDealer().getName()
            : "Unknown dealer";
    String summary =
        "Approve dispatch credit override request "
            + reference
            + " for "
            + dealerLabel
            + ": dispatch "
            + toAmountString(request.getDispatchAmount())
            + ", exposure "
            + toAmountString(request.getCurrentExposure())
            + ", limit "
            + toAmountString(request.getCreditLimit())
            + ", required headroom "
            + toAmountString(request.getRequiredHeadroom());
    if (StringUtils.hasText(request.getRequestedBy())) {
      summary = summary + " (requested by " + request.getRequestedBy().trim() + ")";
    }
    return approvalItem(
        AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST,
        overrideOwnerType(request),
        request.getId(),
        request.getPublicId(),
        reference,
        normalizeStatus(request.getStatus()),
        summary,
        CREDIT_OVERRIDE_APPROVAL_ACTION,
        "Approve dispatch credit override",
        CREDIT_OVERRIDE_APPROVE_ENDPOINT,
        CREDIT_OVERRIDE_REJECT_ENDPOINT,
        request.getCreatedAt());
  }

  private AdminApprovalItemDto toPayrollApprovalItem(PayrollRun run) {
    String reference =
        StringUtils.hasText(run.getRunNumber()) ? run.getRunNumber() : "PR-" + run.getId();
    String summary =
        "Approve payroll run "
            + reference
            + " ("
            + run.getRunType().name()
            + " "
            + run.getPeriodStart()
            + " - "
            + run.getPeriodEnd()
            + ")";
    return approvalItem(
        AdminApprovalItemDto.OriginType.PAYROLL_RUN,
        AdminApprovalItemDto.OwnerType.HR,
        run.getId(),
        run.getPublicId(),
        reference,
        normalizeStatus(run.getStatus() != null ? run.getStatus().name() : null),
        summary,
        PAYROLL_APPROVAL_ACTION,
        "Approve payroll run",
        PAYROLL_APPROVE_ENDPOINT,
        null,
        run.getCreatedAt());
  }

  private AdminApprovalItemDto toPeriodCloseApprovalItem(PeriodCloseRequest request) {
    String reference =
        request != null && request.getAccountingPeriod() != null
            ? request.getAccountingPeriod().getLabel()
            : "PERIOD-" + (request != null ? request.getId() : "UNKNOWN");
    String summary = "Approve accounting period close request for " + reference;
    if (request != null
        && request.getAccountingPeriod() != null
        && request.getAccountingPeriod().getStatus() != null) {
      summary =
          summary + " (current status: " + request.getAccountingPeriod().getStatus().name() + ")";
    }
    if (request != null && request.isForceRequested()) {
      summary = summary + " [force requested]";
    }
    if (request != null && StringUtils.hasText(request.getRequestedBy())) {
      summary = summary + " (requested by " + request.getRequestedBy().trim() + ")";
    }
    if (request != null && StringUtils.hasText(request.getRequestNote())) {
      summary = summary + " (note: " + request.getRequestNote().trim() + ")";
    }
    return approvalItem(
        AdminApprovalItemDto.OriginType.PERIOD_CLOSE_REQUEST,
        AdminApprovalItemDto.OwnerType.ACCOUNTING,
        request != null ? request.getId() : null,
        request != null ? request.getPublicId() : null,
        reference,
        normalizeStatus(
            request != null && request.getStatus() != null ? request.getStatus().name() : null),
        summary,
        PERIOD_CLOSE_APPROVAL_ACTION,
        "Approve accounting period close",
        PERIOD_CLOSE_APPROVE_ENDPOINT,
        PERIOD_CLOSE_REJECT_ENDPOINT,
        request != null ? request.getRequestedAt() : null);
  }

  private AdminApprovalItemDto toExportApprovalItem(
      ExportRequestDto request, boolean includeSensitiveDetails) {
    String reference = "EXP-" + request.id();
    String summary = "Approve export request " + reference + " for report " + request.reportType();
    String requesterEmail =
        includeSensitiveDetails && StringUtils.hasText(request.userEmail())
            ? request.userEmail()
            : null;
    String actionType = includeSensitiveDetails ? EXPORT_REQUEST_APPROVAL_ACTION : null;
    String actionLabel = includeSensitiveDetails ? "Approve data export" : null;
    String approveEndpoint = includeSensitiveDetails ? EXPORT_REQUEST_APPROVE_ENDPOINT : null;
    String rejectEndpoint = includeSensitiveDetails ? EXPORT_REQUEST_REJECT_ENDPOINT : null;
    if (requesterEmail != null) {
      summary = summary + " requested by " + requesterEmail;
    }
    return new AdminApprovalItemDto(
        AdminApprovalItemDto.OriginType.EXPORT_REQUEST,
        AdminApprovalItemDto.OwnerType.REPORTS,
        request.id(),
        null,
        reference,
        normalizeStatus(request.status() != null ? request.status().name() : null),
        summary,
        request.reportType(),
        includeSensitiveDetails ? request.parameters() : null,
        includeSensitiveDetails ? request.userId() : null,
        requesterEmail,
        actionType,
        actionLabel,
        approveEndpoint,
        rejectEndpoint,
        request.createdAt());
  }

  private boolean canViewSensitiveApprovalRequesterDetails() {
    org.springframework.security.core.Authentication authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (authentication == null || authentication.getAuthorities() == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .anyMatch(
            authority -> "ROLE_ADMIN".equals(authority) || "ROLE_SUPER_ADMIN".equals(authority));
  }

  private String normalizeStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return "UNKNOWN";
    }
    return status.trim().toUpperCase(Locale.ROOT);
  }

  private String overrideReference(CreditLimitOverrideRequest request) {
    PackagingSlip slip = request.getPackagingSlip();
    if (slip != null && StringUtils.hasText(slip.getSlipNumber())) {
      return slip.getSlipNumber();
    }
    if (request.getSalesOrder() != null
        && StringUtils.hasText(request.getSalesOrder().getOrderNumber())) {
      return request.getSalesOrder().getOrderNumber();
    }
    return "CLO-" + request.getId();
  }

  private AdminApprovalItemDto.OwnerType overrideOwnerType(CreditLimitOverrideRequest request) {
    if (request.getPackagingSlip() != null) {
      return AdminApprovalItemDto.OwnerType.FACTORY;
    }
    if (request.getSalesOrder() != null) {
      return AdminApprovalItemDto.OwnerType.SALES;
    }
    return AdminApprovalItemDto.OwnerType.SALES;
  }

  private String toAmountString(BigDecimal amount) {
    return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
  }

  private boolean isHrPayrollEnabled(Company company) {
    return moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL);
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
