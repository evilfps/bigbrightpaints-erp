package com.bigbrightpaints.erp.modules.company;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CompanyControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate rest;
    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    @Test
    void create_and_list_companies_as_admin() {
        String token = loginToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> createBody = Map.of(
                "name", "Test Co",
                "code", "TESTCO",
                "timezone", "UTC"
        );
        ResponseEntity<Map> createResp = rest.exchange("/api/v1/companies", HttpMethod.POST, new HttpEntity<>(createBody, headers), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/companies", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map dataWrapper = listResp.getBody();
        assertThat(dataWrapper).isNotNull();
        List list = (List) dataWrapper.get("data");
        assertThat(list.stream().anyMatch(it -> String.valueOf(((Map) it).get("code")).equals("TESTCO"))).isTrue();
    }
}
