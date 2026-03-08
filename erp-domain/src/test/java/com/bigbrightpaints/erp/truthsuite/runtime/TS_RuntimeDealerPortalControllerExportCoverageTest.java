package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.portal.service.TenantRuntimeEnforcementInterceptor;
import com.bigbrightpaints.erp.modules.sales.controller.DealerPortalController;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
}
