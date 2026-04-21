package com.bigbrightpaints.erp.modules.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalDecisionRequest;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalInboxResponse;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalItemDto;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.ModuleGatingService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;

@Service
public class AdminApprovalService {

  private static final String GENERIC_DECISION_ENDPOINT_TEMPLATE =
      "/api/v1/admin/approvals/%s/%d/decisions";

  private final CompanyContextService companyContextService;
  private final ModuleGatingService moduleGatingService;
  private final ExportApprovalService exportApprovalService;
  private final CreditRequestRepository creditRequestRepository;
  private final CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
  private final PayrollRunRepository payrollRunRepository;
  private final PeriodCloseRequestRepository periodCloseRequestRepository;
  private final CreditLimitRequestService creditLimitRequestService;
  private final CreditLimitOverrideService creditLimitOverrideService;
  private final PayrollService payrollService;
  private final AccountingPeriodService accountingPeriodService;

  public AdminApprovalService(
      CompanyContextService companyContextService,
      ModuleGatingService moduleGatingService,
      ExportApprovalService exportApprovalService,
      CreditRequestRepository creditRequestRepository,
      CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository,
      PayrollRunRepository payrollRunRepository,
      PeriodCloseRequestRepository periodCloseRequestRepository,
      CreditLimitRequestService creditLimitRequestService,
      CreditLimitOverrideService creditLimitOverrideService,
      PayrollService payrollService,
      AccountingPeriodService accountingPeriodService) {
    this.companyContextService = companyContextService;
    this.moduleGatingService = moduleGatingService;
    this.exportApprovalService = exportApprovalService;
    this.creditRequestRepository = creditRequestRepository;
    this.creditLimitOverrideRequestRepository = creditLimitOverrideRequestRepository;
    this.payrollRunRepository = payrollRunRepository;
    this.periodCloseRequestRepository = periodCloseRequestRepository;
    this.creditLimitRequestService = creditLimitRequestService;
    this.creditLimitOverrideService = creditLimitOverrideService;
    this.payrollService = payrollService;
    this.accountingPeriodService = accountingPeriodService;
  }

  @Transactional(readOnly = true)
  public AdminApprovalInboxResponse getInbox() {
    Company company = companyContextService.requireCurrentCompany();

    List<AdminApprovalItemDto> creditRequests =
        creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company).stream()
            .map(this::toCreditRequestItem)
            .toList();

