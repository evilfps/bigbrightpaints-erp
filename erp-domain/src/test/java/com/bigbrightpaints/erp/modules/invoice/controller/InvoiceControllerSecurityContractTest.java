package com.bigbrightpaints.erp.modules.invoice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
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
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class InvoiceControllerSecurityContractTest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INVOICE-ROUTE-SEC";
  private static final String ADMIN_EMAIL = "invoice-admin@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;

  private Dealer dealer;

  @BeforeEach
  void setup() {
    UserAccount portalUser =
        dataSeeder.ensureUser(
            "invoice-dealer@bbp.com",
            PASSWORD,
            "Invoice Dealer",
            COMPANY_CODE,
            List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Invoice Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));

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
    dealerRepository.saveAndFlush(dealer);
  }

  @Test
  void downloadInvoicePdf_requiresAdminRole() throws NoSuchMethodException {
    Method method = InvoiceController.class.getMethod("downloadInvoicePdf", Long.class);
    PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
  }

  @Test
  void retiredDealerInvoiceAlias_isNotFoundForAdmin() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/invoices/dealers/" + dealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> payload =
        Map.of(
            "email", ADMIN_EMAIL,
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
