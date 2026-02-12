package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalItemDto;
import com.bigbrightpaints.erp.modules.admin.dto.AdminApprovalsResponse;
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
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSettingsControllerApprovalsContractTest {

    @Test
    void approvals_includeCreditOverridesWithExplicitApprovalSummary() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        EmailService emailService = mock(EmailService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CreditRequestRepository creditRequestRepository = mock(CreditRequestRepository.class);
        CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository =
                mock(CreditLimitOverrideRequestRepository.class);
        PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                emailService,
                companyContextService,
                creditRequestRepository,
                creditLimitOverrideRequestRepository,
                payrollRunRepository
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

        when(creditRequestRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING"))
                .thenReturn(List.of(creditRequest));
        when(creditLimitOverrideRequestRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING"))
                .thenReturn(List.of(overrideRequest));
        when(payrollRunRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, PayrollRun.PayrollStatus.CALCULATED))
                .thenReturn(List.of(payrollRun));

        ApiResponse<AdminApprovalsResponse> response = controller.approvals();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().creditRequests()).hasSize(2);

        AdminApprovalItemDto overrideApproval = response.data().creditRequests().get(0);
        assertThat(overrideApproval.type()).isEqualTo("CREDIT_LIMIT_OVERRIDE_REQUEST");
        assertThat(overrideApproval.reference()).isEqualTo("PS-44");
        assertThat(overrideApproval.summary()).contains("Approve dispatch credit override");
        assertThat(overrideApproval.summary()).contains("Dealer Beta");
        assertThat(overrideApproval.summary()).contains("dispatch 300");
        assertThat(overrideApproval.summary()).contains("requested by sales.user@bbp.com");

        AdminApprovalItemDto creditApproval = response.data().creditRequests().get(1);
        assertThat(creditApproval.type()).isEqualTo("CREDIT_REQUEST");
        assertThat(creditApproval.reference()).isEqualTo("CR-10");
        assertThat(creditApproval.summary()).contains("Approve credit-limit increase for Dealer Alpha");

        assertThat(response.data().payrollRuns()).hasSize(1);
        assertThat(response.data().payrollRuns().get(0).type()).isEqualTo("PAYROLL_RUN");
    }
}
