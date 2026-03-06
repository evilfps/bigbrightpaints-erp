package com.bigbrightpaints.erp.modules.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class SuperAdminControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ROOT_COMPANY_CODE = "ROOT";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
    private static final String PASSWORD = "admin123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Super Admin", ROOT_COMPANY_CODE,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        companyRepository.findByCodeIgnoreCase(COMPANY_CODE).ifPresent(company -> {
            company.setLifecycleState(CompanyLifecycleState.ACTIVE);
            company.setLifecycleReason(null);
            companyRepository.save(company);
        });
        companyRepository.findByCodeIgnoreCase(ROOT_COMPANY_CODE).ifPresent(company -> {
            company.setLifecycleState(CompanyLifecycleState.ACTIVE);
            company.setLifecycleReason(null);
            companyRepository.save(company);
        });
    }

    @Test
    void dashboard_requiresSuperAdminAuthority() {
        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        ResponseEntity<Map> forbidden = rest.exchange(
                "/api/v1/superadmin/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers(adminToken, COMPANY_CODE)),
                Map.class);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        ResponseEntity<Map> allowed = rest.exchange(
                "/api/v1/superadmin/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers(superAdminToken, ROOT_COMPANY_CODE)),
                Map.class);

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).isNotNull();
        assertThat(allowed.getBody()).containsKey("data");
    }

    @Test
    void superAdmin_canSuspendActivateListAndReadUsage() {
        Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        String tenantToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        rest.exchange(
                "/api/v1/companies",
                HttpMethod.GET,
                new HttpEntity<>(headers(tenantToken, COMPANY_CODE)),
                Map.class);

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

        ResponseEntity<Map> suspendResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Company suspended = companyRepository.findById(tenant.getId()).orElseThrow();
        assertThat(suspended.getLifecycleState()).isEqualTo(CompanyLifecycleState.SUSPENDED);

        ResponseEntity<Map> tenantsResponse = rest.exchange(
                "/api/v1/superadmin/tenants?status=SUSPENDED",
                HttpMethod.GET,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(tenantsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tenants = (List<Map<String, Object>>) tenantsResponse.getBody().get("data");
        assertThat(tenants)
                .extracting(row -> row.get("companyCode").toString().toUpperCase(Locale.ROOT))
                .contains(COMPANY_CODE);

        ResponseEntity<Map> usageResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/usage",
                HttpMethod.GET,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(usageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) usageResponse.getBody().get("data");
        assertThat(Long.parseLong(usage.get("apiCallCount").toString())).isGreaterThanOrEqualTo(1L);
        assertThat(usage.get("lastActivityAt")).isNotNull();

        ResponseEntity<Map> activateResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Company activated = companyRepository.findById(tenant.getId()).orElseThrow();
        assertThat(activated.getLifecycleState()).isEqualTo(CompanyLifecycleState.ACTIVE);

        ResponseEntity<Map> deactivateResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/deactivate",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Company deactivated = companyRepository.findById(tenant.getId()).orElseThrow();
        assertThat(deactivated.getLifecycleState()).isEqualTo(CompanyLifecycleState.DEACTIVATED);
    }

    @Test
    void superAdmin_lifecycle_transitions_persist_schemaCompatible_values() {
        Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

        ResponseEntity<Map> suspendResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readLifecycleState(tenant.getId())).isEqualTo("HOLD");

        ResponseEntity<Map> activateResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readLifecycleState(tenant.getId())).isEqualTo("ACTIVE");

        ResponseEntity<Map> deactivateResponse = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/deactivate",
                HttpMethod.POST,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readLifecycleState(tenant.getId())).isEqualTo("BLOCKED");
    }

    @Test
    void superAdmin_canConfigureTenantModules() {
        Company tenant = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders superAdminHeaders = headers(superAdminToken, ROOT_COMPANY_CODE);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/modules",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabledModules", List.of("PORTAL")), superAdminHeaders),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(((Number) data.get("companyId")).longValue()).isEqualTo(tenant.getId());
        assertThat(data.get("companyCode")).isEqualTo(COMPANY_CODE);
        @SuppressWarnings("unchecked")
        List<String> enabledModules = (List<String>) data.get("enabledModules");
        assertThat(enabledModules).containsExactly("PORTAL");

        Company updated = companyRepository.findById(tenant.getId()).orElseThrow();
        assertThat(updated.getEnabledModules()).containsExactly("PORTAL");
    }

    private HttpHeaders headers(String token, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", companyCode);
        return headers;
    }

    private String loginToken(String email, String companyCode) {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode);
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private String readLifecycleState(Long companyId) {
        return jdbcTemplate.queryForObject(
                "select lifecycle_state from companies where id = ?",
                String.class,
                companyId);
    }
}
