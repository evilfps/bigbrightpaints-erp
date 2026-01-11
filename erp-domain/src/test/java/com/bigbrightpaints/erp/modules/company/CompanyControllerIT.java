package com.bigbrightpaints.erp.modules.company;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CompanyControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserAccountRepository userAccountRepository;

    private static final String COMPANY_CODE = "ACME";
    private static final String OTHER_COMPANY_CODE = "BETA";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String SWITCH_EMAIL = "switcher@bbp.com";
    private static final String SWITCH_PASSWORD = "switch123";
    private static final String MULTI_EMAIL = "multi@bbp.com";
    private static final String MULTI_PASSWORD = "multi123";

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
    }

    private String loginToken() {
        return loginToken(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY_CODE);
    }

    private String loginToken(String email, String password, String companyCode) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    @Test
    void list_companies_as_admin_only() {
        String token = loginToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/companies", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map dataWrapper = listResp.getBody();
        assertThat(dataWrapper).isNotNull();
        List list = (List) dataWrapper.get("data");
        assertThat(list).isNotNull();
    }

    @Test
    void switch_company_denies_for_unowned_company() {
        dataSeeder.ensureCompany(OTHER_COMPANY_CODE, OTHER_COMPANY_CODE + " Ltd");
        dataSeeder.ensureUser(SWITCH_EMAIL, SWITCH_PASSWORD, "Switch User", COMPANY_CODE, List.of("ROLE_ADMIN"));

        String token = loginToken(SWITCH_EMAIL, SWITCH_PASSWORD, COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of("companyCode", OTHER_COMPANY_CODE);
        ResponseEntity<Map> switchResp = rest.exchange(
                "/api/v1/multi-company/companies/switch",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UserAccount reloaded = userAccountRepository.findByEmailIgnoreCase(SWITCH_EMAIL).orElseThrow();
        List<String> companies = reloaded.getCompanies().stream().map(Company::getCode).toList();
        assertThat(companies).containsExactlyInAnyOrder(COMPANY_CODE);
    }

    @Test
    void auth_me_prefers_company_header_for_multi_company_user() {
        Company otherCompany = dataSeeder.ensureCompany(OTHER_COMPANY_CODE, OTHER_COMPANY_CODE + " Ltd");
        UserAccount user = dataSeeder.ensureUser(MULTI_EMAIL, MULTI_PASSWORD, "Multi Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        user.addCompany(otherCompany);
        userAccountRepository.save(user);

        String token = loginToken(MULTI_EMAIL, MULTI_PASSWORD, COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.add("X-Company-Id", OTHER_COMPANY_CODE);

        ResponseEntity<Map> meResp = rest.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map data = (Map) meResp.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("companyId")).isEqualTo(OTHER_COMPANY_CODE);
    }
}
