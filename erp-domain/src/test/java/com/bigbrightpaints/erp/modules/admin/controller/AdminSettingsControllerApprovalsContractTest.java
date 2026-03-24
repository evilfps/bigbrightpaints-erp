package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalItemDto;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalsResponse;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDto;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
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
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("critical")
class AdminSettingsControllerApprovalsContractTest {

    @Test
    void approvals_isReadOnlyTransactional() throws Exception {
        Method method = AdminSettingsController.class.getMethod("approvals");
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
    }

    @Test
    void approvals_includeCreditOverridesWithExplicitApprovalSummary() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        when(exportApprovalService.listPending()).thenReturn(List.of());
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 501L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Dealer alpha = new Dealer();
        alpha.setName("Dealer Alpha");
        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setCompany(company);
        creditRequest.setDealer(alpha);
        creditRequest.setAmountRequested(new BigDecimal("5000"));
        creditRequest.setStatus("PENDING");
        creditRequest.setRequesterUserId(4401L);
        creditRequest.setRequesterEmail("dealer.user@bbp.com");
        ReflectionTestUtils.setField(creditRequest, "id", 10L);
        ReflectionTestUtils.setField(creditRequest, "publicId", UUID.fromString("11111111-1111-1111-1111-111111111111"));
        ReflectionTestUtils.setField(creditRequest, "createdAt", Instant.parse("2026-02-12T11:00:00Z"));

        Dealer beta = new Dealer();
        beta.setName("Dealer Beta");
        PackagingSlip slip = new PackagingSlip();
        slip.setSlipNumber("PS-44");
        CreditLimitOverrideRequest overrideRequest = new CreditLimitOverrideRequest();
        overrideRequest.setCompany(company);
        overrideRequest.setDealer(beta);
        overrideRequest.setPackagingSlip(slip);
        overrideRequest.setDispatchAmount(new BigDecimal("300"));
        overrideRequest.setCurrentExposure(new BigDecimal("1200"));
        overrideRequest.setCreditLimit(new BigDecimal("1000"));
        overrideRequest.setRequiredHeadroom(new BigDecimal("500"));
        overrideRequest.setRequestedBy("sales.user@bbp.com");
        overrideRequest.setStatus("PENDING");
        ReflectionTestUtils.setField(overrideRequest, "id", 20L);
        ReflectionTestUtils.setField(overrideRequest, "publicId", UUID.fromString("22222222-2222-2222-2222-222222222222"));
        ReflectionTestUtils.setField(overrideRequest, "createdAt", Instant.parse("2026-02-12T12:00:00Z"));

        Dealer gamma = new Dealer();
        gamma.setName("Dealer Gamma");
        SalesOrder order = new SalesOrder();
        order.setOrderNumber("SO-55");
        CreditLimitOverrideRequest orderOverrideRequest = new CreditLimitOverrideRequest();
        orderOverrideRequest.setCompany(company);
        orderOverrideRequest.setDealer(gamma);
        orderOverrideRequest.setSalesOrder(order);
        orderOverrideRequest.setDispatchAmount(new BigDecimal("410"));
        orderOverrideRequest.setCurrentExposure(new BigDecimal("610"));
        orderOverrideRequest.setCreditLimit(new BigDecimal("700"));
        orderOverrideRequest.setRequiredHeadroom(new BigDecimal("320"));
        orderOverrideRequest.setRequestedBy("factory.user@bbp.com");
        orderOverrideRequest.setStatus("PENDING");
        ReflectionTestUtils.setField(orderOverrideRequest, "id", 21L);
        ReflectionTestUtils.setField(orderOverrideRequest, "publicId", UUID.fromString("44444444-4444-4444-4444-444444444444"));
        ReflectionTestUtils.setField(orderOverrideRequest, "createdAt", Instant.parse("2026-02-12T12:30:00Z"));

