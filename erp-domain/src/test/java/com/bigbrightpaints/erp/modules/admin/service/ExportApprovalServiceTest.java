package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestCreateRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class ExportApprovalServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private ExportRequestRepository exportRequestRepository;
  @Mock private SystemSettingsService systemSettingsService;

  private ExportApprovalService service;
  private Company company;
  private UserAccount actor;

  @BeforeEach
  void setUp() {
    service =
        new ExportApprovalService(
            companyContextService,
            userAccountRepository,
            exportRequestRepository,
            systemSettingsService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("EXP");
    company.setTimezone("UTC");

    actor = new UserAccount("admin@bbp.com", "hash", "Export Admin");
    ReflectionTestUtils.setField(actor, "id", 11L);
    actor.setCompany(company);

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("admin@bbp.com", "n/a", List.of()));
  }

  @Test
  void createRequest_persistsPendingExportRequestForActor() {
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "admin@bbp.com", "EXP"))
        .thenReturn(Optional.of(actor));
    when(exportRequestRepository.save(any(ExportRequest.class)))
        .thenAnswer(
            invocation -> {
              ExportRequest request = invocation.getArgument(0);
              ReflectionTestUtils.setField(request, "id", 101L);
              request.setStatus(ExportApprovalStatus.PENDING);
              return request;
            });

    var response =
        service.createRequest(new ExportRequestCreateRequest("trial-balance", "periodId=12"));

    assertThat(response.id()).isEqualTo(101L);
    assertThat(response.userId()).isEqualTo(11L);
    assertThat(response.userEmail()).isEqualTo("admin@bbp.com");
    assertThat(response.reportType()).isEqualTo("TRIAL-BALANCE");
    assertThat(response.status()).isEqualTo(ExportApprovalStatus.PENDING);
  }

  @Test
  void listPending_mapsUserEmailAndStatus() {
    ExportRequest pending = new ExportRequest();
    pending.setCompany(company);
    pending.setUserId(11L);
    pending.setReportType("AGED-DEBTORS");
    pending.setStatus(ExportApprovalStatus.PENDING);
    ReflectionTestUtils.setField(pending, "id", 55L);
    ReflectionTestUtils.setField(pending, "createdAt", Instant.parse("2026-03-04T00:00:00Z"));

    when(userAccountRepository.findById(11L)).thenReturn(Optional.of(actor));
    when(exportRequestRepository.findByCompanyAndStatusOrderByCreatedAtAsc(
            company, ExportApprovalStatus.PENDING))
        .thenReturn(List.of(pending));

    var items = service.listPending();

    assertThat(items).hasSize(1);
    assertThat(items.getFirst().id()).isEqualTo(55L);
    assertThat(items.getFirst().userEmail()).isEqualTo("admin@bbp.com");
    assertThat(items.getFirst().status()).isEqualTo(ExportApprovalStatus.PENDING);
  }

  @Test
  void approve_setsApprovalMetadata() {
    ExportRequest pending = new ExportRequest();
    pending.setCompany(company);
    pending.setUserId(11L);
    pending.setStatus(ExportApprovalStatus.PENDING);
    pending.setReportType("TRIAL-BALANCE");
    ReflectionTestUtils.setField(pending, "id", 88L);
    ReflectionTestUtils.setField(pending, "createdAt", Instant.parse("2026-03-04T00:00:00Z"));

    when(userAccountRepository.findById(11L)).thenReturn(Optional.of(actor));
    when(exportRequestRepository.findByCompanyAndId(company, 88L)).thenReturn(Optional.of(pending));

    var approved = service.approve(88L);

    assertThat(approved.status()).isEqualTo(ExportApprovalStatus.APPROVED);
    assertThat(approved.approvedBy()).isEqualTo("admin@bbp.com");
    assertThat(approved.approvedAt()).isNotNull();
  }

  @Test
  void reject_setsRejectedStateAndReason() {
    ExportRequest pending = new ExportRequest();
    pending.setCompany(company);
    pending.setUserId(11L);
    pending.setStatus(ExportApprovalStatus.PENDING);
    pending.setReportType("GST-RETURN");
    ReflectionTestUtils.setField(pending, "id", 89L);
    ReflectionTestUtils.setField(pending, "createdAt", Instant.parse("2026-03-04T00:00:00Z"));

    when(userAccountRepository.findById(11L)).thenReturn(Optional.of(actor));
    when(exportRequestRepository.findByCompanyAndId(company, 89L)).thenReturn(Optional.of(pending));

    var rejected = service.reject(89L, "Missing compliance sign-off");

    assertThat(rejected.status()).isEqualTo(ExportApprovalStatus.REJECTED);
    assertThat(rejected.rejectionReason()).isEqualTo("Missing compliance sign-off");
    assertThat(rejected.approvedBy()).isEqualTo("admin@bbp.com");
  }

  @Test
  void resolveDownload_requiresApprovalWhenGateEnabled() {
    ExportRequest pending = new ExportRequest();
    pending.setCompany(company);
    pending.setUserId(11L);
    pending.setStatus(ExportApprovalStatus.PENDING);
    pending.setReportType("TRIAL-BALANCE");
    ReflectionTestUtils.setField(pending, "id", 90L);

    when(exportRequestRepository.findByCompanyAndId(company, 90L)).thenReturn(Optional.of(pending));
    when(systemSettingsService.isExportApprovalRequired()).thenReturn(true);

    assertThatThrownBy(() -> service.resolveDownload(90L))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS);
            });
  }

  @Test
  void resolveDownload_allowsApprovedRequestWhenGateEnabled() {
    ExportRequest approved = new ExportRequest();
    approved.setCompany(company);
    approved.setUserId(11L);
    approved.setStatus(ExportApprovalStatus.APPROVED);
    approved.setReportType("TRIAL-BALANCE");
    approved.setParameters("periodId=12");
    ReflectionTestUtils.setField(approved, "id", 91L);

    when(exportRequestRepository.findByCompanyAndId(company, 91L))
        .thenReturn(Optional.of(approved));
    when(systemSettingsService.isExportApprovalRequired()).thenReturn(true);

    var response = service.resolveDownload(91L);

    assertThat(response.requestId()).isEqualTo(91L);
    assertThat(response.status()).isEqualTo(ExportApprovalStatus.APPROVED);
    assertThat(response.message()).contains("approved");
  }

  @Test
  void resolveDownload_bypassesGateWhenDisabled() {
    ExportRequest rejected = new ExportRequest();
    rejected.setCompany(company);
    rejected.setUserId(11L);
    rejected.setStatus(ExportApprovalStatus.REJECTED);
    rejected.setReportType("TRIAL-BALANCE");
    ReflectionTestUtils.setField(rejected, "id", 92L);

    when(exportRequestRepository.findByCompanyAndId(company, 92L))
        .thenReturn(Optional.of(rejected));
    when(systemSettingsService.isExportApprovalRequired()).thenReturn(false);

    var response = service.resolveDownload(92L);

    assertThat(response.requestId()).isEqualTo(92L);
    assertThat(response.status()).isEqualTo(ExportApprovalStatus.REJECTED);
    assertThat(response.message()).contains("disabled");
  }
}
