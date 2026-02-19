package com.bigbrightpaints.erp.modules.invoice.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceControllerExportGovernanceTest {

    @Mock
    private InvoiceService invoiceService;
    @Mock
    private InvoicePdfService invoicePdfService;
    @Mock
    private EmailService emailService;
    @Mock
    private AuditService auditService;

    private InvoiceController controller;

    @BeforeEach
    void setup() {
        controller = new InvoiceController(invoiceService, invoicePdfService, emailService, auditService);
    }

    @Test
    void downloadInvoicePdf_hasAdminOnlyPreAuthorize() throws Exception {
        Method method = InvoiceController.class.getMethod("downloadInvoicePdf", Long.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }

    @Test
    void downloadInvoicePdf_logsDataExportMetadata() {
        long invoiceId = 42L;
        byte[] payload = "pdf-bytes".getBytes();
        InvoicePdfService.PdfDocument pdf = new InvoicePdfService.PdfDocument("invoice-42.pdf", payload);
        when(invoicePdfService.renderInvoicePdf(invoiceId)).thenReturn(pdf);

        ResponseEntity<byte[]> response = controller.downloadInvoicePdf(invoiceId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("invoice-42.pdf");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getBody()).isEqualTo(payload);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        Map<String, String> metadata = metadataCaptor.getValue();
        assertThat(metadata)
                .containsEntry("resourceType", "INVOICE")
                .containsEntry("resourceId", "42")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "invoice-42.pdf");
    }
}
