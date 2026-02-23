package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimePolicyUpdateRequest;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.portal.service.TenantRuntimeEnforcementInterceptor;
import com.bigbrightpaints.erp.modules.sales.controller.DealerPortalController;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
class TS_RuntimeDealerPortalControllerExportCoverageTest {

    @Test
    void getMyInvoicePdf_emitsDealerExportAuditMetadata() {
        DealerPortalService dealerPortalService = org.mockito.Mockito.mock(DealerPortalService.class);
        SalesService salesService = org.mockito.Mockito.mock(SalesService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        DealerPortalController controller = new DealerPortalController(dealerPortalService, salesService, auditService);

        when(dealerPortalService.getMyInvoicePdf(88L)).thenReturn(
                new InvoicePdfService.PdfDocument("dealer-invoice-88.pdf", "pdf".getBytes()));

        controller.getMyInvoicePdf(88L);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "DEALER_INVOICE")
                .containsEntry("resourceId", "88")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "dealer-invoice-88.pdf");
    }

    @Test
    void getMyInvoicePdf_handlesNullResourceMetadataFailClosed() {
        DealerPortalService dealerPortalService = org.mockito.Mockito.mock(DealerPortalService.class);
        SalesService salesService = org.mockito.Mockito.mock(SalesService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        DealerPortalController controller = new DealerPortalController(dealerPortalService, salesService, auditService);

        when(dealerPortalService.getMyInvoicePdf(null)).thenReturn(
                new InvoicePdfService.PdfDocument(null, new byte[0]));

        controller.getMyInvoicePdf(null);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "DEALER_INVOICE")
                .containsEntry("resourceId", "")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "");
    }

    @Test
    void tenantRuntimePolicyService_normalizeHoldState_failClosed_forMalformedValues() {
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                org.mockito.Mockito.mock(CompanyContextService.class),
                org.mockito.Mockito.mock(SystemSettingsRepository.class),
                org.mockito.Mockito.mock(UserAccountRepository.class),
                org.mockito.Mockito.mock(AuditService.class));

        String normalizedNull = (String) ReflectionTestUtils.invokeMethod(service, "normalizeHoldState", (String) null);
        String normalizedActive = (String) ReflectionTestUtils.invokeMethod(service, "normalizeHoldState", "ACTIVE");
        String normalizedPaused = (String) ReflectionTestUtils.invokeMethod(service, "normalizeHoldState", "PAUSED");

        assertThat(normalizedNull).isEqualTo("ACTIVE");
        assertThat(normalizedActive).isEqualTo("ACTIVE");
        assertThat(normalizedPaused).isEqualTo("BLOCKED");
    }

    @Test
    void tenantRuntimePolicyService_metrics_delegatesToRuntimeSnapshot() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false), user(true)));
        when(runtimeService.snapshot("ACME")).thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "INCIDENT_CONTAINMENT",
                "audit-chain-1",
                Instant.parse("2026-02-20T10:15:30Z"),
                30,
                900,
                120,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(71, 8, 2, 3, 11, 8, 2)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("HOLD");
        assertThat(metrics.holdReason()).isEqualTo("INCIDENT_CONTAINMENT");
        assertThat(metrics.maxConcurrentRequests()).isEqualTo(30);
        assertThat(metrics.maxRequestsPerMinute()).isEqualTo(900);
        assertThat(metrics.maxActiveUsers()).isEqualTo(120);
        assertThat(metrics.enabledUsers()).isEqualTo(2);
        assertThat(metrics.totalUsers()).isEqualTo(3);
        assertThat(metrics.policyReference()).isEqualTo("audit-chain-1");
    }

    @Test
    void tenantRuntimePolicyService_metrics_activeStateClearsHoldReason_andNormalizesNegativeActiveUsers() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true), user(false)));
        when(runtimeService.snapshot("ACME")).thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "SHOULD_CLEAR",
                "audit-chain-active",
                Instant.parse("2026-02-20T10:15:30Z"),
                40,
                1200,
                250,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(4, 0, 0, 1, 2, 0, -7)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("ACTIVE");
        assertThat(metrics.holdReason()).isNull();
        assertThat(metrics.enabledUsers()).isZero();
    }

    @Test
    void tenantRuntimePolicyService_updatePolicy_delegatesToRuntimeService_andNormalizesTargetState() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false)));
        when(runtimeService.snapshot("ACME"))
                .thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                        "POLICY_ACTIVE",
                        "audit-chain-before",
                        Instant.parse("2026-02-20T10:14:30Z"),
                        40,
                        1200,
                        250,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(10, 1, 0, 0, 4, 1, 1)));
        when(runtimeService.updatePolicy(
                eq("ACME"),
                eq(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED),
                eq("Manual override"),
                eq(21),
                eq(800),
                eq(55),
                eq(null)))
                .thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                        "Manual override",
                        "audit-chain-after",
                        Instant.parse("2026-02-20T10:15:30Z"),
                        21,
                        800,
                        55,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(10, 1, 0, 0, 4, 1, 1)));

        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                55,
                800,
                21,
                "PAUSED",
                "Manual override",
                "Controlled rollout");

        TenantRuntimeMetricsDto updated = service.updatePolicy(request);

        assertThat(updated.holdState()).isEqualTo("BLOCKED");
        assertThat(updated.maxConcurrentRequests()).isEqualTo(21);
        assertThat(updated.maxRequestsPerMinute()).isEqualTo(800);
        assertThat(updated.maxActiveUsers()).isEqualTo(55);
        verify(runtimeService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "Manual override",
                21,
                800,
                55,
                null);
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("oldHoldState", "ACTIVE")
                .containsEntry("newHoldState", "BLOCKED")
                .containsEntry("oldMaxActiveUsers", "250")
                .containsEntry("newMaxActiveUsers", "55")
                .containsEntry("oldMaxRequestsPerMinute", "1200")
                .containsEntry("newMaxRequestsPerMinute", "800")
                .containsEntry("oldMaxConcurrentRequests", "40")
                .containsEntry("newMaxConcurrentRequests", "21")
                .containsEntry("policyReference", "audit-chain-after")
                .containsEntry("holdReason", "Manual override")
                .containsEntry("changeReason", "Controlled rollout")
                .containsKeys("requestId", "traceId", "ipAddress", "userAgent");
    }

    @Test
    void tenantRuntimePolicyService_updatePolicy_handlesNullRequestByDelegatingNullMutationPayload() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true)));
        when(runtimeService.snapshot("ACME"))
                .thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                        "POLICY_ACTIVE",
                        "audit-chain-before-null",
                        Instant.parse("2026-02-20T10:14:30Z"),
                        40,
                        1200,
                        250,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(5, 0, 0, 0, 2, 0, 1)));
        when(runtimeService.updatePolicy(
                eq("ACME"),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null)))
                .thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                        "POLICY_ACTIVE",
                        "audit-chain-after-null",
                        Instant.parse("2026-02-20T10:15:30Z"),
                        40,
                        1200,
                        250,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(5, 0, 0, 0, 2, 0, 1)));

        TenantRuntimeMetricsDto updated = service.updatePolicy(null);

        assertThat(updated.holdState()).isEqualTo("ACTIVE");
        assertThat(updated.holdReason()).isNull();
        verify(runtimeService).updatePolicy("ACME", null, null, null, null, null, null);
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("oldHoldState", "ACTIVE")
                .containsEntry("newHoldState", "ACTIVE")
                .containsEntry("oldMaxActiveUsers", "250")
                .containsEntry("newMaxActiveUsers", "250")
                .containsEntry("oldMaxRequestsPerMinute", "1200")
                .containsEntry("newMaxRequestsPerMinute", "1200")
                .containsEntry("oldMaxConcurrentRequests", "40")
                .containsEntry("newMaxConcurrentRequests", "40")
                .containsEntry("policyReference", "audit-chain-after-null")
                .containsEntry("holdReason", "")
                .containsEntry("changeReason", "")
                .containsKeys("requestId", "traceId", "ipAddress", "userAgent");
    }

    @Test
    void tenantRuntimePolicyService_assertCanAddEnabledUser_usesRuntimeSnapshotQuota() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(runtimeService.snapshot("ACME")).thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "POLICY_ACTIVE",
                "audit-chain-q",
                Instant.parse("2026-02-20T10:15:30Z"),
                40,
                1200,
                1,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(3, 0, 0, 0, 1, 0, 1)));

        assertThatThrownBy(() -> service.assertCanAddEnabledUser(company, "ENABLE_USER"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED));
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("operation", "ENABLE_USER")
                .containsEntry("policyReference", "audit-chain-q")
                .containsEntry("enabledUsers", "1")
                .containsEntry("maxActiveUsers", "1")
                .containsEntry("reason", "Active user quota exceeded for tenant ACME")
                .containsKeys("requestId", "traceId", "ipAddress", "userAgent");
    }

    @Test
    void tenantRuntimePolicyService_assertCanAddEnabledUser_allowsWhenRuntimeActiveUsersBelowQuota() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(runtimeService.snapshot("ACME")).thenReturn(new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "POLICY_ACTIVE",
                "audit-chain-allow",
                Instant.parse("2026-02-20T10:15:30Z"),
                40,
                1200,
                5,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(3, 0, 0, 0, 1, 0, 2)));

        service.assertCanAddEnabledUser(company, "ENABLE_USER");

        verify(auditService, org.mockito.Mockito.never())
                .logFailure(eq(AuditEvent.ACCESS_DENIED), org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }

    @Test
    void tenantRuntimePolicyService_metrics_fallsBackToSettingsWhenRuntimeServiceMissing() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService);
        Map<String, String> settings = wireSettingsRepository(settingsRepository);

        Company company = company(42L, "ACME");
        settings.put("tenant.runtime.hold-state.42", "HOLD");
        settings.put("tenant.runtime.hold-reason.42", "manual review");
        settings.put("tenant.runtime.max-active-users.42", "11");
        settings.put("tenant.runtime.max-requests-per-minute.42", "500");
        settings.put("tenant.runtime.max-concurrent-requests.42", "9");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true), user(false)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("HOLD");
        assertThat(metrics.holdReason()).isEqualTo("manual review");
        assertThat(metrics.maxActiveUsers()).isEqualTo(11);
        assertThat(metrics.maxRequestsPerMinute()).isEqualTo(500);
        assertThat(metrics.maxConcurrentRequests()).isEqualTo(9);
    }

    @Test
    void tenantRuntimePolicyService_updatePolicy_fallsBackToSettingsWhenRuntimeServiceMissing() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService);
        Map<String, String> settings = wireSettingsRepository(settingsRepository);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true)));

        TenantRuntimeMetricsDto updated = service.updatePolicy(
                new TenantRuntimePolicyUpdateRequest(19, 750, 13, "ACTIVE", null, "fallback update"));

        assertThat(updated.holdState()).isEqualTo("ACTIVE");
        assertThat(settings.get("tenant.runtime.max-active-users.42")).isEqualTo("19");
        assertThat(settings.get("tenant.runtime.max-requests-per-minute.42")).isEqualTo("750");
        assertThat(settings.get("tenant.runtime.max-concurrent-requests.42")).isEqualTo("13");
    }

    @Test
    void tenantRuntimePolicyService_assertCanAddEnabledUser_fallsBackToSettingsWhenRuntimeServiceMissing() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService);
        Map<String, String> settings = wireSettingsRepository(settingsRepository);

        Company company = company(42L, "ACME");
        settings.put("tenant.runtime.max-active-users.42", "3");
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true), user(false)));

        service.assertCanAddEnabledUser(company, "ENABLE_USER");

        verify(auditService, org.mockito.Mockito.never())
                .logFailure(eq(AuditEvent.ACCESS_DENIED), org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }

    @Test
    void tenantRuntimePolicyService_updatePolicy_mapsExplicitTargetStatesViaPublicApi() {
        CompanyContextService contextService = org.mockito.Mockito.mock(CompanyContextService.class);
        SystemSettingsRepository settingsRepository = org.mockito.Mockito.mock(SystemSettingsRepository.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimeEnforcementService runtimeService = org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                contextService, settingsRepository, userAccountRepository, auditService, runtimeService);

        Company company = company(42L, "ACME");
        when(contextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of(user(true), user(false)));
        when(runtimeService.snapshot("ACME")).thenReturn(
                runtimeSnapshot(
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE, "POLICY_ACTIVE", "audit-chain-explicit"));
        when(runtimeService.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "back online",
                11,
                600,
                22,
                null))
                .thenReturn(runtimeSnapshot(
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE, "POLICY_ACTIVE", "audit-chain-active"));
        when(runtimeService.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "throttle",
                12,
                700,
                23,
                null))
                .thenReturn(runtimeSnapshot(
                        TenantRuntimeEnforcementService.TenantRuntimeState.HOLD, "throttle", "audit-chain-hold"));
        when(runtimeService.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "manual block",
                13,
                800,
                24,
                null))
                .thenReturn(runtimeSnapshot(
                        TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED, "manual block", "audit-chain-block"));

        TenantRuntimeMetricsDto active = service.updatePolicy(
                new TenantRuntimePolicyUpdateRequest(22, 600, 11, "ACTIVE", "back online", "resume"));
        TenantRuntimeMetricsDto hold = service.updatePolicy(
                new TenantRuntimePolicyUpdateRequest(23, 700, 12, "HOLD", "throttle", "stabilize"));
        TenantRuntimeMetricsDto blocked = service.updatePolicy(
                new TenantRuntimePolicyUpdateRequest(24, 800, 13, "BLOCKED", "manual block", "contain"));

        assertThat(active.holdState()).isEqualTo("ACTIVE");
        assertThat(hold.holdState()).isEqualTo("HOLD");
        assertThat(blocked.holdState()).isEqualTo("BLOCKED");
        verify(runtimeService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "back online",
                11,
                600,
                22,
                null);
        verify(runtimeService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "throttle",
                12,
                700,
                23,
                null);
        verify(runtimeService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "manual block",
                13,
                800,
                24,
                null);
    }

    @Test
    void tenantRuntimeInterceptor_normalizeHoldState_failClosed_forMalformedValues() {
        TenantRuntimeEnforcementInterceptor interceptor = new TenantRuntimeEnforcementInterceptor(
                org.mockito.Mockito.mock(CompanyContextService.class),
                org.mockito.Mockito.mock(SystemSettingsRepository.class),
                org.mockito.Mockito.mock(AuditService.class));

        String normalizedNull =
                (String) ReflectionTestUtils.invokeMethod(interceptor, "normalizeHoldState", (String) null);
        String normalizedActive =
                (String) ReflectionTestUtils.invokeMethod(interceptor, "normalizeHoldState", "ACTIVE");
        String normalizedPaused =
                (String) ReflectionTestUtils.invokeMethod(interceptor, "normalizeHoldState", "PAUSED");

        assertThat(normalizedNull).isEqualTo("ACTIVE");
        assertThat(normalizedActive).isEqualTo("ACTIVE");
        assertThat(normalizedPaused).isEqualTo("BLOCKED");
    }

    private Company company(Long id, String code) {
        Company company = org.mockito.Mockito.mock(Company.class);
        when(company.getId()).thenReturn(id);
        when(company.getCode()).thenReturn(code);
        return company;
    }

    private UserAccount user(boolean enabled) {
        UserAccount account = new UserAccount(enabled ? "enabled@bbp.com" : "disabled@bbp.com", "hash", "User");
        account.setEnabled(enabled);
        return account;
    }

    private TenantRuntimeEnforcementService.TenantRuntimeSnapshot runtimeSnapshot(
            TenantRuntimeEnforcementService.TenantRuntimeState state,
            String reasonCode,
            String auditChainId) {
        return new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                state,
                reasonCode,
                auditChainId,
                Instant.parse("2026-02-20T10:15:30Z"),
                40,
                1200,
                250,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(10, 1, 0, 0, 4, 1, 1));
    }

    private Map<String, String> wireSettingsRepository(SystemSettingsRepository repository) {
        Map<String, String> settings = new HashMap<>();
        when(repository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0, String.class);
                    String value = settings.get(key);
                    return value == null ? Optional.empty() : Optional.of(new SystemSetting(key, value));
                });
        when(repository.save(org.mockito.ArgumentMatchers.any(SystemSetting.class)))
                .thenAnswer(invocation -> {
                    SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
                    settings.put(setting.getKey(), setting.getValue());
                    return setting;
                });
        return settings;
    }
}
