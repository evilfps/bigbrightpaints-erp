package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.admin.dto.*;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminSettingsController {

    private final SystemSettingsService systemSettingsService;
    private final EmailService emailService;
    private final CompanyContextService companyContextService;
    private final CreditRequestRepository creditRequestRepository;
    private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
    private final PayrollRunRepository payrollRunRepository;

    public AdminSettingsController(SystemSettingsService systemSettingsService,
                                   EmailService emailService,
                                   CompanyContextService companyContextService,
                                   CreditRequestRepository creditRequestRepository,
                                   CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
                                   PayrollRunRepository payrollRunRepository) {
        this.systemSettingsService = systemSettingsService;
        this.emailService = emailService;
        this.companyContextService = companyContextService;
        this.creditRequestRepository = creditRequestRepository;
        this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
    }

    @GetMapping("/settings")
    public ApiResponse<SystemSettingsDto> getSettings() {
        return ApiResponse.success("Settings fetched", systemSettingsService.snapshot());
    }

    @PutMapping("/settings")
    public ApiResponse<SystemSettingsDto> updateSettings(@Valid @RequestBody SystemSettingsUpdateRequest request) {
        SystemSettingsDto dto = systemSettingsService.update(request);
        return ApiResponse.success("Settings updated", dto);
    }

    @PostMapping("/notify")
    public ApiResponse<String> notifyUser(@Valid @RequestBody AdminNotifyRequest request) {
        emailService.sendSimpleEmail(request.to(), request.subject(), request.body());
        return ApiResponse.success("Notification sent", "Email dispatched");
    }

    @GetMapping("/approvals")
    public ApiResponse<AdminApprovalsResponse> approvals() {
        Company company = companyContextService.requireCurrentCompany();
        List<AdminApprovalItemDto> creditRequestApprovals = creditRequestRepository
                .findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING")
                .stream()
                .map(this::toCreditRequestApprovalItem)
                .toList();

        List<AdminApprovalItemDto> creditOverrideApprovals = creditLimitOverrideRequestRepository
                .findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING")
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
                .map(pr -> approvalItem("PAYROLL_RUN", pr.getId(), pr.getPublicId(),
                        pr.getRunNumber(), pr.getStatus().name(),
                        pr.getRunType().name() + " " + pr.getPeriodStart() + " - " + pr.getPeriodEnd(),
                        pr.getCreatedAt()))
                .toList();

        AdminApprovalsResponse response = new AdminApprovalsResponse(creditApprovals, payrollApprovals);
        return ApiResponse.success("Approvals fetched", response);
    }

    private AdminApprovalItemDto approvalItem(String type, Long id, UUID publicId, String reference,
                                              String status, String summary, Instant createdAt) {
        return new AdminApprovalItemDto(type, id, publicId, reference, status, summary, createdAt);
    }

    private AdminApprovalItemDto toCreditRequestApprovalItem(CreditRequest request) {
        String dealerLabel = request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
                ? request.getDealer().getName()
                : "Unknown dealer";
        String summary = "Approve credit-limit increase for " + dealerLabel
                + " amount " + toAmountString(request.getAmountRequested());
        if (StringUtils.hasText(request.getReason())) {
            summary = summary + " (reason: " + request.getReason().trim() + ")";
        }
        return approvalItem(
                "CREDIT_REQUEST",
                request.getId(),
                request.getPublicId(),
                "CR-" + request.getId(),
                request.getStatus(),
                summary,
                request.getCreatedAt()
        );
    }

    private AdminApprovalItemDto toCreditOverrideApprovalItem(CreditLimitOverrideRequest request) {
        String dealerLabel = request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
                ? request.getDealer().getName()
                : "Unknown dealer";
        String summary = "Approve dispatch credit override for " + dealerLabel
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
                overrideReference(request),
                request.getStatus(),
                summary,
                request.getCreatedAt()
        );
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

    private String toAmountString(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }
}
