package com.bigbrightpaints.erp.modules.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.TenantRuntimeRequestAttributes;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementInterceptorTest {

    @Mock
    private CompanyContextService companyContextService;

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private TenantRuntimeEnforcementInterceptor interceptor;

    @Test
    void preHandle_bypassesWhenPathIsNotEnforced() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/settings");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(companyContextService, tenantRuntimeEnforcementService);
    }

    @Test
    void preHandle_bypassesWhenPathIsBlank() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("   ");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(companyContextService, tenantRuntimeEnforcementService);
    }

    @Test
    void preHandle_skipsLegacyPortalRuntimeChecks_whenCanonicalAdmissionAlreadyApplied() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        request.setAttribute(TenantRuntimeRequestAttributes.CANONICAL_ADMISSION_APPLIED, Boolean.TRUE);

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(companyContextService, tenantRuntimeEnforcementService);
    }

    @Test
    void preHandle_usesCanonicalRuntimeServiceWhenFilterDidNotTrackRequest() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission admission = admission(true, "ACME", 200, null,
                null, null, null, null, null);
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/portal/dashboard"),
                eq("GET"),
                eq(null),
                eq(false))).thenReturn(admission);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(TenantRuntimeRequestAttributes.INTERCEPTOR_FALLBACK_ADMISSION))
                .isSameAs(admission);
        verify(tenantRuntimeEnforcementService).completeRequest(admission, 200);
    }

    @Test
    void preHandle_passesTrimmedAuthenticatedActorToFallbackAdmission() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission admission = admission(true, "ACME", 200, null,
                null, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("  actor@bbp.com  ", "ignored"));
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/portal/dashboard"),
                eq("GET"),
                eq("actor@bbp.com"),
                eq(false))).thenReturn(admission);

        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

            boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

            assertThat(allowed).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void preHandle_usesNullActorWhenAuthenticationNameIsBlank() throws Exception {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission admission = admission(true, "ACME", 200, null,
                null, null, null, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("   ", "ignored"));
        when(tenantRuntimeEnforcementService.beginRequest(
                eq("ACME"),
                eq("/api/v1/portal/dashboard"),
                eq("GET"),
                eq(null),
                eq(false))).thenReturn(admission);

        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

            boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

            assertThat(allowed).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void preHandle_translatesNullAdmissionIntoUnavailablePortalContract() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(tenantRuntimeEnforcementService.beginRequest(any(), any(), any(), any(), eq(false))).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getMessage()).contains("Tenant runtime admission is unavailable");
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("path", "/api/v1/portal/dashboard");
                });
        verify(tenantRuntimeEnforcementService, never()).snapshot("ACME");
    }

    @Test
    void preHandle_translatesCanonicalStateRejectionIntoPortalContract() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                403,
                "Tenant is currently blocked",
                "TENANT_BLOCKED",
                "SECURITY_REVIEW",
                null,
                null,
                null,
                "policy-blocked");
        when(tenantRuntimeEnforcementService.beginRequest(any(), any(), any(), any(), eq(false))).thenReturn(rejected);
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot(
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "SECURITY_REVIEW",
                "policy-blocked",
                Instant.parse("2026-02-20T10:16:05Z"),
                500,
                5000,
                200,
                0,
                0,
                0,
                0L));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("holdState", "BLOCKED")
                            .containsEntry("holdReason", "SECURITY_REVIEW")
                            .containsEntry("policyReference", "policy-blocked")
                            .containsEntry("path", "/api/v1/portal/dashboard");
                });
    }

    @Test
    void preHandle_treatsTenantStateLimitTypeAsInvalidStateContract() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                423,
                "Tenant is currently on hold",
                "TENANT_ON_HOLD",
                "MAINTENANCE_WINDOW",
                "TENANT_STATE",
                "HOLD",
                "ACTIVE",
                "policy-hold");
        when(tenantRuntimeEnforcementService.beginRequest(any(), any(), any(), any(), eq(false))).thenReturn(rejected);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/portal/orders");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("holdState", "HOLD")
                            .containsEntry("holdReason", "MAINTENANCE_WINDOW")
                            .containsEntry("policyReference", "policy-hold")
                            .containsEntry("path", "/api/v1/portal/orders");
                });
        verify(tenantRuntimeEnforcementService, never()).snapshot("ACME");
    }

    @Test
    void preHandle_translatesQuotaRejectionIntoPortalContract_whenQuotaValuesAreBlankOrInvalid() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                429,
                "Tenant request rate quota exceeded",
                "TENANT_REQUEST_RATE_EXCEEDED",
                "POLICY_ACTIVE",
                "MAX_REQUESTS_PER_MINUTE",
                "   ",
                "not-a-number",
                "policy-rpm");
        when(tenantRuntimeEnforcementService.beginRequest(any(), any(), any(), any(), eq(false))).thenReturn(rejected);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/inventory");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED);
                    assertThat(exception.getDetails())
                            .containsEntry("quotaValue", 0)
                            .containsEntry("observed", 0);
                });
        verify(tenantRuntimeEnforcementService, never()).snapshot("ACME");
    }

    @Test
    void preHandle_translatesCanonicalQuotaRejectionIntoPortalContract() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        Company company = company(55L, "ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                429,
                "Tenant request rate quota exceeded",
                "TENANT_REQUEST_RATE_EXCEEDED",
                "POLICY_ACTIVE",
                "MAX_REQUESTS_PER_MINUTE",
                "2",
                "1",
                "policy-rpm");
        when(tenantRuntimeEnforcementService.beginRequest(any(), any(), any(), any(), eq(false))).thenReturn(rejected);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/inventory");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("quotaType", "MAX_REQUESTS_PER_MINUTE")
                            .containsEntry("quotaValue", 1)
                            .containsEntry("observed", 2)
                            .containsEntry("policyReference", "policy-rpm")
                            .containsEntry("path", "/api/v1/reports/inventory");
                });
        verify(tenantRuntimeEnforcementService, never()).snapshot("ACME");
    }

    @Test
    void admissionException_treatsBlankCompanyAndPathAsNulls() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "   ",
                "   ",
                null);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", null)
                .containsEntry("path", null);
        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void admissionException_usesAdmissionCompanyAndStateDetailsWithoutSnapshotLookup() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                403,
                "Tenant is currently blocked",
                "TENANT_BLOCKED",
                "SECURITY_REVIEW",
                "TENANT_STATE",
                "BLOCKED",
                "ACTIVE");

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "   ",
                "/api/v1/portal/orders",
                rejected);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", "ACME")
                .containsEntry("holdState", "BLOCKED")
                .containsEntry("holdReason", "SECURITY_REVIEW")
                .containsEntry("policyReference", "chain-id")
                .containsEntry("path", "/api/v1/portal/orders");
        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void admissionException_leavesFallbackStateDetailsNullWhenAdmissionMetadataIsBlank() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "   ",
                423,
                "Tenant is currently on hold",
                "TENANT_ON_HOLD",
                "   ",
                "TENANT_STATE",
                "   ",
                "ACTIVE",
                "   ");

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "   ",
                "/api/v1/portal/orders",
                rejected);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", null)
                .containsEntry("holdState", null)
                .containsEntry("holdReason", null)
                .containsEntry("policyReference", null)
                .containsEntry("path", "/api/v1/portal/orders");
        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void admissionException_usesSnapshotReasonWhenAdmissionReasonIsMissing() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                423,
                "Tenant is currently on hold",
                "TENANT_ON_HOLD",
                "   ",
                "TENANT_STATE",
                "HOLD",
                "ACTIVE");
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot(
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "MAINTENANCE_WINDOW",
                "policy-hold",
                Instant.parse("2026-02-20T10:16:05Z"),
                500,
                5000,
                200,
                0,
                1,
                0,
                0L));

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "ACME",
                "/api/v1/portal/orders",
                rejected);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", "ACME")
                .containsEntry("holdState", "HOLD")
                .containsEntry("holdReason", "MAINTENANCE_WINDOW")
                .containsEntry("policyReference", "chain-id")
                .containsEntry("path", "/api/v1/portal/orders");
        verify(tenantRuntimeEnforcementService).snapshot("ACME");
    }

    @Test
    void admissionException_prefersAdmissionMetadataOverChangedSnapshot() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                423,
                "Tenant is currently on hold",
                "TENANT_ON_HOLD",
                "MAINTENANCE_WINDOW",
                "TENANT_STATE",
                "HOLD",
                "ACTIVE",
                "policy-denied");

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "ACME",
                "/api/v1/portal/orders",
                rejected);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", "ACME")
                .containsEntry("holdState", "HOLD")
                .containsEntry("holdReason", "MAINTENANCE_WINDOW")
                .containsEntry("policyReference", "policy-denied")
                .containsEntry("path", "/api/v1/portal/orders");
        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void admissionException_degradesToNullFallbackStateDetailsWhenSnapshotLookupFails() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        TenantRuntimeEnforcementService.TenantRequestAdmission rejected = admission(
                false,
                "ACME",
                423,
                "Tenant is currently on hold",
                "TENANT_ON_HOLD",
                "   ",
                "TENANT_STATE",
                "HOLD",
                "ACTIVE",
                "   ");
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenThrow(new IllegalStateException("db offline"));

        RuntimeException exception = ReflectionTestUtils.invokeMethod(
                interceptor,
                "admissionException",
                "ACME",
                "/api/v1/portal/orders",
                rejected);

        assertThat(exception).isInstanceOf(ApplicationException.class);
        ApplicationException applicationException = (ApplicationException) exception;
        assertThat(applicationException.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
        assertThat(applicationException.getDetails())
                .containsEntry("companyCode", "ACME")
                .containsEntry("holdState", "HOLD")
                .containsEntry("holdReason", null)
                .containsEntry("policyReference", null)
                .containsEntry("path", "/api/v1/portal/orders");
        verify(tenantRuntimeEnforcementService).snapshot("ACME");
    }

    @Test
    void afterCompletion_ignoresRequestsWithoutInterceptorFallbackAdmission() {
        interceptor = new TenantRuntimeEnforcementInterceptor(companyContextService, tenantRuntimeEnforcementService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(tenantRuntimeEnforcementService, never()).completeRequest(any(), eq(200));
    }

    private Company company(Long id, String code) {
        Company company = new Company();
        org.springframework.test.util.ReflectionTestUtils.setField(company, "id", id);
        company.setCode(code);
        return company;
    }

    private TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot(
            TenantRuntimeEnforcementService.TenantRuntimeState state,
            String reasonCode,
            String auditChainId,
            Instant updatedAt,
            int maxActiveUsers,
            int maxRequestsPerMinute,
            int maxConcurrentRequests,
            int totalRequests,
            int rejectedRequests,
            int inFlightRequests,
            long activeUsers) {
        return new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                state,
                reasonCode,
                auditChainId,
                updatedAt,
                maxConcurrentRequests,
                maxRequestsPerMinute,
                maxActiveUsers,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(
                        totalRequests,
                        rejectedRequests,
                        0,
                        inFlightRequests,
                        totalRequests,
                        rejectedRequests,
                        activeUsers));
    }

    @SuppressWarnings("unchecked")
    private TenantRuntimeEnforcementService.TenantRequestAdmission admission(boolean admitted,
                                                                             String companyCode,
                                                                             int statusCode,
                                                                             String message,
                                                                             String reasonCode,
                                                                             String tenantReasonCode,
                                                                             String limitType,
                                                                             String observedValue,
                                                                             String limitValue) {
        return admission(
                admitted,
                companyCode,
                statusCode,
                message,
                reasonCode,
                tenantReasonCode,
                limitType,
                observedValue,
                limitValue,
                "chain-id");
    }

    @SuppressWarnings("unchecked")
    private TenantRuntimeEnforcementService.TenantRequestAdmission admission(boolean admitted,
                                                                             String companyCode,
                                                                             int statusCode,
                                                                             String message,
                                                                             String reasonCode,
                                                                             String tenantReasonCode,
                                                                             String limitType,
                                                                             String observedValue,
                                                                             String limitValue,
                                                                             String auditChainId) {
        try {
            Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission> ctor =
                    (Constructor<TenantRuntimeEnforcementService.TenantRequestAdmission>) Arrays.stream(
                                    TenantRuntimeEnforcementService.TenantRequestAdmission.class.getDeclaredConstructors())
                            .filter(candidate -> candidate.getParameterCount() == 12)
                            .findFirst()
                            .orElseThrow();
            ctor.setAccessible(true);
            return ctor.newInstance(
                    admitted,
                    companyCode,
                    auditChainId,
                    null,
                    statusCode,
                    message,
                    false,
                    reasonCode,
                    tenantReasonCode,
                    limitType,
                    observedValue,
                    limitValue);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct tenant request admission for test", ex);
        }
    }
}
