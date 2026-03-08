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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@DisplayName("Dealer portal read-only security")
class DealerPortalReadOnlySecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DEALER-PORTAL-READONLY";
    private static final String DEALER_EMAIL = "readonly-dealer@bbp.com";
    private static final String PASSWORD = "changeme";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DealerRepository dealerRepository;

    @BeforeEach
    void setup() {
        UserAccount dealerUser = dataSeeder.ensureUser(
                DEALER_EMAIL,
                PASSWORD,
                "Readonly Dealer",
                COMPANY_CODE,
                List.of("ROLE_DEALER"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "READONLY-DEALER")
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
    void dealerPortalCreditRequests_areForbidden() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/credit-requests",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "amountRequested", "25000.00",
                        "reason", "Need more stock"
                ), headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertDeniedMessage(
                response,
                "Dealer portal is read-only. Ask your sales or admin contact to review credit-limit changes.");
    }

    @Test
    void dealerPortalCreditRequests_failClosedBeforePayloadValidation() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/credit-requests",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertDeniedMessage(
                response,
                "Dealer portal is read-only. Ask your sales or admin contact to review credit-limit changes.");
    }

    @Test
    void dealerRole_cannotReadTenantSalesPromotions() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/promotions",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> payload = Map.of(
                "email", DEALER_EMAIL,
                "password", PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.set("X-Company-Code", COMPANY_CODE);
        return headers;
    }

    private void assertDeniedMessage(ResponseEntity<Map> response, String expectedMessage) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo(expectedMessage);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("message")).isEqualTo(expectedMessage);
    }
}
