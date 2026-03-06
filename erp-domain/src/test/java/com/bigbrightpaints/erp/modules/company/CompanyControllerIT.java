package com.bigbrightpaints.erp.modules.company;

import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

public class CompanyControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private SystemSettingsRepository systemSettingsRepository;
    @SpyBean private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
    private static final String COMPANY_CODE = "ACME";
    private static final String ROOT_COMPANY_CODE = "ROOT";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String SUPER_ADMIN_EMAIL = "super-admin@bbp.com";
    private static final String HIERARCHY_SUPER_ADMIN_EMAIL = "super-admin-hierarchy@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, ADMIN_PASSWORD, "Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, ADMIN_PASSWORD, "Super Admin", COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        dataSeeder.ensureUser(HIERARCHY_SUPER_ADMIN_EMAIL, ADMIN_PASSWORD, "Hierarchy Super Admin", COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN"));
        userAccountRepository.findByEmailIgnoreCase(ADMIN_EMAIL).ifPresent(user -> {
            user.setMustChangePassword(false);
            user.setEnabled(true);
            userAccountRepository.save(user);
        });
        userAccountRepository.findByEmailIgnoreCase(SUPER_ADMIN_EMAIL).ifPresent(user -> {
            user.setMustChangePassword(false);
            user.setEnabled(true);
            userAccountRepository.save(user);
        });
        userAccountRepository.findByEmailIgnoreCase(HIERARCHY_SUPER_ADMIN_EMAIL).ifPresent(user -> {
            user.setMustChangePassword(false);
            user.setEnabled(true);
            userAccountRepository.save(user);
        });
        companyRepository.findByCodeIgnoreCase(COMPANY_CODE).ifPresent(company -> {
            company.setLifecycleState(CompanyLifecycleState.ACTIVE);
            company.setLifecycleReason(null);
            companyRepository.save(company);
            resetTenantRuntimePolicy(company.getId(), company.getCode());
        });
        companyRepository.findByCodeIgnoreCase(ROOT_COMPANY_CODE).ifPresent(company -> {
            company.setLifecycleState(CompanyLifecycleState.ACTIVE);
            company.setLifecycleReason(null);
            companyRepository.save(company);
            resetTenantRuntimePolicy(company.getId(), company.getCode());
        });
    }

    private void resetTenantRuntimePolicy(Long companyId, String companyCode) {
        if (companyId == null) {
            return;
        }
        systemSettingsRepository.deleteById("tenant.runtime.hold-state." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.hold-reason." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-active-users." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-requests-per-minute." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-concurrent-requests." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.policy-reference." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.policy-updated-at." + companyId);
        tenantRuntimeEnforcementService.invalidatePolicyCache(companyCode);
    }

    private String loginToken() {
        return loginToken(ADMIN_EMAIL, COMPANY_CODE);
    }

    private String loginToken(String email, String companyCode) {
        return loginPayload(email, companyCode).get("accessToken").toString();
    }

    private Map<String, Object> loginPayload(String email, String companyCode) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", ADMIN_PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    void list_companies_as_admin_only() {
        String token = loginToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", COMPANY_CODE);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/companies", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map dataWrapper = listResp.getBody();
        assertThat(dataWrapper).isNotNull();
        List list = (List) dataWrapper.get("data");
        assertThat(list).isNotNull();
    }

    @Test
    void list_companies_allows_super_admin_without_admin_role_assignment() {
        String token = loginToken(HIERARCHY_SUPER_ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/companies", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void list_companies_returns_all_tenants_for_root_only_super_admin() {
        String rootOnlySuperAdminEmail = "root-list-super-admin@bbp.com";
        dataSeeder.ensureUser(rootOnlySuperAdminEmail, ADMIN_PASSWORD, "Root List Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN"));
        String token = loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/companies", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> companies = (List<Map<String, Object>>) listResp.getBody().get("data");
        assertThat(companies).isNotNull();
        assertThat(companies)
                .extracting(company -> company.get("code").toString().toUpperCase(Locale.ROOT))
                .contains(ROOT_COMPANY_CODE, COMPANY_CODE);
    }

    @Test
    void superadmin_dashboard_requires_super_admin_authority() {
        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/companies/superadmin/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders superAdminHeaders = new HttpHeaders();
        superAdminHeaders.setBearerAuth(superAdminToken);
        superAdminHeaders.setContentType(MediaType.APPLICATION_JSON);
        superAdminHeaders.set("X-Company-Code", ROOT_COMPANY_CODE);
        ResponseEntity<Map> superAdminResponse = rest.exchange(
                "/api/v1/companies/superadmin/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(superAdminHeaders),
                Map.class);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(superAdminResponse.getBody()).isNotNull();
        assertThat(superAdminResponse.getBody()).containsKey("data");
    }

    @Test
    void support_warning_endpoint_issues_warning_for_super_admin() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(superAdminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", ROOT_COMPANY_CODE);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies/" + companyId + "/support/warnings",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "warningCategory", "quota",
                        "message", "Storage usage crossed 85% of plan",
                        "requestedLifecycleState", "SUSPENDED",
                        "gracePeriodHours", 24
                ), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("companyCode").toString().toUpperCase(Locale.ROOT)).isEqualTo(COMPANY_CODE);
        assertThat(data.get("warningId")).isNotNull();
        assertThat(data.get("requestedLifecycleState")).isEqualTo("SUSPENDED");
    }

    @Test
    void support_admin_password_reset_revokes_existing_admin_tokens() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        Map<String, Object> adminLogin = loginPayload(ADMIN_EMAIL, COMPANY_CODE);
        String adminAccessToken = adminLogin.get("accessToken").toString();
        String adminRefreshToken = adminLogin.get("refreshToken").toString();

        String superAdminToken = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        HttpHeaders supportHeaders = new HttpHeaders();
        supportHeaders.setBearerAuth(superAdminToken);
        supportHeaders.setContentType(MediaType.APPLICATION_JSON);
        supportHeaders.set("X-Company-Code", ROOT_COMPANY_CODE);

        ResponseEntity<Map> resetResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/support/admin-password-reset",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "adminEmail", ADMIN_EMAIL,
                        "reason", "Security hard reset"), supportHeaders),
                Map.class);

        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminAccessToken);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> meResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(meResponse.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);

        ResponseEntity<Map> refreshResponse = rest.postForEntity(
                "/api/v1/auth/refresh-token",
                Map.of(
                        "refreshToken", adminRefreshToken,
                        "companyCode", COMPANY_CODE),
                Map.class);
        assertThat(refreshResponse.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tenant_bootstrap_accepts_missing_default_gst_rate_and_applies_fallback() {
        String token = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        String newCompanyCode = "GST-FALLBACK-" + System.nanoTime();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", ROOT_COMPANY_CODE);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "GST Fallback Co",
                        "code", newCompanyCode,
                        "timezone", "UTC"
                ), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(companyRepository.findByCodeIgnoreCase(newCompanyCode).orElseThrow().getDefaultGstRate())
                .isEqualByComparingTo("18");

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        assertThat(data).isNotNull();
        assertThat(new BigDecimal(data.get("defaultGstRate").toString())).isEqualByComparingTo("18");
    }

    @Test
    void tenant_bootstrap_preserves_explicit_zero_default_gst_rate() {
        String token = loginToken(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
        String newCompanyCode = "GST-ZERO-" + System.nanoTime();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", ROOT_COMPANY_CODE);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "GST Zero Co",
                        "code", newCompanyCode,
                        "timezone", "UTC",
                        "defaultGstRate", 0
                ), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(companyRepository.findByCodeIgnoreCase(newCompanyCode).orElseThrow().getDefaultGstRate())
                .isEqualByComparingTo("0");

        @SuppressWarnings("unchecked")
        Map<String, Object> responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
        assertThat(data).isNotNull();
        assertThat(new BigDecimal(data.get("defaultGstRate").toString())).isEqualByComparingTo("0");
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

    @Test
    void tenant_configuration_update_allows_super_admin_outside_target_tenant_membership() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        String rootOnlySuperAdminEmail = "root-only-super-admin@bbp.com";
        dataSeeder.ensureUser(rootOnlySuperAdminEmail, ADMIN_PASSWORD, "Root Only Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        Map<String, Object> updateRequest = Map.of(
                "name", "Config Updated By Root",
                "code", COMPANY_CODE,
                "timezone", "UTC",
                "defaultGstRate", 18.0
        );

        String superAdminRootToken = loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE);
        HttpHeaders rootHeaders = new HttpHeaders();
        rootHeaders.setBearerAuth(superAdminRootToken);
        rootHeaders.setContentType(MediaType.APPLICATION_JSON);
        rootHeaders.set("X-Company-Code", ROOT_COMPANY_CODE);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies/" + companyId,
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, rootHeaders),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tenant_runtime_policy_update_requires_super_admin() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        Map<String, Object> updateRequest = Map.of(
                "holdState", "ACTIVE",
                "reasonCode", "policy-refresh",
                "maxConcurrentRequests", 15,
                "maxRequestsPerMinute", 120,
                "maxActiveUsers", 45
        );

        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);
        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
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
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, superAdminHeaders),
                Map.class);
        assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tenant_runtime_policy_update_allows_super_admin_outside_target_tenant_membership() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        String rootOnlySuperAdminEmail = "root-only-super-admin-runtime@bbp.com";
        dataSeeder.ensureUser(rootOnlySuperAdminEmail, ADMIN_PASSWORD, "Root Only Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
        Map<String, Object> updateRequest = Map.of(
                "holdState", "ACTIVE",
                "reasonCode", "recovery-complete",
                "maxConcurrentRequests", 25
        );

        String rootToken = loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(rootToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", ROOT_COMPANY_CODE);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void canonical_tenant_runtime_policy_update_tracks_policy_control_and_blocks_same_node_immediately() {
        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        String rootOnlySuperAdminEmail = "root-policy-super-admin@bbp.com";
        dataSeeder.ensureUser(rootOnlySuperAdminEmail, ADMIN_PASSWORD, "Root Policy Super Admin", ROOT_COMPANY_CODE,
                java.util.List.of("ROLE_SUPER_ADMIN"));

        String adminToken = loginToken(ADMIN_EMAIL, COMPANY_CODE);
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        adminHeaders.set("X-Company-Code", COMPANY_CODE);

        ResponseEntity<Map> warmResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(warmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        clearInvocations(tenantRuntimeEnforcementService);

        String rootToken = loginToken(rootOnlySuperAdminEmail, ROOT_COMPANY_CODE);
        HttpHeaders rootHeaders = new HttpHeaders();
        rootHeaders.setBearerAuth(rootToken);
        rootHeaders.setContentType(MediaType.APPLICATION_JSON);
        rootHeaders.set("X-Company-Code", ROOT_COMPANY_CODE);

        ResponseEntity<Map> updateResponse = rest.exchange(
                "/api/v1/companies/" + companyId + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "holdState", "BLOCKED",
                        "reasonCode", "incident-lockdown",
                        "maxConcurrentRequests", 25,
                        "maxRequestsPerMinute", 250,
                        "maxActiveUsers", 45
                ), rootHeaders),
                Map.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(tenantRuntimeEnforcementService).beginRequest(
                eq(COMPANY_CODE),
                eq("/api/v1/companies/" + companyId + "/tenant-runtime/policy"),
                eq("PUT"),
                eq(rootOnlySuperAdminEmail),
                eq(true));

        ResponseEntity<Map> blockedMeResponse = rest.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);

        assertThat(blockedMeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blockedMeResponse.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorData = (Map<String, Object>) blockedMeResponse.getBody().get("data");
        assertThat(errorData).isNotNull();
        assertThat(errorData.get("reason")).isEqualTo("TENANT_BLOCKED");
    }
}
