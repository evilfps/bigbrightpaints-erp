package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.invoice.controller.InvoiceController;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("critical")
class TS_RuntimeInvoiceControllerExportCoverageTest {

    @Test
    void downloadInvoicePdf_emitsInvoiceExportAuditMetadata() {
        InvoiceService invoiceService = org.mockito.Mockito.mock(InvoiceService.class);
        InvoicePdfService invoicePdfService = org.mockito.Mockito.mock(InvoicePdfService.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        InvoiceController controller = new InvoiceController(invoiceService, invoicePdfService, emailService, auditService);

        when(invoicePdfService.renderInvoicePdf(42L))
                .thenReturn(new InvoicePdfService.PdfDocument("invoice-42.pdf", "pdf".getBytes()));

        controller.downloadInvoicePdf(42L);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "INVOICE")
                .containsEntry("resourceId", "42")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "invoice-42.pdf");
    }

    @Test
    void downloadInvoicePdf_handlesNullResourceMetadataFailClosed() {
        InvoiceService invoiceService = org.mockito.Mockito.mock(InvoiceService.class);
        InvoicePdfService invoicePdfService = org.mockito.Mockito.mock(InvoicePdfService.class);
        EmailService emailService = org.mockito.Mockito.mock(EmailService.class);
        AuditService auditService = org.mockito.Mockito.mock(AuditService.class);
        InvoiceController controller = new InvoiceController(invoiceService, invoicePdfService, emailService, auditService);

        when(invoicePdfService.renderInvoicePdf(null))
                .thenReturn(new InvoicePdfService.PdfDocument(null, new byte[0]));

        controller.downloadInvoicePdf(null);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "INVOICE")
                .containsEntry("resourceId", "")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "");
    }
}
