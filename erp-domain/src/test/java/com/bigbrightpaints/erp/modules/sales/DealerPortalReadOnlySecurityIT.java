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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Dealer portal credit-limit request security")
class DealerPortalReadOnlySecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "DEALER-PORTAL-READONLY";
  private static final String DEALER_EMAIL = "readonly-dealer@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private DealerRepository dealerRepository;

  @BeforeEach
  void setup() {
    UserAccount dealerUser =
        dataSeeder.ensureUser(
            DEALER_EMAIL, PASSWORD, "Readonly Dealer", COMPANY_CODE, List.of("ROLE_DEALER"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "READONLY-DEALER")
            .orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode("READONLY-DEALER");
    dealer.setName("Readonly Dealer");
    dealer.setCompanyName("Readonly Dealer Pvt Ltd");
    dealer.setEmail(DEALER_EMAIL);
    dealer.setCreditLimit(new BigDecimal("100000.00"));
    dealer.setPortalUser(dealerUser);
    dealerRepository.saveAndFlush(dealer);
  }

  @Test
  void dealerPortalCreditRequests_areAllowedForMappedDealer() {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/credit-limit-requests",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "amountRequested", "25000.00",
                    "reason", "Need more stock"),
                headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
    assertThat(response.getBody().get("message")).isEqualTo("Credit limit request submitted");
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("status")).isEqualTo("PENDING");
    assertThat(data.get("amountRequested")).isEqualTo(25000.00);
  }

  @Test
  void dealerPortalCreditRequests_validatePayload() {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/credit-limit-requests",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(String.valueOf(response.getBody().get("message"))).contains("amountRequested");
  }

  @Test
  void dealerPortalFinanceMutationAliases_areAbsentOutsideCreditLimitRequests() {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    List<String> retiredMutationPaths =
        List.of(
            "/api/v1/dealer-portal/dashboard",
            "/api/v1/dealer-portal/ledger",
            "/api/v1/dealer-portal/invoices",
            "/api/v1/dealer-portal/aging",
            "/api/v1/dealer-portal/orders");

    for (String path : retiredMutationPaths) {
      ResponseEntity<Map> response =
          rest.exchange(path, HttpMethod.POST, new HttpEntity<>(Map.of(), headers), Map.class);
      assertThat(response.getStatusCode())
          .as(path)
          .isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
    }
  }

  @Test
  void dealerRole_cannotReadTenantSalesPromotions() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/promotions", HttpMethod.GET, new HttpEntity<>(authHeaders()), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> payload =
        Map.of(
            "email", DEALER_EMAIL,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }
}
