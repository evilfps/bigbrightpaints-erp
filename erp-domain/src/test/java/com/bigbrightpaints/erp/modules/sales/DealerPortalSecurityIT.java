package com.bigbrightpaints.erp.modules.sales;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DealerPortalSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DEAL-PORTAL";
    private static final String DEALER_EMAIL = "dealer-portal@bbp.com";
    private static final String DEALER_PASSWORD = "Dealer123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DealerRepository dealerRepository;

    private Company company;
    private UserAccount dealerUser;
    private Dealer dealer;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, "Dealer Portal Co");
        dealerUser = dataSeeder.ensureUser(DEALER_EMAIL, DEALER_PASSWORD, "Portal Dealer", COMPANY_CODE,
                List.of("ROLE_DEALER"));
        dealer = ensureDealer(company, "PORTAL-ONLY", "Portal Only Dealer", dealerUser);
    }

    @Test
    void dealer_portal_dashboard_returns_dealer_context() {
        String token = login(DEALER_EMAIL, DEALER_PASSWORD, COMPANY_CODE);
        HttpHeaders headers = authHeaders(token, COMPANY_CODE);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map data = (Map) response.getBody().get("data");
        assertThat(data).isNotNull();
        Number dealerId = (Number) data.get("dealerId");
        assertThat(dealerId).isNotNull();
        assertThat(dealerId.longValue()).isEqualTo(dealer.getId());
        assertThat(data.get("dealerName")).isEqualTo(dealer.getName());
    }

    private Dealer ensureDealer(Company company, String code, String name, UserAccount portalUser) {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .map(existing -> {
                    if (existing.getPortalUser() == null) {
                        existing.setPortalUser(portalUser);
                    }
                    if (existing.getName() == null) {
                        existing.setName(name);
                    }
                    if (existing.getCreditLimit() == null) {
                        existing.setCreditLimit(BigDecimal.ZERO);
                    }
                    if (existing.getOutstandingBalance() == null) {
                        existing.setOutstandingBalance(BigDecimal.ZERO);
                    }
                    return dealerRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> {
                    Dealer dealer = new Dealer();
                    dealer.setCompany(company);
                    dealer.setName(name);
                    dealer.setCode(code);
                    dealer.setPortalUser(portalUser);
                    dealer.setCreditLimit(BigDecimal.ZERO);
                    dealer.setOutstandingBalance(BigDecimal.ZERO);
                    return dealerRepository.saveAndFlush(dealer);
                });
    }

    private String login(String email, String password, String companyCode) {
        Map<String, Object> payload = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        return body.get("accessToken").toString();
    }

    private HttpHeaders authHeaders(String token, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Id", companyCode);
        return headers;
    }
}
