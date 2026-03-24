package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.TenantRuntimeRequestAttributes;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.portal.service.TenantRuntimeEnforcementInterceptor;
import com.bigbrightpaints.erp.modules.sales.controller.DealerPortalController;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("critical")
class TS_RuntimeDealerPortalControllerExportCoverageTest {

    @Test
    void getMyInvoicePdf_emitsDealerExportAuditMetadata() {
        DealerPortalService dealerPortalService = org.mockito.Mockito.mock(DealerPortalService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        DealerPortalController controller = new DealerPortalController(dealerPortalService, auditService);

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
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        DealerPortalController controller = new DealerPortalController(dealerPortalService, auditService);

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
    void tenantRuntimePolicyService_metrics_reflects_canonical_active_reason() {
        CompanyContextService companyContextService = org.mockito.Mockito.mock(CompanyContextService.class);
        UserAccountRepository userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        TenantRuntimeEnforcementService tenantRuntimeEnforcementService =
                org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        TenantRuntimePolicyService service = new TenantRuntimePolicyService(
                companyContextService,
                userAccountRepository,
                auditService,
                tenantRuntimeEnforcementService);
        com.bigbrightpaints.erp.modules.company.domain.Company company =
                org.mockito.Mockito.mock(com.bigbrightpaints.erp.modules.company.domain.Company.class);
        when(company.getId()).thenReturn(42L);
        when(company.getCode()).thenReturn("ACME");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(userAccountRepository.findDistinctByCompanies_Id(42L)).thenReturn(List.of());
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(
                new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                        "POLICY_ACTIVE",
                        "bootstrap",
                        null,
                        200,
                        5000,
                        500,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(0, 0, 0, 0, 0, 0, 0L)));

        assertThat(service.metrics().holdReason()).isEqualTo("POLICY_ACTIVE");
    }

    @Test
    void tenantRuntimeInterceptor_skipsLegacyChecks_whenCanonicalAdmissionAlreadyApplied() throws Exception {
        TenantRuntimeEnforcementService tenantRuntimeEnforcementService =
                org.mockito.Mockito.mock(TenantRuntimeEnforcementService.class);
        TenantRuntimeEnforcementInterceptor interceptor = new TenantRuntimeEnforcementInterceptor(
                org.mockito.Mockito.mock(CompanyContextService.class),
                tenantRuntimeEnforcementService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        request.setAttribute(TenantRuntimeRequestAttributes.CANONICAL_ADMISSION_APPLIED, Boolean.TRUE);

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(tenantRuntimeEnforcementService);
    }
}
