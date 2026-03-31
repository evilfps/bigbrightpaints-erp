package com.bigbrightpaints.erp.modules.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Dealer Controller Security")
class DealerControllerSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "DEALER-SEC";
  private static final String DEALER_A_EMAIL = "dealer-a@bbp.com";
  private static final String DEALER_B_EMAIL = "dealer-b@bbp.com";
  private static final String ADMIN_EMAIL = "dealer-admin@bbp.com";
  private static final String SALES_EMAIL = "dealer-sales@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private DealerRepository dealerRepository;

  private Dealer dealerA;
  private Dealer dealerB;

  @BeforeEach
  void setup() {
    UserAccount dealerAUser =
        dataSeeder.ensureUser(
            DEALER_A_EMAIL, PASSWORD, "Dealer A User", COMPANY_CODE, List.of("ROLE_DEALER"));
    UserAccount dealerBUser =
        dataSeeder.ensureUser(
            DEALER_B_EMAIL, PASSWORD, "Dealer B User", COMPANY_CODE, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Dealer Admin User", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        SALES_EMAIL, PASSWORD, "Dealer Sales User", COMPANY_CODE, List.of("ROLE_SALES"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    dealerA = upsertDealer(company, "D-SEC-A", "Dealer A", dealerAUser);
    dealerB = upsertDealer(company, "D-SEC-B", "Dealer B", dealerBUser);
  }

  @Test
  @DisplayName("Retired dealer ledger endpoint is absent for dealer callers")
  void dealerCannotReadAnotherDealerLedger() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealers/" + dealerB.getId() + "/ledger",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Retired dealer ledger endpoint is absent even for own-dealer probes")
  void dealerCannotReadOwnLedgerFromBackofficeEndpoint() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/ledger",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Retired dealer invoices endpoint is absent for dealer callers")
  void dealerCannotReadAnotherDealerInvoices() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealers/" + dealerB.getId() + "/invoices",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Retired dealer aging endpoint is absent for dealer callers")
  void dealerCannotReadAnotherDealerAging() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealers/" + dealerB.getId() + "/aging",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Retired dealer finance endpoints are absent for own-dealer probes too")
  void dealerCannotReadOwnInvoicesAndAgingFromBackofficeEndpoints() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> invoices =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/invoices",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> aging =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/aging",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Admin sees retired dealer finance endpoints as not found")
  void adminSeesRetiredDealerBackofficeEndpointsAsNotFound() {
    HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD);
    ResponseEntity<Map> ledger =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/ledger",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> invoices =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/invoices",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> aging =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/aging",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> creditUtilization =
        rest.exchange(
            "/api/v1/dealers/" + dealerA.getId() + "/credit-utilization",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(creditUtilization.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Admin gets strict not-found for retired dealer finance endpoints regardless of id")
  void adminGetsNotFoundForMissingDealerReadEndpoints() {
    HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD);
    long missingDealerId = 999_999_999L;

    ResponseEntity<Map> ledger =
        rest.exchange(
            "/api/v1/dealers/" + missingDealerId + "/ledger",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> invoices =
        rest.exchange(
            "/api/v1/dealers/" + missingDealerId + "/invoices",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> aging =
        rest.exchange(
            "/api/v1/dealers/" + missingDealerId + "/aging",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> creditUtilization =
        rest.exchange(
            "/api/v1/dealers/" + missingDealerId + "/credit-utilization",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(creditUtilization.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Sales actor sees every retired dealer finance read as not found")
  void salesSeesRetiredDealerFinanceReadsAsNotFound() {
    HttpHeaders headers = authHeaders(SALES_EMAIL, PASSWORD);
    List<String> retiredPaths =
        List.of(
            "/api/v1/dealers/" + dealerA.getId() + "/ledger",
            "/api/v1/dealers/" + dealerA.getId() + "/invoices",
            "/api/v1/dealers/" + dealerA.getId() + "/aging",
            "/api/v1/dealers/" + dealerA.getId() + "/credit-utilization");

    for (String path : retiredPaths) {
      ResponseEntity<Map> response =
          rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  @DisplayName("Sales dealer directory redacts exact credit amounts on list surfaces")
  void salesDealerDirectoryRedactsExactCreditAmounts() {
    HttpHeaders headers = authHeaders(SALES_EMAIL, PASSWORD);

    ResponseEntity<Map> dealersResponse =
        rest.exchange("/api/v1/dealers", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    ResponseEntity<Map> salesAliasResponse =
        rest.exchange(
            "/api/v1/sales/dealers", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(dealersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(salesAliasResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertDealerDirectoryPayloadRedacted((List<Map<String, Object>>) dealersResponse.getBody().get("data"));
    assertDealerDirectoryPayloadRedacted(
        (List<Map<String, Object>>) salesAliasResponse.getBody().get("data"));
  }

  @Test
  @DisplayName("Sales dealer search redacts exact credit amounts but keeps credit status")
  void salesDealerSearchRedactsExactCreditAmounts() {
    HttpHeaders headers = authHeaders(SALES_EMAIL, PASSWORD);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealers/search?query=Dealer",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("data");
    assertThat(rows)
        .isNotEmpty()
        .allSatisfy(
            row -> {
              assertThat(row).containsKey("creditStatus");
              assertThat(row).doesNotContainKeys("creditLimit", "outstandingBalance");
            });
  }

  private HttpHeaders authHeaders(String email, String password) {
    Map<String, Object> req =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  private Dealer upsertDealer(Company company, String code, String name, UserAccount portalUser) {
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setCreditLimit(new BigDecimal("100000"));
    dealer.setPortalUser(portalUser);
    return dealerRepository.save(dealer);
  }

  private void assertDealerDirectoryPayloadRedacted(List<Map<String, Object>> rows) {
    assertThat(rows)
        .isNotEmpty()
        .allSatisfy(
            row -> {
              assertThat(row).containsKey("creditStatus");
              assertThat(row).doesNotContainKeys("creditLimit", "outstandingBalance");
            });
  }
}
