package com.bigbrightpaints.erp.modules.invoice.controller;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/invoices")
@PreAuthorize(PortalRoleActionMatrix.ADMIN_SALES_ACCOUNTING)
public class InvoiceController {

  private final InvoiceService invoiceService;
  private final InvoicePdfService invoicePdfService;
  private final EmailService emailService;
  private final AuditService auditService;

  public InvoiceController(
      InvoiceService invoiceService,
      InvoicePdfService invoicePdfService,
      EmailService emailService,
      AuditService auditService) {
    this.invoiceService = invoiceService;
    this.invoicePdfService = invoicePdfService;
    this.emailService = emailService;
    this.auditService = auditService;
  }

  @GetMapping
  @Timed(value = "erp.invoices.list", description = "List invoices")
  public ResponseEntity<ApiResponse<List<InvoiceDto>>> listInvoices(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
    return ResponseEntity.ok(ApiResponse.success(invoiceService.listInvoices(page, size)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<InvoiceDto>> getInvoice(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(invoiceService.getInvoice(id)));
  }

  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(summary = "Download invoice PDF")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "PDF document",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PDF_VALUE,
              schema = @Schema(type = "string", format = "binary")))
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_ONLY)
  public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable Long id) {
    InvoicePdfService.PdfDocument pdf = invoicePdfService.renderInvoicePdf(id);
    logInvoiceExport(id, pdf.fileName(), "pdf");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf.content());
  }

  @PostMapping("/{id}/email")
  public ResponseEntity<ApiResponse<String>> sendInvoiceEmail(@PathVariable Long id) {
    // Get invoice with dealer email from service
    var invoiceWithEmail = invoiceService.getInvoiceWithDealerEmail(id);
    InvoiceDto invoice = invoiceWithEmail.invoice();
    String dealerEmail = invoiceWithEmail.dealerEmail();
    String companyName = invoiceWithEmail.companyName();

    if (dealerEmail == null || dealerEmail.isBlank()) {
      return ResponseEntity.badRequest()
          .body(
              ApiResponse.failure(
                  "Dealer email not configured for invoice " + invoice.invoiceNumber()));
    }

    InvoicePdfService.PdfDocument pdf = invoicePdfService.renderInvoicePdf(id);

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");
    String invoiceDate = invoice.issueDate() != null ? invoice.issueDate().format(formatter) : "";
    String dueDate = invoice.dueDate() != null ? invoice.dueDate().format(formatter) : "";
    String totalAmount =
        String.format(
            "₹%.2f", invoice.totalAmount() != null ? invoice.totalAmount().doubleValue() : 0);

    emailService.sendInvoiceEmail(
        dealerEmail,
        invoice.dealerName(),
        invoice.invoiceNumber(),
        invoiceDate,
        dueDate,
        totalAmount,
        companyName,
        pdf.content());

    return ResponseEntity.ok(ApiResponse.success("Invoice email sent to " + dealerEmail));
  }

  private void logInvoiceExport(Long invoiceId, String fileName, String format) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "INVOICE");
    metadata.put("resourceId", invoiceId != null ? invoiceId.toString() : "");
    metadata.put("operation", "EXPORT");
    metadata.put("format", format);
    metadata.put("fileName", fileName != null ? fileName : "");
    auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);
  }
}
