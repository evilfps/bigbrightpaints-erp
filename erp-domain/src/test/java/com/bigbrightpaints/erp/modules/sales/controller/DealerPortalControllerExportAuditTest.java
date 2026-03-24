package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.DealerPortalCreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class DealerPortalControllerExportAuditTest {

    @Mock
    private DealerPortalService dealerPortalService;
    @Mock
    private CreditLimitRequestService creditLimitRequestService;
    @Mock
    private AuditService auditService;

    private DealerPortalController controller;

    @BeforeEach
    void setup() {
        controller = new DealerPortalController(dealerPortalService, creditLimitRequestService, auditService);
    }

    @Test
    void classLevelAccessRemainsDealerOnly() {
        PreAuthorize preAuthorize = DealerPortalController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_DEALER')");
    }

    @Test
    void createCreditLimitRequest_scopesSubmissionToAuthenticatedDealer() {
        Dealer dealer = new Dealer();
        dealer.setName("Dealer One");
        org.springframework.test.util.ReflectionTestUtils.setField(dealer, "id", 19L);
        DealerPortalService.RequesterIdentity requesterIdentity =
                new DealerPortalService.RequesterIdentity(4401L, "dealer.user@bbp.com");
        CreditLimitRequestDto created = new CreditLimitRequestDto(
                501L,
                UUID.randomUUID(),
                "Dealer One",
                new BigDecimal("1500"),
                "PENDING",
                "Seasonal growth",
                Instant.parse("2026-03-23T10:15:30Z")
        );

        when(dealerPortalService.getCurrentDealer()).thenReturn(dealer);
        when(dealerPortalService.getCurrentRequesterIdentity()).thenReturn(requesterIdentity);
        when(creditLimitRequestService.createRequest(any(), eq(4401L), eq("dealer.user@bbp.com"))).thenReturn(created);

        var response = controller.createCreditLimitRequest(
                new DealerPortalCreditLimitRequestCreateRequest(new BigDecimal("1500"), "Seasonal growth"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Credit limit request submitted");
        assertThat(response.getBody().data()).isEqualTo(created);

        ArgumentCaptor<com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest.class);
        verify(creditLimitRequestService).createRequest(requestCaptor.capture(), eq(4401L), eq("dealer.user@bbp.com"));
        assertThat(requestCaptor.getValue().dealerId()).isEqualTo(19L);
        assertThat(requestCaptor.getValue().amountRequested()).isEqualByComparingTo("1500");
        assertThat(requestCaptor.getValue().reason()).isEqualTo("Seasonal growth");
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
