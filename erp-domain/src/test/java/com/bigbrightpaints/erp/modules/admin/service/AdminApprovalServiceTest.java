package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
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
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;

@ExtendWith(MockitoExtension.class)
class AdminApprovalServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ModuleGatingService moduleGatingService;
  @Mock private ExportApprovalService exportApprovalService;
  @Mock private CreditRequestRepository creditRequestRepository;
  @Mock private CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
  @Mock private PayrollRunRepository payrollRunRepository;
  @Mock private PeriodCloseRequestRepository periodCloseRequestRepository;
  @Mock private CreditLimitRequestService creditLimitRequestService;
  @Mock private CreditLimitOverrideService creditLimitOverrideService;
  @Mock private PayrollService payrollService;
  @Mock private AccountingPeriodService accountingPeriodService;

  private AdminApprovalService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new AdminApprovalService(
            companyContextService,
            moduleGatingService,
            exportApprovalService,
            creditRequestRepository,
            creditLimitOverrideRequestRepository,
            payrollRunRepository,
            periodCloseRequestRepository,
            creditLimitRequestService,
            creditLimitOverrideService,
            payrollService,
            accountingPeriodService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("TEST");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient()
        .when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
        .thenReturn(List.of());
    lenient()
        .when(
            creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
        .thenReturn(List.of());
    lenient()
        .when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
        .thenReturn(List.of());
    lenient().when(exportApprovalService.listPending()).thenReturn(List.of());
    lenient()
        .when(moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL))
        .thenReturn(false);
    lenient().when(creditRequestRepository.countPendingByCompany(company)).thenReturn(0L);
    lenient()
        .when(creditLimitOverrideRequestRepository.countPendingByCompany(company))
        .thenReturn(0L);
    lenient()
        .when(
            periodCloseRequestRepository.countByCompanyAndStatus(
                company, PeriodCloseRequestStatus.PENDING))
        .thenReturn(0L);
    lenient().when(exportApprovalService.countPending()).thenReturn(0L);
    lenient()
        .when(
            payrollRunRepository.countByCompanyAndStatus(
                company, PayrollRun.PayrollStatus.CALCULATED))
        .thenReturn(0L);
  }

  @Test
  void pendingCounts_usesRepositoryCountsWithPayrollGateEnabled() {
    when(moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL)).thenReturn(true);
    when(creditRequestRepository.countPendingByCompany(company)).thenReturn(3L);
    when(creditLimitOverrideRequestRepository.countPendingByCompany(company)).thenReturn(4L);
    when(payrollRunRepository.countByCompanyAndStatus(company, PayrollRun.PayrollStatus.CALCULATED))
        .thenReturn(2L);
    when(periodCloseRequestRepository.countByCompanyAndStatus(
            company, PeriodCloseRequestStatus.PENDING))
        .thenReturn(5L);
    when(exportApprovalService.countPending()).thenReturn(6L);

    AdminApprovalService.PendingCounts counts = service.getPendingCounts();

    assertThat(counts.creditPending()).isEqualTo(3L);
    assertThat(counts.creditOverridePending()).isEqualTo(4L);
    assertThat(counts.payrollPending()).isEqualTo(2L);
    assertThat(counts.periodClosePending()).isEqualTo(5L);
    assertThat(counts.exportPending()).isEqualTo(6L);
    assertThat(counts.totalPending()).isEqualTo(20L);
  }

  @Test
  void pendingCounts_skipsPayrollWhenModuleDisabled() {
    when(moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL)).thenReturn(false);
    when(creditRequestRepository.countPendingByCompany(company)).thenReturn(1L);
    when(creditLimitOverrideRequestRepository.countPendingByCompany(company)).thenReturn(1L);
    when(periodCloseRequestRepository.countByCompanyAndStatus(
            company, PeriodCloseRequestStatus.PENDING))
        .thenReturn(1L);
    when(exportApprovalService.countPending()).thenReturn(1L);

    AdminApprovalService.PendingCounts counts = service.getPendingCounts();

    verify(payrollRunRepository, never())
        .countByCompanyAndStatus(company, PayrollRun.PayrollStatus.CALCULATED);
    assertThat(counts.payrollPending()).isEqualTo(0L);
    assertThat(counts.totalPending()).isEqualTo(4L);
  }

  @Test
  void decide_periodCloseApproval_preservesWorkflowForceResolution() {
    AdminApprovalDecisionRequest request =
        new AdminApprovalDecisionRequest(
            AdminApprovalDecisionRequest.Decision.APPROVE,
            "Close with workflow force policy",
            null);

    service.decide("PERIOD_CLOSE_REQUEST", 77L, request);

    ArgumentCaptor<PeriodCloseRequestActionRequest> requestCaptor =
        ArgumentCaptor.forClass(PeriodCloseRequestActionRequest.class);
    verify(accountingPeriodService).approvePeriodClose(eq(77L), requestCaptor.capture());
    assertThat(requestCaptor.getValue().note()).isEqualTo("Close with workflow force policy");
    assertThat(requestCaptor.getValue().force()).isNull();
  }

  @Test
  void decide_periodClose_requiresReason_forApproveAndReject() {
    AdminApprovalDecisionRequest approveWithoutReason =
        new AdminApprovalDecisionRequest(AdminApprovalDecisionRequest.Decision.APPROVE, " ", null);
    AdminApprovalDecisionRequest rejectWithoutReason =
        new AdminApprovalDecisionRequest(AdminApprovalDecisionRequest.Decision.REJECT, null, null);

    assertThatThrownBy(() -> service.decide("PERIOD_CLOSE_REQUEST", 78L, approveWithoutReason))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reason is required to approve this approval");
    assertThatThrownBy(() -> service.decide("PERIOD_CLOSE_REQUEST", 79L, rejectWithoutReason))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reason is required to reject this approval");
    verify(accountingPeriodService, never())
        .approvePeriodClose(any(Long.class), any(PeriodCloseRequestActionRequest.class));
    verify(accountingPeriodService, never())
        .rejectPeriodClose(any(Long.class), any(PeriodCloseRequestActionRequest.class));
  }

  @Test
  void inbox_periodCloseItems_includeForceRequestedAndRequestNoteContext() {
    PeriodCloseRequest request = new PeriodCloseRequest();
    ReflectionTestUtils.setField(request, "id", 77L);
    request.setRequestedBy("accounting-maker@bbp.com");
    request.setForceRequested(true);
    request.setRequestNote("Emergency close requested after reconciliation.");
    request.setRequestedAt(Instant.parse("2026-04-15T08:40:00Z"));

    when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
        .thenReturn(List.of(request));

    AdminApprovalInboxResponse inbox = service.getInbox();

    assertThat(inbox.items()).hasSize(1);
    AdminApprovalItemDto item = inbox.items().getFirst();
    assertThat(item.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PERIOD_CLOSE_REQUEST);
    assertThat(item.summary()).contains("force requested");
    assertThat(item.summary())
        .contains("request note: Emergency close requested after reconciliation.");
  }

  @Test
  void inbox_payrollItems_areApproveOnly() {
    PayrollRun run = new PayrollRun();
    ReflectionTestUtils.setField(run, "id", 51L);
    run.setRunNumber("PR-51");
    run.setRunType(PayrollRun.RunType.MONTHLY);
    run.setPeriodStart(LocalDate.of(2026, 4, 1));
    run.setPeriodEnd(LocalDate.of(2026, 4, 30));
    run.setStatus(PayrollRun.PayrollStatus.CALCULATED);
    ReflectionTestUtils.setField(run, "createdAt", Instant.parse("2026-04-15T08:30:00Z"));

    when(moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL)).thenReturn(true);
    when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(
            company, PayrollRun.PayrollStatus.CALCULATED))
        .thenReturn(List.of(run));

    AdminApprovalInboxResponse inbox = service.getInbox();

    assertThat(inbox.items()).hasSize(1);
    AdminApprovalItemDto item = inbox.items().getFirst();
    assertThat(item.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PAYROLL_RUN);
    assertThat(item.approveEndpoint())
        .isEqualTo("/api/v1/admin/approvals/PAYROLL_RUN/51/decisions");
    assertThat(item.rejectEndpoint()).isNull();
  }

  @Test
  void decide_payrollApproval_allowsMissingReasonAndNormalizesBlankStatus() {
    when(payrollService.approvePayroll(63L)).thenReturn(payrollRunDto(63L, "   "));

    AdminApprovalItemDto item =
        service.decide(
            "PAYROLL_RUN",
            63L,
            new AdminApprovalDecisionRequest(
                AdminApprovalDecisionRequest.Decision.APPROVE, null, null));

    assertThat(item.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PAYROLL_RUN);
    assertThat(item.status()).isEqualTo("UNKNOWN");
    assertThat(item.summary()).contains("status=UNKNOWN");
    verify(payrollService).approvePayroll(63L);
  }

  @Test
  void decide_payrollReject_usesHrCorrectionGuardrail() {
    assertThatThrownBy(
            () ->
                service.decide(
                    "PAYROLL_RUN",
                    64L,
                    new AdminApprovalDecisionRequest(
                        AdminApprovalDecisionRequest.Decision.REJECT, "not supported", null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Payroll rejection is not supported");

    verify(payrollService, never()).approvePayroll(any(Long.class));
  }

  @Test
  void normalizeStatus_returnsUnknownForNullAndBlankAndUppercasesText() {
    assertThat(normalizeStatus(null)).isEqualTo("UNKNOWN");
    assertThat(normalizeStatus("   ")).isEqualTo("UNKNOWN");
    assertThat(normalizeStatus(" approved ")).isEqualTo("APPROVED");
  }

  @Test
  void decide_creditOverride_requiresReason_forApproveAndReject() {
    AdminApprovalDecisionRequest approveWithoutReason =
        new AdminApprovalDecisionRequest(
            AdminApprovalDecisionRequest.Decision.APPROVE,
            "   ",
            Instant.parse("2026-04-20T00:00:00Z"));
    AdminApprovalDecisionRequest rejectWithoutReason =
        new AdminApprovalDecisionRequest(
            AdminApprovalDecisionRequest.Decision.REJECT,
            null,
            Instant.parse("2026-04-20T00:00:00Z"));

    assertThatThrownBy(
            () -> service.decide("CREDIT_LIMIT_OVERRIDE_REQUEST", 88L, approveWithoutReason))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reason is required to approve this approval");
    assertThatThrownBy(
            () -> service.decide("CREDIT_LIMIT_OVERRIDE_REQUEST", 89L, rejectWithoutReason))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reason is required to reject this approval");
    verify(creditLimitOverrideService, never())
        .approveRequest(
            any(Long.class), any(CreditLimitOverrideDecisionRequest.class), any(String.class));
    verify(creditLimitOverrideService, never())
        .rejectRequest(
            any(Long.class), any(CreditLimitOverrideDecisionRequest.class), any(String.class));
  }

  @Test
  void decide_creditOverride_passesTrimmedReasonToDelegate() {
    CreditLimitOverrideRequestDto approved =
        new CreditLimitOverrideRequestDto(
            90L,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "APPROVED",
            "Reviewed and approved",
            "maker",
            "reviewer",
            Instant.parse("2026-04-15T08:35:00Z"),
            Instant.parse("2026-04-20T00:00:00Z"),
            Instant.parse("2026-04-15T08:30:00Z"));
    when(creditLimitOverrideService.approveRequest(
            any(Long.class), any(CreditLimitOverrideDecisionRequest.class), any(String.class)))
        .thenReturn(approved);
    when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 90L))
        .thenReturn(Optional.of(buildCreditLimitOverrideRequest(90L, "APPROVED")));

    service.decide(
        "CREDIT_LIMIT_OVERRIDE_REQUEST",
        90L,
        new AdminApprovalDecisionRequest(
            AdminApprovalDecisionRequest.Decision.APPROVE,
            "  Reviewed and approved  ",
            Instant.parse("2026-04-20T00:00:00Z")));

    ArgumentCaptor<CreditLimitOverrideDecisionRequest> requestCaptor =
        ArgumentCaptor.forClass(CreditLimitOverrideDecisionRequest.class);
    verify(creditLimitOverrideService)
        .approveRequest(eq(90L), requestCaptor.capture(), any(String.class));
    assertThat(requestCaptor.getValue().reason()).isEqualTo("Reviewed and approved");
    assertThat(requestCaptor.getValue().expiresAt())
        .isEqualTo(Instant.parse("2026-04-20T00:00:00Z"));
  }

  @Test
  void decide_creditRequest_referenceRemainsStableWithInbox() {
    CreditRequest pending = buildCreditRequest(42L, "PENDING");
    CreditRequest approved = buildCreditRequest(42L, "APPROVED");
    when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
        .thenReturn(List.of(pending));
    when(creditLimitRequestService.approveRequest(eq(42L), eq("approved")))
        .thenReturn(
            new CreditLimitRequestDto(
                42L,
                null,
                "Dealer A",
                BigDecimal.TEN,
                "APPROVED",
                "approved",
                Instant.parse("2026-04-15T08:30:00Z")));
    when(creditRequestRepository.findByCompanyAndId(company, 42L))
        .thenReturn(Optional.of(approved));

    AdminApprovalInboxResponse inbox = service.getInbox();
    AdminApprovalItemDto decided =
        service.decide(
            "CREDIT_REQUEST",
            42L,
            new AdminApprovalDecisionRequest(
                AdminApprovalDecisionRequest.Decision.APPROVE, "approved", null));

    assertThat(inbox.items()).hasSize(1);
    assertThat(decided.reference()).isEqualTo(inbox.items().getFirst().reference());
    assertThat(decided.reference()).isEqualTo("CLR-42");
  }

  @Test
  void decide_exportKeepsApproveAndRejectEndpoints() {
    ExportRequestDto export =
        new ExportRequestDto(
            14L,
            6L,
            "reporter@bbp.com",
            "SALES_SUMMARY",
            "period=7",
            com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus.PENDING,
            null,
            Instant.parse("2026-04-15T08:00:00Z"),
            null,
            null);
    when(exportApprovalService.approve(14L)).thenReturn(export);

    AdminApprovalItemDto item =
        service.decide(
            "EXPORT_REQUEST",
            14L,
            new AdminApprovalDecisionRequest(
                AdminApprovalDecisionRequest.Decision.APPROVE, null, null));

    assertThat(item.approveEndpoint())
        .isEqualTo("/api/v1/admin/approvals/EXPORT_REQUEST/14/decisions");
    assertThat(item.rejectEndpoint())
        .isEqualTo("/api/v1/admin/approvals/EXPORT_REQUEST/14/decisions");
  }

  private CreditRequest buildCreditRequest(Long id, String status) {
    CreditRequest request = new CreditRequest();
    ReflectionTestUtils.setField(request, "id", id);
    ReflectionTestUtils.setField(request, "publicId", java.util.UUID.randomUUID());
    ReflectionTestUtils.setField(request, "createdAt", Instant.parse("2026-04-15T08:30:00Z"));
    request.setStatus(status);
    request.setAmountRequested(BigDecimal.TEN);
    request.setReason("approved");
    request.setRequesterEmail("dealer.requester@bbp.com");
    request.setRequesterUserId(101L);
    return request;
  }

  private PayrollService.PayrollRunDto payrollRunDto(Long id, String status) {
    return new PayrollService.PayrollRunDto(
        id,
        java.util.UUID.randomUUID(),
        "PR-" + id,
        "MONTHLY",
        LocalDate.of(2026, 4, 1),
        LocalDate.of(2026, 4, 30),
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "admin@bbp.com",
        Instant.parse("2026-04-15T08:30:00Z"),
        null,
        null,
        null,
        null,
        null);
  }

  private String normalizeStatus(String status) {
    return ReflectionTestUtils.invokeMethod(service, "normalizeStatus", status);
  }

  private CreditLimitOverrideRequest buildCreditLimitOverrideRequest(Long id, String status) {
    CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
    ReflectionTestUtils.setField(request, "id", id);
    ReflectionTestUtils.setField(request, "publicId", java.util.UUID.randomUUID());
    ReflectionTestUtils.setField(request, "createdAt", Instant.parse("2026-04-15T08:30:00Z"));
    request.setStatus(status);
    request.setDispatchAmount(BigDecimal.ONE);
    request.setCurrentExposure(BigDecimal.ONE);
    request.setCreditLimit(BigDecimal.ONE);
    return request;
  }
}
