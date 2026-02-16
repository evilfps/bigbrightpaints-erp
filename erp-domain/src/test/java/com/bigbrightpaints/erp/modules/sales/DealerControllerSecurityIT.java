package com.bigbrightpaints.erp.modules.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
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

@DisplayName("Dealer Controller Security")
class DealerControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DEALER-SEC";
    private static final String DEALER_A_EMAIL = "dealer-a@bbp.com";
    private static final String DEALER_B_EMAIL = "dealer-b@bbp.com";
    private static final String ADMIN_EMAIL = "dealer-admin@bbp.com";
    private static final String PASSWORD = "DealerPass123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DealerRepository dealerRepository;

    private Dealer dealerA;
    private Dealer dealerB;

    @BeforeEach
    void setup() {
        UserAccount dealerAUser = dataSeeder.ensureUser(
                DEALER_A_EMAIL, PASSWORD, "Dealer A User", COMPANY_CODE, List.of("ROLE_DEALER"));
        UserAccount dealerBUser = dataSeeder.ensureUser(
                DEALER_B_EMAIL, PASSWORD, "Dealer B User", COMPANY_CODE, List.of("ROLE_DEALER"));
        dataSeeder.ensureUser(
                ADMIN_EMAIL, PASSWORD, "Dealer Admin User", COMPANY_CODE, List.of("ROLE_ADMIN"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        dealerA = upsertDealer(company, "D-SEC-A", "Dealer A", dealerAUser);
        dealerB = upsertDealer(company, "D-SEC-B", "Dealer B", dealerBUser);
    }

    @Test
    @DisplayName("Dealer cannot access another dealer ledger (returns 403, not 500)")
    void dealerCannotReadAnotherDealerLedger() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealers/" + dealerB.getId() + "/ledger",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Dealer can access own ledger")
    void dealerCanReadOwnLedger() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealers/" + dealerA.getId() + "/ledger",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Dealer cannot access another dealer invoices (returns 403, not 500)")
    void dealerCannotReadAnotherDealerInvoices() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealers/" + dealerB.getId() + "/invoices",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Dealer cannot access another dealer aging (returns 403, not 500)")
    void dealerCannotReadAnotherDealerAging() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealers/" + dealerB.getId() + "/aging",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Dealer can access own invoices and aging")
    void dealerCanReadOwnInvoicesAndAging() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> invoices = rest.exchange(
                "/api/v1/dealers/" + dealerA.getId() + "/invoices",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> aging = rest.exchange(
                "/api/v1/dealers/" + dealerA.getId() + "/aging",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Admin gets not-found for missing dealer read endpoints")
    void adminGetsNotFoundForMissingDealerReadEndpoints() {
        HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD);
        long missingDealerId = 999_999_999L;

        ResponseEntity<Map> ledger = rest.exchange(
                "/api/v1/dealers/" + missingDealerId + "/ledger",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> invoices = rest.exchange(
                "/api/v1/dealers/" + missingDealerId + "/invoices",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        ResponseEntity<Map> aging = rest.exchange(
                "/api/v1/dealers/" + missingDealerId + "/aging",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invoices.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(aging.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders authHeaders(String email, String password) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Id", COMPANY_CODE);
        return headers;
    }

    private Dealer upsertDealer(Company company, String code, String name, UserAccount portalUser) {
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
        dealer.setCompany(company);
        dealer.setCode(code);
        dealer.setName(name);
        dealer.setCompanyName(name + " Pvt Ltd");
        dealer.setEmail(portalUser.getEmail());
        dealer.setCreditLimit(new BigDecimal("100000"));
        dealer.setPortalUser(portalUser);
        return dealerRepository.save(dealer);
    }
}
