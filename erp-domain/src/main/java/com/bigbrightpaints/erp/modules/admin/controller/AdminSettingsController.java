package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.admin.dto.*;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSettingsController {
    private static final String CREDIT_REQUEST_APPROVAL_ACTION = "APPROVE_DEALER_CREDIT_REQUEST";
    private static final String CREDIT_OVERRIDE_APPROVAL_ACTION = "APPROVE_DISPATCH_CREDIT_OVERRIDE";
    private static final String PAYROLL_APPROVAL_ACTION = "APPROVE_PAYROLL_RUN";
    private static final String CREDIT_REQUEST_APPROVE_ENDPOINT = "/api/v1/sales/credit-requests/{id}/approve";
    private static final String CREDIT_REQUEST_REJECT_ENDPOINT = "/api/v1/sales/credit-requests/{id}/reject";
    private static final String CREDIT_OVERRIDE_APPROVE_ENDPOINT = "/api/v1/credit/override-requests/{id}/approve";
    private static final String CREDIT_OVERRIDE_REJECT_ENDPOINT = "/api/v1/credit/override-requests/{id}/reject";
    private static final String PAYROLL_APPROVE_ENDPOINT = "/api/v1/payroll/runs/{id}/approve";


    private final SystemSettingsService systemSettingsService;
    private final EmailService emailService;
    private final CompanyContextService companyContextService;
    private final TenantRuntimePolicyService tenantRuntimePolicyService;
    private final CreditRequestRepository creditRequestRepository;
    private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
    private final PayrollRunRepository payrollRunRepository;

    public AdminSettingsController(SystemSettingsService systemSettingsService,
                                   EmailService emailService,
                                   CompanyContextService companyContextService,
                                   TenantRuntimePolicyService tenantRuntimePolicyService,
                                   CreditRequestRepository creditRequestRepository,
                                   CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
                                   PayrollRunRepository payrollRunRepository) {
        this.systemSettingsService = systemSettingsService;
        this.emailService = emailService;
        this.companyContextService = companyContextService;
        this.tenantRuntimePolicyService = tenantRuntimePolicyService;
        this.creditRequestRepository = creditRequestRepository;
        this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<SystemSettingsDto> getSettings() {
        return ApiResponse.success("Settings fetched", systemSettingsService.snapshot());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<SystemSettingsDto> updateSettings(@Valid @RequestBody SystemSettingsUpdateRequest request) {
        SystemSettingsDto dto = systemSettingsService.update(request);
        return ApiResponse.success("Settings updated", dto);
    }

    @GetMapping("/tenant-runtime/metrics")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<TenantRuntimeMetricsDto> tenantRuntimeMetrics() {
        return ApiResponse.success("Tenant runtime metrics", tenantRuntimePolicyService.metrics());
    }

    @PutMapping("/tenant-runtime/policy")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ApiResponse<TenantRuntimeMetricsDto> updateTenantRuntimePolicy(
            @Valid @RequestBody TenantRuntimePolicyUpdateRequest request) {
        return ApiResponse.success("Tenant runtime policy updated", tenantRuntimePolicyService.updatePolicy(request));
    }

    @PostMapping("/notify")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ApiResponse<String> notifyUser(@Valid @RequestBody AdminNotifyRequest request) {
        emailService.sendSimpleEmail(request.to(), request.subject(), request.body());
        return ApiResponse.success("Notification sent", "Email dispatched");
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    @Transactional(readOnly = true)
    public ApiResponse<AdminApprovalsResponse> approvals() {
        Company company = companyContextService.requireCurrentCompany();
        List<AdminApprovalItemDto> creditRequestApprovals = creditRequestRepository
                .findPendingByCompanyOrderByCreatedAtDesc(company)
                .stream()
                .map(this::toCreditRequestApprovalItem)
                .toList();

        List<AdminApprovalItemDto> creditOverrideApprovals = creditLimitOverrideRequestRepository
                .findPendingByCompanyOrderByCreatedAtDesc(company)
                .stream()
                .map(this::toCreditOverrideApprovalItem)
                .toList();

        List<AdminApprovalItemDto> creditApprovals = Stream
                .concat(creditRequestApprovals.stream(), creditOverrideApprovals.stream())
                .sorted(Comparator.comparing(AdminApprovalItemDto::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<AdminApprovalItemDto> payrollApprovals = payrollRunRepository
                .findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED)
                .stream()
                .map(this::toPayrollApprovalItem)
                .toList();

        AdminApprovalsResponse response = new AdminApprovalsResponse(creditApprovals, payrollApprovals);
        return ApiResponse.success("Approvals fetched", response);
    }

    private AdminApprovalItemDto approvalItem(String type, Long id, UUID publicId, String reference,
                                              String status, String summary, String actionType,
                                              String actionLabel, String sourcePortal,
                                              String approveEndpoint, String rejectEndpoint,
                                              Instant createdAt) {
        return new AdminApprovalItemDto(
                type,
                id,
                publicId,
                reference,
                status,
                summary,
                actionType,
                actionLabel,
                sourcePortal,
                approveEndpoint,
                rejectEndpoint,
                createdAt
        );
    }

    private AdminApprovalItemDto toCreditRequestApprovalItem(CreditRequest request) {
        String reference = "CR-" + request.getId();
        String dealerLabel = request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
                ? request.getDealer().getName()
                : "Unknown dealer";
        String summary = "Approve dealer credit-limit increase request " + reference + " for " + dealerLabel
                + " amount " + toAmountString(request.getAmountRequested());
        if (StringUtils.hasText(request.getReason())) {
            summary = summary + " (reason: " + request.getReason().trim() + ")";
        }
        return approvalItem(
                "CREDIT_REQUEST",
                request.getId(),
                request.getPublicId(),
                reference,
                normalizeStatus(request.getStatus()),
                summary,
                CREDIT_REQUEST_APPROVAL_ACTION,
                "Approve dealer credit-limit increase",
                "DEALER_PORTAL",
                CREDIT_REQUEST_APPROVE_ENDPOINT,
                CREDIT_REQUEST_REJECT_ENDPOINT,
                request.getCreatedAt()
        );
    }

    private AdminApprovalItemDto toCreditOverrideApprovalItem(CreditLimitOverrideRequest request) {
        String reference = overrideReference(request);
        String dealerLabel = request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
                ? request.getDealer().getName()
                : "Unknown dealer";
        String summary = "Approve dispatch credit override request " + reference + " for " + dealerLabel
                + ": dispatch " + toAmountString(request.getDispatchAmount())
                + ", exposure " + toAmountString(request.getCurrentExposure())
                + ", limit " + toAmountString(request.getCreditLimit())
                + ", required headroom " + toAmountString(request.getRequiredHeadroom());
        if (StringUtils.hasText(request.getRequestedBy())) {
            summary = summary + " (requested by " + request.getRequestedBy().trim() + ")";
        }
        return approvalItem(
                "CREDIT_LIMIT_OVERRIDE_REQUEST",
                request.getId(),
                request.getPublicId(),
                reference,
                normalizeStatus(request.getStatus()),
                summary,
                CREDIT_OVERRIDE_APPROVAL_ACTION,
                "Approve dispatch credit override",
                overrideSourcePortal(request),
                CREDIT_OVERRIDE_APPROVE_ENDPOINT,
                CREDIT_OVERRIDE_REJECT_ENDPOINT,
                request.getCreatedAt()
        );
    }

    private AdminApprovalItemDto toPayrollApprovalItem(PayrollRun run) {
        String reference = StringUtils.hasText(run.getRunNumber())
                ? run.getRunNumber()
                : "PR-" + run.getId();
        String summary = "Approve payroll run " + reference
                + " (" + run.getRunType().name() + " " + run.getPeriodStart() + " - " + run.getPeriodEnd() + ")";
        return approvalItem(
                "PAYROLL_RUN",
                run.getId(),
                run.getPublicId(),
                reference,
                normalizeStatus(run.getStatus() != null ? run.getStatus().name() : null),
                summary,
                PAYROLL_APPROVAL_ACTION,
                "Approve payroll run",
                "HR_PORTAL",
                PAYROLL_APPROVE_ENDPOINT,
                null,
                run.getCreatedAt()
        );
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
        if (request.getSalesOrder() != null && StringUtils.hasText(request.getSalesOrder().getOrderNumber())) {
            return request.getSalesOrder().getOrderNumber();
        }
        return "CLO-" + request.getId();
    }

    private String overrideSourcePortal(CreditLimitOverrideRequest request) {
        if (request.getPackagingSlip() != null) {
            return "FACTORY_PORTAL";
        }
        if (request.getSalesOrder() != null) {
            return "SALES_PORTAL";
        }
        return "SALES_PORTAL";
    }

    private String toAmountString(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