        Dealer epsilon = new Dealer();
        epsilon.setName("Dealer Epsilon");
        PackagingSlip slipAndOrderRef = new PackagingSlip();
        slipAndOrderRef.setSlipNumber("PS-66");
        SalesOrder slipAndOrder = new SalesOrder();
        slipAndOrder.setOrderNumber("SO-66");
        CreditLimitOverrideRequest slipAndOrderOverrideRequest = new CreditLimitOverrideRequest();
        slipAndOrderOverrideRequest.setCompany(company);
        slipAndOrderOverrideRequest.setDealer(epsilon);
        slipAndOrderOverrideRequest.setPackagingSlip(slipAndOrderRef);
        slipAndOrderOverrideRequest.setSalesOrder(slipAndOrder);
        slipAndOrderOverrideRequest.setDispatchAmount(new BigDecimal("275"));
        slipAndOrderOverrideRequest.setCurrentExposure(new BigDecimal("900"));
        slipAndOrderOverrideRequest.setCreditLimit(new BigDecimal("850"));
        slipAndOrderOverrideRequest.setRequiredHeadroom(new BigDecimal("325"));
        slipAndOrderOverrideRequest.setRequestedBy("sales.manager@bbp.com");
        slipAndOrderOverrideRequest.setStatus("PENDING");
        ReflectionTestUtils.setField(slipAndOrderOverrideRequest, "id", 23L);
        ReflectionTestUtils.setField(slipAndOrderOverrideRequest, "publicId", UUID.fromString("66666666-6666-6666-6666-666666666666"));
        ReflectionTestUtils.setField(slipAndOrderOverrideRequest, "createdAt", Instant.parse("2026-02-12T12:15:00Z"));

        Dealer delta = new Dealer();
        delta.setName("Dealer Delta");
        CreditLimitOverrideRequest fallbackOverrideRequest = new CreditLimitOverrideRequest();
        fallbackOverrideRequest.setCompany(company);
        fallbackOverrideRequest.setDealer(delta);
        fallbackOverrideRequest.setDispatchAmount(new BigDecimal("90"));
        fallbackOverrideRequest.setCurrentExposure(new BigDecimal("150"));
        fallbackOverrideRequest.setCreditLimit(new BigDecimal("100"));
        fallbackOverrideRequest.setRequiredHeadroom(new BigDecimal("140"));
        fallbackOverrideRequest.setRequestedBy("ops.user@bbp.com");
        fallbackOverrideRequest.setStatus("PENDING");
        ReflectionTestUtils.setField(fallbackOverrideRequest, "id", 22L);
        ReflectionTestUtils.setField(fallbackOverrideRequest, "publicId", UUID.fromString("55555555-5555-5555-5555-555555555555"));
        ReflectionTestUtils.setField(fallbackOverrideRequest, "createdAt", Instant.parse("2026-02-12T12:45:00Z"));

