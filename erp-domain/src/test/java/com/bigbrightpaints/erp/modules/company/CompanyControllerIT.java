package com.bigbrightpaints.erp.modules.company;

import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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
    @Autowired private CompanyRepository companyRepository;
    private static final String COMPANY_CODE = "ACME";
    private static final String ROOT_COMPANY_CODE = "ROOT";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, ADMIN_PASSWORD, "Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, ADMIN_PASSWORD, "Super Admin", COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    }

    private String loginToken() {
        return loginToken(ADMIN_EMAIL, COMPANY_CODE);
    }

    private String loginToken(String email, String companyCode) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", ADMIN_PASSWORD,
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
    void tenant_metrics_requires_super_admin() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();

        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-metrics",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders superAdminHeaders = new HttpHeaders();
        superAdminHeaders.setBearerAuth(superAdminToken);
        superAdminHeaders.setContentType(MediaType.APPLICATION_JSON);
        superAdminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> superAdminResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-metrics",
                HttpMethod.GET,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tenant_configuration_update_requires_super_admin() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        Map<String, Object> updateRequest = Map.of(
                "name", "Config Updated",
                "code", COMPANY_CODE,
                "timezone", "UTC",
                "defaultGstRate", 18.0
        );

        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/companies/" + companyId,
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, adminHeaders),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders superAdminHeaders = new HttpHeaders();
        superAdminHeaders.setBearerAuth(superAdminToken);
        superAdminHeaders.setContentType(MediaType.APPLICATION_JSON);
        superAdminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> superAdminResponse = rest.exchange(
                "/api/v1/companies/" + companyId,
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, superAdminHeaders),
                Map.class);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
