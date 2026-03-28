package com.bigbrightpaints.erp.modules.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Portal finance controller")
class PortalFinanceControllerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "PORTAL-FIN";
  private static final String OTHER_COMPANY_CODE = "PORTAL-FIN-OTHER";
  private static final String ADMIN_EMAIL = "portal-fin-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "portal-fin-accounting@bbp.com";
  private static final String SALES_EMAIL = "portal-fin-sales@bbp.com";
  private static final String DEALER_EMAIL = "portal-fin-dealer@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "portal-fin-superadmin@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private InvoiceRepository invoiceRepository;

  private Dealer dealerA;
  private Dealer foreignDealer;
  private Invoice invoiceA;

  @BeforeEach
  void setup() {
    dataSeeder.ensureCompany(COMPANY_CODE, "Portal Finance Company");
    dataSeeder.ensureCompany(OTHER_COMPANY_CODE, "Portal Finance Other Company");
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Portal Finance Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Portal Finance Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SALES_EMAIL, PASSWORD, "Portal Finance Sales", COMPANY_CODE, List.of("ROLE_SALES"));
    UserAccount dealerUser =
        dataSeeder.ensureUser(
            DEALER_EMAIL, PASSWORD, "Portal Finance Dealer", COMPANY_CODE, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Portal Finance Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Company otherCompany = companyRepository.findByCodeIgnoreCase(OTHER_COMPANY_CODE).orElseThrow();

    dealerA = upsertDealer(company, "PORTAL-FIN-A", "Portal Finance Dealer A", dealerUser);
    foreignDealer = upsertDealer(otherCompany, "PORTAL-FIN-B", "Portal Finance Dealer B", null);
    invoiceA = upsertInvoice(company, dealerA, "INV-PORTAL-FIN-A");
  }

  @Test
  void adminAndAccountingCanReadCanonicalPortalFinanceRoutes() {
    for (String email : List.of(ADMIN_EMAIL, ACCOUNTING_EMAIL)) {
      HttpHeaders headers = authHeaders(email, COMPANY_CODE);

      ResponseEntity<Map> ledger =
          rest.exchange(
              "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(headers),
              Map.class);
      ResponseEntity<Map> invoices =
          rest.exchange(
              "/api/v1/portal/finance/invoices?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(headers),
              Map.class);
      ResponseEntity<Map> aging =
          rest.exchange(
              "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(headers),
              Map.class);

      assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.OK);

      assertThat(((Map<?, ?>) ledger.getBody().get("data")).get("dealerId"))
          .isEqualTo(dealerA.getId().intValue());
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> invoiceRows =
          (List<Map<String, Object>>) ((Map<?, ?>) invoices.getBody().get("data")).get("invoices");
      assertThat(invoiceRows)
          .extracting(row -> ((Number) row.get("id")).longValue())
          .contains(invoiceA.getId());
      assertThat(((Map<?, ?>) aging.getBody().get("data")).get("dealerId"))
          .isEqualTo(dealerA.getId().intValue());
    }
  }

  @Test
  void dealerSalesAndSuperAdminAreDeniedFromPortalFinanceRoutes() {
    assertThat(
            rest.exchange(
                    "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(SALES_EMAIL, COMPANY_CODE)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    assertThat(
            rest.exchange(
                    "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(DEALER_EMAIL, COMPANY_CODE)),
                    Map.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
            "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(SUPER_ADMIN_EMAIL, COMPANY_CODE)),
            Map.class);

    assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(((Map<?, ?>) superAdminResponse.getBody().get("data")).get("reason"))
        .isEqualTo("SUPER_ADMIN_PLATFORM_ONLY");
  }

  @Test
  void portalFinanceRejectsCompanyHeaderMismatch() {
    HttpHeaders headers = authHeaders(ADMIN_EMAIL, COMPANY_CODE);
    headers.set("X-Company-Code", "MISMATCH");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void portalFinanceFailsClosedOnCrossTenantDealerLookup() {
    HttpHeaders headers = authHeaders(ADMIN_EMAIL, COMPANY_CODE);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/portal/finance/aging?dealerId=" + foreignDealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private HttpHeaders authHeaders(String email, String companyCode) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", companyCode);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", companyCode);
    return headers;
  }

  private Dealer upsertDealer(Company company, String code, String name, UserAccount portalUser) {
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(portalUser != null ? portalUser.getEmail() : code.toLowerCase() + "@bbp.com");
    dealer.setCreditLimit(new BigDecimal("100000"));
    dealer.setPortalUser(portalUser);
    dealer.setStatus("ACTIVE");
    return dealerRepository.saveAndFlush(dealer);
  }

  private Invoice upsertInvoice(Company company, Dealer dealer, String invoiceNumber) {
    Invoice invoice =
        invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
            .filter(existing -> invoiceNumber.equals(existing.getInvoiceNumber()))
            .findFirst()
            .orElseGet(Invoice::new);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber(invoiceNumber);
    invoice.setStatus("ISSUED");
    invoice.setIssueDate(LocalDate.now().minusDays(2));
    invoice.setDueDate(LocalDate.now().plusDays(15));
    invoice.setSubtotal(new BigDecimal("1000.00"));
    invoice.setTaxTotal(new BigDecimal("180.00"));
    invoice.setTotalAmount(new BigDecimal("1180.00"));
    invoice.setOutstandingAmount(new BigDecimal("1180.00"));
    invoice.setCurrency("INR");
    return invoiceRepository.saveAndFlush(invoice);
  }
}
