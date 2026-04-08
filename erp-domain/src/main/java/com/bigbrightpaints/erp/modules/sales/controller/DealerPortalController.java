package com.bigbrightpaints.erp.modules.sales.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.invoice.service.InvoicePdfService;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import com.bigbrightpaints.erp.modules.sales.dto.DealerPortalCreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

/**
 * Dealer Portal API - endpoints for authenticated dealer users to view their own data.
 * All endpoints require ROLE_DEALER and automatically scope data to the logged-in dealer.
 */
@RestController
@RequestMapping("/api/v1/dealer-portal")
@PreAuthorize(PortalRoleActionMatrix.DEALER_ONLY)
public class DealerPortalController {

  private final DealerPortalService dealerPortalService;
  private final CreditLimitRequestService creditLimitRequestService;
  private final AuditService auditService;

  public DealerPortalController(
      DealerPortalService dealerPortalService,
      CreditLimitRequestService creditLimitRequestService,
      AuditService auditService) {
    this.dealerPortalService = dealerPortalService;
    this.creditLimitRequestService = creditLimitRequestService;
    this.auditService = auditService;
  }

  /**
   * Get dealer dashboard with summary of balance, credit, aging.
   */
  @GetMapping("/dashboard")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
    Map<String, Object> dashboard = dealerPortalService.getMyDashboard();
    return ResponseEntity.ok(ApiResponse.success("Dealer dashboard", dashboard));
  }

  /**
   * Get complete ledger with all transactions and running balance.
   */
  @GetMapping("/ledger")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getMyLedger() {
    Map<String, Object> ledger = dealerPortalService.getMyLedger();
    return ResponseEntity.ok(ApiResponse.success("Your ledger", ledger));
  }

  /**
   * Get all invoices with status and outstanding amounts.
   */
  @GetMapping("/invoices")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getMyInvoices() {
    Map<String, Object> invoices = dealerPortalService.getMyInvoices();
    return ResponseEntity.ok(ApiResponse.success("Your invoices", invoices));
  }

  /**
   * Get outstanding balance with aging buckets (current, 1-30, 31-60, 61-90, 90+).
   */
  @GetMapping("/aging")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getMyAging() {
    Map<String, Object> aging = dealerPortalService.getMyOutstandingAndAging();
    return ResponseEntity.ok(ApiResponse.success("Outstanding & aging", aging));
  }

  /**
   * Get all orders for the authenticated dealer.
   */
  @GetMapping("/orders")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getMyOrders() {
    Map<String, Object> orders = dealerPortalService.getMyOrders();
    return ResponseEntity.ok(ApiResponse.success("Your orders", orders));
  }

  /**
   * Submit a dealer-scoped credit-limit increase request.
   */
  @PostMapping("/credit-limit-requests")
  @Operation(summary = "Submit dealer credit-limit request")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Credit-limit request submitted"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Invalid credit-limit request payload"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden")
  })
  public ResponseEntity<ApiResponse<CreditLimitRequestDto>> createCreditLimitRequest(
      @Valid @RequestBody DealerPortalCreditLimitRequestCreateRequest request) {
    Long dealerId = dealerPortalService.getCurrentDealer().getId();
    DealerPortalService.RequesterIdentity requesterIdentity =
        dealerPortalService.getCurrentRequesterIdentity();
    CreditLimitRequestDto response =
        creditLimitRequestService.createRequest(
            new CreditLimitRequestCreateRequest(
                dealerId, request.amountRequested(), request.reason()),
            requesterIdentity.userId(),
            requesterIdentity.email());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("Credit limit request submitted", response));
  }

  /**
   * Download invoice PDF for the authenticated dealer.
   */
  @GetMapping(value = "/invoices/{invoiceId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  @Operation(summary = "Download invoice PDF (dealer scoped)")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "PDF document",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_PDF_VALUE,
              schema = @Schema(type = "string", format = "binary")))
  public ResponseEntity<byte[]> getMyInvoicePdf(@PathVariable Long invoiceId) {
    InvoicePdfService.PdfDocument pdf = dealerPortalService.getMyInvoicePdf(invoiceId);
    logDealerInvoiceExport(invoiceId, pdf.fileName());
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf.content());
  }

  private void logDealerInvoiceExport(Long invoiceId, String fileName) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "DEALER_INVOICE");
    metadata.put("resourceId", invoiceId != null ? invoiceId.toString() : "");
    metadata.put("operation", "EXPORT");
    metadata.put("format", "pdf");
    metadata.put("fileName", fileName != null ? fileName : "");
    auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);
  }
}
