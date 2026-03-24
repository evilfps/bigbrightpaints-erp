package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealerPortalControllerExportAuditTest {

    @Mock
    private DealerPortalService dealerPortalService;
    @Mock
    private AuditService auditService;

    private DealerPortalController controller;

    @BeforeEach
    void setup() {
        controller = new DealerPortalController(dealerPortalService, auditService);
    }

    @Test
    void classLevelAccessRemainsDealerOnly() {
        PreAuthorize preAuthorize = DealerPortalController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_DEALER')");
    }

    @Test
    void createCreditRequest_returnsReadOnlyDealerPortalMessage() {
        var response = controller.createCreditRequest();

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Dealer portal is read-only. Ask your sales or admin contact to review credit-limit changes.");
        assertThat(response.getBody().data())
                .containsEntry("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode())
                .containsEntry(
                        "message",
                        "Dealer portal is read-only. Ask your sales or admin contact to review credit-limit changes.")
                .containsEntry("reason", "DEALER_PORTAL_READ_ONLY");
    }

    @Test
    void getMyInvoicePdf_logsDataExportMetadata() {
        long invoiceId = 88L;
        byte[] pdfBytes = "dealer-pdf".getBytes();
        InvoicePdfService.PdfDocument pdf = new InvoicePdfService.PdfDocument("dealer-invoice-88.pdf", pdfBytes);
        when(dealerPortalService.getMyInvoicePdf(invoiceId)).thenReturn(pdf);

        ResponseEntity<byte[]> response = controller.getMyInvoicePdf(invoiceId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("dealer-invoice-88.pdf");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getBody()).isEqualTo(pdfBytes);

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("resourceType", "DEALER_INVOICE")
                .containsEntry("resourceId", "88")
                .containsEntry("operation", "EXPORT")
                .containsEntry("format", "pdf")
                .containsEntry("fileName", "dealer-invoice-88.pdf");
    }
}