        PayrollRun payrollRun = new PayrollRun();
        payrollRun.setCompany(company);
        payrollRun.setRunNumber("PR-2026-02");
        payrollRun.setRunType(PayrollRun.RunType.MONTHLY);
        payrollRun.setPeriodStart(LocalDate.of(2026, 2, 1));
        payrollRun.setPeriodEnd(LocalDate.of(2026, 2, 28));
        payrollRun.setStatus(PayrollRun.PayrollStatus.CALCULATED);
        ReflectionTestUtils.setField(payrollRun, "id", 30L);
        ReflectionTestUtils.setField(payrollRun, "publicId", UUID.fromString("33333333-3333-3333-3333-333333333333"));
        ReflectionTestUtils.setField(payrollRun, "createdAt", Instant.parse("2026-02-12T10:00:00Z"));

        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of(creditRequest));
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of(overrideRequest, orderOverrideRequest, fallbackOverrideRequest, slipAndOrderOverrideRequest));
        PeriodCloseRequest periodCloseRequest = new PeriodCloseRequest();
        periodCloseRequest.setCompany(company);
        AccountingPeriod period = new AccountingPeriod();
        period.setYear(2026);
        period.setMonth(2);
        period.setStatus(com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus.LOCKED);
        periodCloseRequest.setAccountingPeriod(period);
        periodCloseRequest.setStatus(PeriodCloseRequestStatus.PENDING);
        periodCloseRequest.setForceRequested(true);
        periodCloseRequest.setRequestedBy("maker.user@bbp.com");
        periodCloseRequest.setRequestNote("Close after reconciliation");
        periodCloseRequest.setRequestedAt(Instant.parse("2026-02-12T13:15:00Z"));
        ReflectionTestUtils.setField(periodCloseRequest, "id", 91L);
        ReflectionTestUtils.setField(periodCloseRequest, "publicId", UUID.fromString("12345678-1234-1234-1234-123456789012"));

        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of(payrollRun));
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
                .thenReturn(List.of(periodCloseRequest));

        authenticateAs("ROLE_ADMIN");
        ApiResponse<AdminApprovalsResponse> response;
        try {
            response = controller.approvals();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).hasSize(5);

        AdminApprovalItemDto overrideApproval = response.data().creditRequests().get(0);
        assertThat(overrideApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST);
        assertThat(overrideApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.SALES);
        assertThat(overrideApproval.reference()).isEqualTo("CLO-22");
        assertThat(overrideApproval.summary()).contains("request CLO-22");
        assertThat(overrideApproval.summary()).contains("Dealer Delta");
        assertThat(overrideApproval.summary()).contains("requested by ops.user@bbp.com");
        assertThat(overrideApproval.actionType()).isEqualTo("APPROVE_DISPATCH_CREDIT_OVERRIDE");
        assertThat(overrideApproval.actionLabel()).isEqualTo("Approve dispatch credit override");
        assertThat(overrideApproval.approveEndpoint()).isEqualTo("/api/v1/credit/override-requests/{id}/approve");
        assertThat(overrideApproval.rejectEndpoint()).isEqualTo("/api/v1/credit/override-requests/{id}/reject");

        AdminApprovalItemDto orderOverrideApproval = response.data().creditRequests().get(1);
        assertThat(orderOverrideApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST);
        assertThat(orderOverrideApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.SALES);
        assertThat(orderOverrideApproval.reference()).isEqualTo("SO-55");
        assertThat(orderOverrideApproval.summary()).contains("request SO-55");
        assertThat(orderOverrideApproval.summary()).contains("Dealer Gamma");
        assertThat(orderOverrideApproval.summary()).contains("requested by factory.user@bbp.com");
        AdminApprovalItemDto slipOverrideApproval = response.data().creditRequests().get(2);
        assertThat(slipOverrideApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST);
        assertThat(slipOverrideApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.FACTORY);
        assertThat(slipOverrideApproval.reference()).isEqualTo("PS-66");
        assertThat(slipOverrideApproval.summary()).contains("request PS-66");
        assertThat(slipOverrideApproval.summary()).contains("Dealer Epsilon");
        assertThat(slipOverrideApproval.summary()).contains("requested by sales.manager@bbp.com");
        AdminApprovalItemDto slipOnlyOverrideApproval = response.data().creditRequests().get(3);
        assertThat(slipOnlyOverrideApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST);
        assertThat(slipOnlyOverrideApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.FACTORY);
        assertThat(slipOnlyOverrideApproval.reference()).isEqualTo("PS-44");
        assertThat(slipOnlyOverrideApproval.summary()).contains("Approve dispatch credit override");
        assertThat(slipOnlyOverrideApproval.summary()).contains("Dealer Beta");
        assertThat(slipOnlyOverrideApproval.summary()).contains("dispatch 300");
        assertThat(slipOnlyOverrideApproval.summary()).contains("requested by sales.user@bbp.com");
        AdminApprovalItemDto creditApproval = response.data().creditRequests().get(4);
        assertThat(creditApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_REQUEST);
        assertThat(creditApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.SALES);
        assertThat(creditApproval.reference()).isEqualTo("CLR-10");
        assertThat(creditApproval.summary()).contains("Approve permanent dealer credit-limit request CLR-10 for Dealer Alpha");
        assertThat(creditApproval.summary()).contains("requested by dealer.user@bbp.com");
        assertThat(creditApproval.requesterUserId()).isEqualTo(4401L);
        assertThat(creditApproval.requesterEmail()).isEqualTo("dealer.user@bbp.com");
        assertThat(creditApproval.actionType()).isEqualTo("APPROVE_DEALER_CREDIT_LIMIT_REQUEST");
        assertThat(creditApproval.actionLabel()).isEqualTo("Approve permanent credit limit");
        assertThat(creditApproval.approveEndpoint()).isEqualTo("/api/v1/credit/limit-requests/{id}/approve");
        assertThat(creditApproval.rejectEndpoint()).isEqualTo("/api/v1/credit/limit-requests/{id}/reject");

        assertThat(response.data().creditRequests())
                .extracting(AdminApprovalItemDto::createdAt)
                .containsExactly(
                        Instant.parse("2026-02-12T12:45:00Z"),
                        Instant.parse("2026-02-12T12:30:00Z"),
                        Instant.parse("2026-02-12T12:15:00Z"),
                        Instant.parse("2026-02-12T12:00:00Z"),
                        Instant.parse("2026-02-12T11:00:00Z")
                );

        assertThat(response.data().payrollRuns()).hasSize(1);
        AdminApprovalItemDto payrollApproval = response.data().payrollRuns().get(0);
        assertThat(payrollApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PAYROLL_RUN);
        assertThat(payrollApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.HR);
        assertThat(payrollApproval.reference()).isEqualTo("PR-2026-02");
        assertThat(payrollApproval.summary()).contains("Approve payroll run PR-2026-02");
        assertThat(payrollApproval.actionType()).isEqualTo("APPROVE_PAYROLL_RUN");
        assertThat(payrollApproval.actionLabel()).isEqualTo("Approve payroll run");
        assertThat(payrollApproval.approveEndpoint()).isEqualTo("/api/v1/payroll/runs/{id}/approve");
        assertThat(payrollApproval.rejectEndpoint()).isNull();

        assertThat(response.data().periodCloseRequests()).hasSize(1);
        AdminApprovalItemDto periodCloseApproval = response.data().periodCloseRequests().get(0);
        assertThat(periodCloseApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PERIOD_CLOSE_REQUEST);
        assertThat(periodCloseApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.ACCOUNTING);
        assertThat(periodCloseApproval.reference()).isEqualTo("February 2026");
        assertThat(periodCloseApproval.status()).isEqualTo("PENDING");
        assertThat(periodCloseApproval.summary()).contains("Approve accounting period close request for February 2026");
        assertThat(periodCloseApproval.summary()).contains("current status: LOCKED");
        assertThat(periodCloseApproval.summary()).contains("[force requested]");
        assertThat(periodCloseApproval.summary()).contains("requested by maker.user@bbp.com");
        assertThat(periodCloseApproval.summary()).contains("note: Close after reconciliation");
        assertThat(periodCloseApproval.actionType()).isEqualTo("APPROVE_ACCOUNTING_PERIOD_CLOSE");
        assertThat(periodCloseApproval.actionLabel()).isEqualTo("Approve accounting period close");
        assertThat(periodCloseApproval.approveEndpoint()).isEqualTo("/api/v1/accounting/periods/{id}/approve-close");
        assertThat(periodCloseApproval.rejectEndpoint()).isEqualTo("/api/v1/accounting/periods/{id}/reject-close");
        assertThat(periodCloseApproval.createdAt()).isEqualTo(Instant.parse("2026-02-12T13:15:00Z"));
    }

    @Test
    void approvals_includeTypedExportApprovalsInSingleInbox() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 502L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of());
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company)).thenReturn(List.of());
        when(exportApprovalService.listPending()).thenReturn(List.of(
                new ExportRequestDto(
                        81L,
                        7001L,
                        "ops.reports@bbp.com",
                        "SALES_SUMMARY",
                        "{\"range\":\"MTD\"}",
                        ExportApprovalStatus.PENDING,
                        null,
                        Instant.parse("2026-02-12T14:30:00Z"),
                        null,
                        null)
        ));

        authenticateAs("ROLE_ADMIN");
        ApiResponse<AdminApprovalsResponse> response;
        try {
            response = controller.approvals();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().exportRequests()).hasSize(1);

        AdminApprovalItemDto exportApproval = response.data().exportRequests().get(0);
        assertThat(exportApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.EXPORT_REQUEST);
        assertThat(exportApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.REPORTS);
        assertThat(exportApproval.reference()).isEqualTo("EXP-81");
        assertThat(exportApproval.status()).isEqualTo("PENDING");
        assertThat(exportApproval.summary()).contains("report SALES_SUMMARY");
        assertThat(exportApproval.reportType()).isEqualTo("SALES_SUMMARY");
        assertThat(exportApproval.actionType()).isEqualTo("APPROVE_EXPORT_REQUEST");
        assertThat(exportApproval.actionLabel()).isEqualTo("Approve data export");
        assertThat(exportApproval.approveEndpoint()).isEqualTo("/api/v1/admin/exports/{id}/approve");
        assertThat(exportApproval.rejectEndpoint()).isEqualTo("/api/v1/admin/exports/{id}/reject");
        assertThat(exportApproval.createdAt()).isEqualTo(Instant.parse("2026-02-12T14:30:00Z"));
    }

    @Test
    void approvals_redactsSensitiveExportDetailsForAccountingView() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 504L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of());
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company)).thenReturn(List.of());
        when(exportApprovalService.listPending()).thenReturn(List.of(
                new ExportRequestDto(
                        91L,
                        8100L,
                        "ops.reports@bbp.com",
                        "AGED_DEBTORS",
                        "{\"range\":\"MTD\"}",
                        ExportApprovalStatus.PENDING,
                        null,
                        Instant.parse("2026-02-13T09:15:00Z"),
                        null,
                        null)
        ));

        authenticateAs("ROLE_ACCOUNTING");
        ApiResponse<AdminApprovalsResponse> response;
        try {
            response = controller.approvals();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().exportRequests()).hasSize(1);

        AdminApprovalItemDto exportApproval = response.data().exportRequests().get(0);
        assertThat(exportApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.EXPORT_REQUEST);
        assertThat(exportApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.REPORTS);
        assertThat(exportApproval.reference()).isEqualTo("EXP-91");
        assertThat(exportApproval.summary()).contains("report AGED_DEBTORS");
        assertThat(exportApproval.summary()).doesNotContain("ops.reports@bbp.com");
        assertThat(exportApproval.reportType()).isEqualTo("AGED_DEBTORS");
        assertThat(exportApproval.parameters()).isNull();
        assertThat(exportApproval.requesterUserId()).isNull();
        assertThat(exportApproval.requesterEmail()).isNull();
        assertThat(exportApproval.actionType()).isNull();
        assertThat(exportApproval.actionLabel()).isNull();
        assertThat(exportApproval.approveEndpoint()).isNull();
        assertThat(exportApproval.rejectEndpoint()).isNull();
    }

    @Test
    void approvals_redactsCreditRequestRequesterIdentityForAccountingView() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        when(exportApprovalService.listPending()).thenReturn(List.of());
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 506L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer Privacy");
        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setCompany(company);
        creditRequest.setDealer(dealer);
        creditRequest.setAmountRequested(new BigDecimal("4200"));
        creditRequest.setReason("Limit review");
        creditRequest.setStatus("PENDING");
        creditRequest.setRequesterUserId(8801L);
        creditRequest.setRequesterEmail("dealer.privacy@bbp.com");
        ReflectionTestUtils.setField(creditRequest, "id", 61L);
        ReflectionTestUtils.setField(creditRequest, "publicId", UUID.fromString("61616161-6161-6161-6161-616161616161"));
        ReflectionTestUtils.setField(creditRequest, "createdAt", Instant.parse("2026-02-14T09:00:00Z"));

        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of(creditRequest));
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of());
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
                .thenReturn(List.of());

        authenticateAs("ROLE_ACCOUNTING");
        ApiResponse<AdminApprovalsResponse> response;
        try {
            response = controller.approvals();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).hasSize(1);
        AdminApprovalItemDto creditApproval = response.data().creditRequests().get(0);
        assertThat(creditApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.CREDIT_REQUEST);
        assertThat(creditApproval.summary()).contains("Approve permanent dealer credit-limit request CLR-61 for Dealer Privacy");
        assertThat(creditApproval.summary()).doesNotContain("requested by");
        assertThat(creditApproval.requesterUserId()).isNull();
        assertThat(creditApproval.requesterEmail()).isNull();
    }

    @Test
    void approvals_fallsBackToUnknownStatusForExportRequests() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 505L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of());
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company)).thenReturn(List.of());
        when(exportApprovalService.listPending()).thenReturn(List.of(
                new ExportRequestDto(
                        92L,
                        9100L,
                        "ops.reports@bbp.com",
                        "INVENTORY_LEDGER",
                        "periodId=11",
                        null,
                        null,
                        Instant.parse("2026-02-13T10:15:00Z"),
                        null,
                        null)
        ));

        ApiResponse<AdminApprovalsResponse> response = controller.approvals();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().exportRequests()).hasSize(1);
        assertThat(response.data().exportRequests().get(0).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void exportApprovalItem_omitsRequesterIdentityWhenPrivilegedViewHasNoRequesterEmail() {
        AdminSettingsController controller = new AdminSettingsController(
                mock(SystemSettingsService.class),
                mock(EmailService.class),
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PeriodCloseRequestRepository.class),
                mock(PayrollRunRepository.class),
                null
        );

        ExportRequestDto request = new ExportRequestDto(
                93L,
                9300L,
                null,
                "INVENTORY_LEDGER",
                "periodId=11",
                ExportApprovalStatus.PENDING,
                null,
                Instant.parse("2026-02-13T10:30:00Z"),
                null,
                null
        );

        AdminApprovalItemDto exportApproval = ReflectionTestUtils.invokeMethod(
                controller,
                "toExportApprovalItem",
                request,
                true
        );

        assertThat(exportApproval).isNotNull();
        assertThat(exportApproval.summary()).contains("report INVENTORY_LEDGER");
        assertThat(exportApproval.summary()).doesNotContain("requested by");
        assertThat(exportApproval.reportType()).isEqualTo("INVENTORY_LEDGER");
        assertThat(exportApproval.parameters()).isEqualTo("periodId=11");
        assertThat(exportApproval.requesterUserId()).isEqualTo(9300L);
        assertThat(exportApproval.requesterEmail()).isNull();
    }

    @Test
    void sensitiveApprovalRequesterDetails_allowSuperAdminView() {
        AdminSettingsController controller = new AdminSettingsController(
                mock(SystemSettingsService.class),
                mock(EmailService.class),
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PeriodCloseRequestRepository.class),
                mock(PayrollRunRepository.class),
                null
        );

        authenticateAs("ROLE_SUPER_ADMIN");
        try {
            Boolean includeSensitiveDetails = ReflectionTestUtils.invokeMethod(
                    controller,
                    "canViewSensitiveApprovalRequesterDetails"
            );
            assertThat(includeSensitiveDetails).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void sensitiveApprovalRequesterDetails_rejectWhenAuthenticationAuthoritiesMissing() {
        AdminSettingsController controller = new AdminSettingsController(
                mock(SystemSettingsService.class),
                mock(EmailService.class),
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PeriodCloseRequestRepository.class),
                mock(PayrollRunRepository.class),
                null
        );

        var authentication = mock(org.springframework.security.core.Authentication.class);
        when(authentication.getAuthorities()).thenReturn(null);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        try {
            Boolean includeSensitiveDetails = ReflectionTestUtils.invokeMethod(
                    controller,
                    "canViewSensitiveApprovalRequesterDetails"
            );
            assertThat(includeSensitiveDetails).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void adminApprovalItemSerialization_keepsStableNullQueueFields() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        AdminApprovalItemDto payrollApproval = new AdminApprovalItemDto(
                AdminApprovalItemDto.OriginType.PAYROLL_RUN,
                AdminApprovalItemDto.OwnerType.HR,
                41L,
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "PR-41",
                "PENDING",
                "Approve payroll run PR-41",
                null,
                null,
                null,
                null,
                "APPROVE_PAYROLL_RUN",
                "Approve payroll run",
                "/api/v1/payroll/runs/{id}/approve",
                null,
                Instant.parse("2026-02-13T11:00:00Z")
        );

        var json = mapper.readTree(mapper.writeValueAsString(payrollApproval));

        assertThat(json.has("approveEndpoint")).isTrue();
        assertThat(json.has("rejectEndpoint")).isTrue();
        assertThat(json.get("rejectEndpoint").isNull()).isTrue();
        assertThat(json.has("reportType")).isFalse();
        assertThat(json.has("parameters")).isFalse();
        assertThat(json.has("requesterUserId")).isFalse();
        assertThat(json.has("requesterEmail")).isFalse();
    }

    @Test
    void adminApprovalItemSerialization_keepsNullExportActionFieldsForAccountingRows() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        AdminApprovalItemDto exportApproval = new AdminApprovalItemDto(
                AdminApprovalItemDto.OriginType.EXPORT_REQUEST,
                AdminApprovalItemDto.OwnerType.REPORTS,
                51L,
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "EXP-51",
                ExportApprovalStatus.PENDING.name(),
                "Review export request EXP-51 for report SALES_SUMMARY",
                "SALES_SUMMARY",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-02-13T12:00:00Z")
        );

        var json = mapper.readTree(mapper.writeValueAsString(exportApproval));

        assertThat(json.has("reportType")).isTrue();
        assertThat(json.get("reportType").asText()).isEqualTo("SALES_SUMMARY");
        assertThat(json.has("parameters")).isFalse();
        assertThat(json.has("requesterUserId")).isFalse();
        assertThat(json.has("requesterEmail")).isFalse();
        assertThat(json.has("actionType")).isTrue();
        assertThat(json.get("actionType").isNull()).isTrue();
        assertThat(json.has("actionLabel")).isTrue();
        assertThat(json.get("actionLabel").isNull()).isTrue();
        assertThat(json.has("approveEndpoint")).isTrue();
        assertThat(json.get("approveEndpoint").isNull()).isTrue();
        assertThat(json.has("rejectEndpoint")).isTrue();
        assertThat(json.get("rejectEndpoint").isNull()).isTrue();
    }

    @Test
    void approvals_appliesUnknownFallbacksForStatusDealerAndAmounts() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        when(exportApprovalService.listPending()).thenReturn(List.of());
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 503L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setCompany(company);
        creditRequest.setDealer(null);
        creditRequest.setAmountRequested(null);
        creditRequest.setReason("  Need urgent raw material  ");
        creditRequest.setStatus("   ");
        ReflectionTestUtils.setField(creditRequest, "id", 40L);
        ReflectionTestUtils.setField(creditRequest, "publicId", UUID.fromString("88888888-8888-8888-8888-888888888888"));
        ReflectionTestUtils.setField(creditRequest, "createdAt", Instant.parse("2026-04-12T09:00:00Z"));

        Dealer unknownDealer = new Dealer();
        unknownDealer.setName(" ");
        PackagingSlip blankSlip = new PackagingSlip();
        blankSlip.setSlipNumber(" ");
        SalesOrder blankOrder = new SalesOrder();
        blankOrder.setOrderNumber(" ");
        CreditLimitOverrideRequest overrideRequest = new CreditLimitOverrideRequest();
        overrideRequest.setCompany(company);
        overrideRequest.setDealer(unknownDealer);
        overrideRequest.setPackagingSlip(blankSlip);
        overrideRequest.setSalesOrder(blankOrder);
        overrideRequest.setDispatchAmount(null);
        overrideRequest.setCurrentExposure(null);
        overrideRequest.setCreditLimit(null);
        overrideRequest.setRequiredHeadroom(null);
        overrideRequest.setRequestedBy("  ");
        overrideRequest.setStatus(null);
        ReflectionTestUtils.setField(overrideRequest, "id", 41L);
        ReflectionTestUtils.setField(overrideRequest, "publicId", UUID.fromString("99999999-9999-9999-9999-999999999999"));
        ReflectionTestUtils.setField(overrideRequest, "createdAt", Instant.parse("2026-04-12T08:00:00Z"));

        PayrollRun payrollRun = new PayrollRun();
        payrollRun.setCompany(company);
        payrollRun.setRunNumber("PR-NULL-STATUS");
        payrollRun.setRunType(PayrollRun.RunType.MONTHLY);
        payrollRun.setPeriodStart(LocalDate.of(2026, 4, 1));
        payrollRun.setPeriodEnd(LocalDate.of(2026, 4, 30));
        payrollRun.setStatus((PayrollRun.PayrollStatus) null);
        ReflectionTestUtils.setField(payrollRun, "id", 42L);
        ReflectionTestUtils.setField(payrollRun, "publicId", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        ReflectionTestUtils.setField(payrollRun, "createdAt", Instant.parse("2026-04-12T07:00:00Z"));

        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of(creditRequest));
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of(overrideRequest));
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of(payrollRun));
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
                .thenReturn(List.of());

        ApiResponse<AdminApprovalsResponse> response = controller.approvals();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).hasSize(2);

        AdminApprovalItemDto creditApproval = response.data().creditRequests().stream()
                .filter(item -> item.originType() == AdminApprovalItemDto.OriginType.CREDIT_REQUEST)
                .findFirst()
                .orElseThrow();
        assertThat(creditApproval.reference()).isEqualTo("CLR-40");
        assertThat(creditApproval.status()).isEqualTo("UNKNOWN");
        assertThat(creditApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.SALES);
        assertThat(creditApproval.summary()).contains("for Unknown dealer amount 0");
        assertThat(creditApproval.summary()).contains("(reason: Need urgent raw material)");

        AdminApprovalItemDto overrideApproval = response.data().creditRequests().stream()
                .filter(item -> item.originType() == AdminApprovalItemDto.OriginType.CREDIT_LIMIT_OVERRIDE_REQUEST)
                .findFirst()
                .orElseThrow();
        assertThat(overrideApproval.reference()).isEqualTo("CLO-41");
        assertThat(overrideApproval.status()).isEqualTo("UNKNOWN");
        assertThat(overrideApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.FACTORY);
        assertThat(overrideApproval.summary()).contains("for Unknown dealer");
        assertThat(overrideApproval.summary()).contains("dispatch 0");
        assertThat(overrideApproval.summary()).doesNotContain("requested by");

        assertThat(response.data().payrollRuns()).hasSize(1);
        AdminApprovalItemDto payrollApproval = response.data().payrollRuns().get(0);
        assertThat(payrollApproval.reference()).isEqualTo("PR-NULL-STATUS");
        assertThat(payrollApproval.status()).isEqualTo("UNKNOWN");
        assertThat(payrollApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PAYROLL_RUN);
        assertThat(payrollApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.HR);
        assertThat(response.data().periodCloseRequests()).isEmpty();
    }

    private void authenticateAs(String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "approvals-contract-test",
                "n/a",
                Stream.of(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
        authentication.setAuthenticated(true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Test
    void approvals_payrollFallbackReferenceUsesIdWhenRunNumberMissing() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        when(exportApprovalService.listPending()).thenReturn(List.of());
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 502L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        PayrollRun payrollRun = new PayrollRun();
        payrollRun.setCompany(company);
        payrollRun.setRunNumber("  ");
        payrollRun.setRunType(PayrollRun.RunType.MONTHLY);
        payrollRun.setPeriodStart(LocalDate.of(2026, 3, 1));
        payrollRun.setPeriodEnd(LocalDate.of(2026, 3, 31));
        payrollRun.setStatus(PayrollRun.PayrollStatus.CALCULATED);
        ReflectionTestUtils.setField(payrollRun, "id", 31L);
        ReflectionTestUtils.setField(payrollRun, "publicId", UUID.fromString("77777777-7777-7777-7777-777777777777"));
        ReflectionTestUtils.setField(payrollRun, "createdAt", Instant.parse("2026-03-12T10:00:00Z"));

        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of());
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of(payrollRun));
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
                .thenReturn(List.of());

        ApiResponse<AdminApprovalsResponse> response = controller.approvals();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).isEmpty();
        assertThat(response.data().payrollRuns()).hasSize(1);

        AdminApprovalItemDto payrollApproval = response.data().payrollRuns().get(0);
        assertThat(payrollApproval.originType()).isEqualTo(AdminApprovalItemDto.OriginType.PAYROLL_RUN);
        assertThat(payrollApproval.ownerType()).isEqualTo(AdminApprovalItemDto.OwnerType.HR);
        assertThat(payrollApproval.reference()).isEqualTo("PR-31");
        assertThat(payrollApproval.summary()).contains("Approve payroll run PR-31");
        assertThat(payrollApproval.actionType()).isEqualTo("APPROVE_PAYROLL_RUN");
        assertThat(response.data().periodCloseRequests()).isEmpty();
    }

    @Test
    void approvals_periodCloseSummaryFallsBackWhenFieldsMissing() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PeriodCloseRequestRepository periodCloseRequestRepository = mock(PeriodCloseRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
        when(exportApprovalService.listPending()).thenReturn(List.of());
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                tenantRuntimePolicyService,
                exportApprovalService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                periodCloseRequestRepository,
                payrollRunRepository,
                null
        );

        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 504L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        PeriodCloseRequest periodCloseRequest = new PeriodCloseRequest();
        periodCloseRequest.setCompany(company);
        periodCloseRequest.setAccountingPeriod(null);
        periodCloseRequest.setStatus(null);
        periodCloseRequest.setForceRequested(false);
        periodCloseRequest.setRequestedBy("  ");
        periodCloseRequest.setRequestNote("   ");
        periodCloseRequest.setRequestedAt(Instant.parse("2026-04-12T06:00:00Z"));
        ReflectionTestUtils.setField(periodCloseRequest, "id", 92L);
        ReflectionTestUtils.setField(periodCloseRequest, "publicId", UUID.fromString("abcdefab-cdef-cdef-cdef-abcdefabcdef"));

        when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of());
        when(creditLimitOverrideRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(company))
                .thenReturn(List.of());
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of());
        when(periodCloseRequestRepository.findPendingByCompanyOrderByRequestedAtDesc(company))
                .thenReturn(List.of(periodCloseRequest));

        ApiResponse<AdminApprovalsResponse> response = controller.approvals();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).isEmpty();
        assertThat(response.data().payrollRuns()).isEmpty();
        assertThat(response.data().periodCloseRequests()).hasSize(1);

        AdminApprovalItemDto periodCloseApproval = response.data().periodCloseRequests().get(0);
        assertThat(periodCloseApproval.reference()).isEqualTo("PERIOD-92");
        assertThat(periodCloseApproval.status()).isEqualTo("UNKNOWN");
        assertThat(periodCloseApproval.summary()).isEqualTo("Approve accounting period close request for PERIOD-92");
        assertThat(periodCloseApproval.actionType()).isEqualTo("APPROVE_ACCOUNTING_PERIOD_CLOSE");
    }
}