    List<AdminApprovalItemDto> creditOverrides =
        creditLimitOverrideRequestRepository
            .findPendingByCompanyOrderByCreatedAtDesc(company)
            .stream()
            .map(this::toCreditOverrideItem)
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
        periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company).stream()
            .map(this::toPeriodCloseApprovalItem)
            .toList();

    List<AdminApprovalItemDto> exportApprovals =
        exportApprovalService.listPending().stream().map(this::toExportApprovalItem).toList();

    List<AdminApprovalItemDto> items =
        Stream.of(
                creditRequests,
                creditOverrides,
                payrollApprovals,
                periodCloseApprovals,
                exportApprovals)
            .flatMap(List::stream)
            .sorted(
                Comparator.comparing(
                    AdminApprovalItemDto::createdAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

    return new AdminApprovalInboxResponse(items, items.size());
  }

  @Transactional(readOnly = true)
  public PendingCounts getPendingCounts() {
    Company company = companyContextService.requireCurrentCompany();
    long creditPending = creditRequestRepository.countPendingByCompany(company);
    long creditOverridePending =
        creditLimitOverrideRequestRepository.countPendingByCompany(company);
    long payrollPending =
        isHrPayrollEnabled(company)
            ? payrollRunRepository.countByCompanyAndStatus(
                company, PayrollRun.PayrollStatus.CALCULATED)
            : 0L;
    long periodClosePending =
        periodCloseRequestRepository.countByCompanyAndStatus(
            company, PeriodCloseRequestStatus.PENDING);
    long exportPending = exportApprovalService.countPending();
    return new PendingCounts(
        creditPending, creditOverridePending, payrollPending, periodClosePending, exportPending);
  }

  @Transactional
  public AdminApprovalItemDto decide(
      String originTypeRaw, Long id, AdminApprovalDecisionRequest request) {
    ValidationUtils.requireNotBlank(originTypeRaw, "originType");
    if (id == null || id <= 0) {
      throw ValidationUtils.invalidInput("Approval id must be positive");
    }
    if (request == null || request.decision() == null) {
      throw ValidationUtils.invalidInput("decision is required");
    }

    AdminApprovalItemDto.OriginType originType = parseOriginType(originTypeRaw);
    boolean approve = request.decision() == AdminApprovalDecisionRequest.Decision.APPROVE;
    Company company = companyContextService.requireCurrentCompany();

    return switch (originType) {
      case EXPORT_REQUEST -> toExportApprovalItem(decideExport(id, approve, request.reason()));
      case CREDIT_REQUEST ->
          toCreditRequestItem(decideCreditRequest(company, id, approve, request.reason()));
      case CREDIT_LIMIT_OVERRIDE_REQUEST ->
          toCreditOverrideItem(decideCreditOverride(company, id, approve, request));
      case PAYROLL_RUN -> toPayrollApprovalItem(decidePayroll(id, approve));
      case PERIOD_CLOSE_REQUEST -> decidePeriodCloseItem(id, approve, request.reason());
    };
  }

  private ExportRequestDto decideExport(Long id, boolean approve, String reason) {
    return approve ? exportApprovalService.approve(id) : exportApprovalService.reject(id, reason);
  }

  private CreditRequest decideCreditRequest(
      Company company, Long id, boolean approve, String reason) {
    String decisionReason = requireReason(reason, approve ? "approve" : "reject");
    if (approve) {
      creditLimitRequestService.approveRequest(id, decisionReason);
    } else {
      creditLimitRequestService.rejectRequest(id, decisionReason);
    }
    return creditRequestRepository
        .findByCompanyAndId(company, id)
        .orElseThrow(() -> ValidationUtils.invalidInput("Credit request not found: " + id));
  }

  private CreditLimitOverrideRequest decideCreditOverride(
      Company company, Long id, boolean approve, AdminApprovalDecisionRequest request) {
    String decisionReason = requireReason(request.reason(), approve ? "approve" : "reject");
    CreditLimitOverrideDecisionRequest delegateRequest =
        new CreditLimitOverrideDecisionRequest(decisionReason, request.expiresAt());
    String actor =
        com.bigbrightpaints.erp.core.security.SecurityActorResolver.resolveActorOrUnknown();
    if (approve) {
      creditLimitOverrideService.approveRequest(id, delegateRequest, actor);
    } else {
      creditLimitOverrideService.rejectRequest(id, delegateRequest, actor);
    }
    return creditLimitOverrideRequestRepository
        .findByCompanyAndId(company, id)
        .orElseThrow(
            () -> ValidationUtils.invalidInput("Credit override request not found: " + id));
  }

  private PayrollService.PayrollRunDto decidePayroll(Long id, boolean approve) {
    if (!approve) {
      throw ValidationUtils.invalidInput(
          "Payroll rejection is not supported; use the HR correction workflow before re-approval");
    }
    return payrollService.approvePayroll(id);
  }

  private AdminApprovalItemDto decidePeriodCloseItem(Long id, boolean approve, String reason) {
    String decisionReason = requireReason(reason, approve ? "approve" : "reject");
    PeriodCloseRequestActionRequest action =
        new PeriodCloseRequestActionRequest(decisionReason, null);
    if (approve) {
      accountingPeriodService.approvePeriodClose(id, action);
    } else {
      accountingPeriodService.rejectPeriodClose(id, action);
    }
    String reference = "PERIOD-" + id;
    String status = approve ? "APPROVED" : "REJECTED";
    String summary =
        approve
            ? "Accounting period close approved for " + reference
            : "Accounting period close rejected for " + reference;
    return decisionItem(
        AdminApprovalItemDto.OriginType.PERIOD_CLOSE_REQUEST,
        AdminApprovalItemDto.OwnerType.ACCOUNTING,
        id,
        null,
        reference,
        status,
        summary,
        null,
        null,
        Instant.now());
  }

  private AdminApprovalItemDto toCreditRequestItem(CreditRequest request) {
    String reference = "CLR-" + request.getId();
    String dealerLabel =
        request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
            ? request.getDealer().getName()
            : "Unknown dealer";
    String summary =
        "Review credit-limit request "
            + reference
            + " for "
            + dealerLabel
            + " amount "
            + toAmountString(request.getAmountRequested());
    if (StringUtils.hasText(request.getReason())) {
      summary = summary + " (reason: " + request.getReason().trim() + ")";
    }
    return decisionItem(
        AdminApprovalItemDto.OriginType.CREDIT_REQUEST,
        AdminApprovalItemDto.OwnerType.SALES,
        request.getId(),
        request.getPublicId(),
        reference,
        normalizeStatus(request.getStatus()),
        summary,
        request.getRequesterUserId(),
        request.getRequesterEmail(),
        request.getCreatedAt());
  }

  private AdminApprovalItemDto toCreditOverrideItem(CreditLimitOverrideRequest request) {
    String reference =
        request.getPackagingSlip() != null
                && StringUtils.hasText(request.getPackagingSlip().getSlipNumber())
            ? request.getPackagingSlip().getSlipNumber()
            : request.getSalesOrder() != null
                    && StringUtils.hasText(request.getSalesOrder().getOrderNumber())
                ? request.getSalesOrder().getOrderNumber()
                : "CLO-" + request.getId();
    String dealerLabel =
        request.getDealer() != null && StringUtils.hasText(request.getDealer().getName())
            ? request.getDealer().getName()
            : "Unknown dealer";
    String summary =
        "Review credit override "
            + reference
            + " for "
            + dealerLabel
            + ": dispatch "
            + toAmountString(request.getDispatchAmount())
            + ", exposure "
            + toAmountString(request.getCurrentExposure())
            + ", limit "
            + toAmountString(request.getCreditLimit());
    if (StringUtils.hasText(request.getRequestedBy())) {
      summary = summary + " (requested by " + request.getRequestedBy().trim() + ")";
    }
    return decisionItem(
        AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST,
        request.getPackagingSlip() != null
            ? AdminApprovalItemDto.OwnerType.FACTORY
            : AdminApprovalItemDto.OwnerType.SALES,
        request.getId(),
        request.getPublicId(),
        reference,
        normalizeStatus(request.getStatus()),
        summary,
        null,
        null,
        request.getCreatedAt());
  }

  private AdminApprovalItemDto toPayrollApprovalItem(PayrollRun run) {
    String reference =
        StringUtils.hasText(run.getRunNumber()) ? run.getRunNumber() : "PR-" + run.getId();
    String summary =
        "Review payroll run "
            + reference
            + " ("
            + run.getRunType().name()
            + " "
            + run.getPeriodStart()
            + " - "
            + run.getPeriodEnd()
            + ")";
    return decisionItem(
        AdminApprovalItemDto.OriginType.PAYROLL_RUN,
        AdminApprovalItemDto.OwnerType.HR,
        run.getId(),
        run.getPublicId(),
        reference,
        normalizeStatus(run.getStatus() != null ? run.getStatus().name() : null),
        summary,
        null,
        null,
        run.getCreatedAt());
  }

  private AdminApprovalItemDto toPayrollApprovalItem(PayrollService.PayrollRunDto run) {
    String reference = StringUtils.hasText(run.runNumber()) ? run.runNumber() : "PR-" + run.id();
    String summary =
        "Payroll run " + reference + " decision applied; status=" + normalizeStatus(run.status());
    return decisionItem(
        AdminApprovalItemDto.OriginType.PAYROLL_RUN,
        AdminApprovalItemDto.OwnerType.HR,
        run.id(),
        run.publicId(),
        reference,
        normalizeStatus(run.status()),
        summary,
        null,
        null,
        run.createdAt());
  }

  private AdminApprovalItemDto toPeriodCloseApprovalItem(PeriodCloseRequest request) {
    String reference =
        request.getAccountingPeriod() != null
            ? request.getAccountingPeriod().getLabel()
            : "PERIOD-" + request.getId();
    String summary = "Review accounting period close request for " + reference;
    if (request.getStatus() != null) {
      summary = summary + " (status: " + request.getStatus().name() + ")";
    }
    if (StringUtils.hasText(request.getRequestedBy())) {
      summary = summary + " (requested by " + request.getRequestedBy().trim() + ")";
    }
    if (request.isForceRequested()) {
      summary = summary + " (force requested)";
    }
    if (StringUtils.hasText(request.getRequestNote())) {
      summary = summary + " (request note: " + request.getRequestNote().trim() + ")";
    }
    return decisionItem(
        AdminApprovalItemDto.OriginType.PERIOD_CLOSE_REQUEST,
        AdminApprovalItemDto.OwnerType.ACCOUNTING,
        request.getAccountingPeriod() != null
            ? request.getAccountingPeriod().getId()
            : request.getId(),
        request.getPublicId(),
        reference,
        normalizeStatus(request.getStatus() != null ? request.getStatus().name() : null),
        summary,
        null,
        null,
        request.getRequestedAt());
  }

  private AdminApprovalItemDto toExportApprovalItem(ExportRequestDto request) {
    String reference = "EXP-" + request.id();
    String summary = "Review export request " + reference + " for report " + request.reportType();
    if (StringUtils.hasText(request.userEmail())) {
      summary = summary + " requested by " + request.userEmail();
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
        request.parameters(),
        request.userId(),
        request.userEmail(),
        "APPROVAL_DECISION",
        "Review approval",
        decisionEndpoint(AdminApprovalItemDto.OriginType.EXPORT_REQUEST, request.id()),
        decisionEndpoint(AdminApprovalItemDto.OriginType.EXPORT_REQUEST, request.id()),
        request.createdAt());
  }

  private AdminApprovalItemDto decisionItem(
      AdminApprovalItemDto.OriginType originType,
      AdminApprovalItemDto.OwnerType ownerType,
      Long id,
      UUID publicId,
      String reference,
      String status,
      String summary,
      Long requesterUserId,
      String requesterEmail,
      Instant createdAt) {
    String endpoint = decisionEndpoint(originType, id);
    String rejectEndpoint = supportsRejectAction(originType) ? endpoint : null;
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
        "APPROVAL_DECISION",
        "Review approval",
        endpoint,
        rejectEndpoint,
        createdAt);
  }

  private boolean supportsRejectAction(AdminApprovalItemDto.OriginType originType) {
    return originType != AdminApprovalItemDto.OriginType.PAYROLL_RUN;
  }

  private String decisionEndpoint(AdminApprovalItemDto.OriginType originType, Long id) {
    if (originType == null || id == null) {
      return null;
    }
    return String.format(Locale.ROOT, GENERIC_DECISION_ENDPOINT_TEMPLATE, originType.name(), id);
  }

  private AdminApprovalItemDto.OriginType parseOriginType(String raw) {
    try {
      return AdminApprovalItemDto.OriginType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw ValidationUtils.invalidInput("Unsupported originType: " + raw);
    }
  }

  private String requireReason(String reason, String action) {
    if (!StringUtils.hasText(reason)) {
      throw ValidationUtils.invalidInput("reason is required to " + action + " this approval");
    }
    return reason.trim();
  }

  private String normalizeStatus(String status) {
    if (status == null) {
      return "UNKNOWN";
    }
    String trimmedStatus = status.trim();
    if (trimmedStatus.isEmpty()) {
      return "UNKNOWN";
    }
    return trimmedStatus.toUpperCase(Locale.ROOT);
  }

  private String toAmountString(BigDecimal amount) {
    return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
  }

  private boolean isHrPayrollEnabled(Company company) {
    return moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL);
  }

  public record PendingCounts(
      long creditPending,
      long creditOverridePending,
      long payrollPending,
      long periodClosePending,
      long exportPending) {
    public long totalPending() {
      return creditPending
          + creditOverridePending
          + payrollPending
          + periodClosePending
          + exportPending;
    }
  }
}
