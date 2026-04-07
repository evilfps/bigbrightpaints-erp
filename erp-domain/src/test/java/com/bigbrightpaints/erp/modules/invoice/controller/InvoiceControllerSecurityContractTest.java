package com.bigbrightpaints.erp.modules.invoice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class InvoiceControllerSecurityContractTest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INVOICE-ROUTE-SEC";
  private static final String ADMIN_EMAIL = "invoice-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "invoice-accounting@bbp.com";
  private static final String DEALER_EMAIL = "invoice-dealer@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private InvoiceRepository invoiceRepository;

  private Dealer dealer;
  private Invoice invoice;

  @BeforeEach
  void setup() {
    UserAccount portalUser =
        dataSeeder.ensureUser(
            DEALER_EMAIL, PASSWORD, "Invoice Dealer", COMPANY_CODE, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Invoice Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL, PASSWORD, "Invoice Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "INV-ROUTE-DEALER")
            .orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode("INV-ROUTE-DEALER");
    dealer.setName("Invoice Route Dealer");
    dealer.setCompanyName("Invoice Route Dealer Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setCreditLimit(new BigDecimal("100000.00"));
    dealer.setPortalUser(portalUser);
    dealer = dealerRepository.saveAndFlush(dealer);

    SalesOrder order =
        salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer).stream()
            .filter(existing -> "SO-INVOICE-SEC-1".equals(existing.getOrderNumber()))
            .findFirst()
            .orElseGet(SalesOrder::new);
    order.setCompany(company);
    order.setDealer(dealer);
    order.setOrderNumber("SO-INVOICE-SEC-1");
    order.setStatus("INVOICED");
    order.setCurrency("INR");
    order.setSubtotalAmount(new BigDecimal("1000.00"));
    order.setGstTotal(new BigDecimal("180.00"));
    order.setGstRate(new BigDecimal("18.00"));
    order.setGstTreatment("INCLUSIVE");
    order.setGstInclusive(true);
    order.setGstRoundingAdjustment(BigDecimal.ZERO);
    order.setTotalAmount(new BigDecimal("1180.00"));
    SalesOrder savedOrder = salesOrderRepository.saveAndFlush(order);

    invoice =
        invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
            .filter(existing -> "INV-ROUTE-SEC-001".equals(existing.getInvoiceNumber()))
            .findFirst()
            .orElseGet(Invoice::new);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setSalesOrder(savedOrder);
    invoice.setInvoiceNumber("INV-ROUTE-SEC-001");
    invoice.setStatus("OPEN");
    invoice.setIssueDate(LocalDate.now().minusDays(1));
    invoice.setDueDate(LocalDate.now().plusDays(14));
    invoice.setSubtotal(new BigDecimal("1000.00"));
    invoice.setTaxTotal(new BigDecimal("180.00"));
    invoice.setTotalAmount(new BigDecimal("1180.00"));
    invoice.setOutstandingAmount(new BigDecimal("1180.00"));
    invoice.setCurrency("INR");

    invoice.getLines().clear();
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductCode("FG-1");
    line.setDescription("Premium Paint");
    line.setQuantity(new BigDecimal("10"));
    line.setUnitPrice(new BigDecimal("100.00"));
    line.setTaxRate(new BigDecimal("18.00"));
    line.setLineTotal(new BigDecimal("1180.00"));
    line.setTaxableAmount(new BigDecimal("1000.00"));
    line.setTaxAmount(new BigDecimal("180.00"));
    line.setDiscountAmount(BigDecimal.ZERO);
    line.setCgstAmount(new BigDecimal("90.00"));
    line.setSgstAmount(new BigDecimal("90.00"));
    line.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(line);

    invoice = invoiceRepository.saveAndFlush(invoice);
  }

  @Test
  void downloadInvoicePdf_requiresTenantAdminOrAccountingRole() throws NoSuchMethodException {
    Method method = InvoiceController.class.getMethod("downloadInvoicePdf", Long.class);
    PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')");
  }

  @Test
  void downloadInvoicePdf_allowsTenantAdmin() {
    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/invoices/" + invoice.getId() + "/pdf",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(ADMIN_EMAIL)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString()).contains("application/pdf");
    assertThat(response.getBody()).isNotEmpty();
  }

  @Test
  void downloadInvoicePdf_allowsTenantAccounting() {
    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/invoices/" + invoice.getId() + "/pdf",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(ACCOUNTING_EMAIL)),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString()).contains("application/pdf");
    assertThat(response.getBody()).isNotEmpty();
  }

  @Test
  void downloadInvoicePdf_deniesDealerRole() {
    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/invoices/" + invoice.getId() + "/pdf",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(DEALER_EMAIL)),
            byte[].class);

    assertThat(response.getStatusCode())
        .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED, HttpStatus.NOT_ACCEPTABLE);
  }

  @Test
  void retiredDealerInvoiceAlias_isNotFoundForAdmin() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/invoices/dealers/" + dealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(ADMIN_EMAIL)),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpHeaders authHeaders(String email) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }
}
