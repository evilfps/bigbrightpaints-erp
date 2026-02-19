package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.controller.DealerPortalController;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}
