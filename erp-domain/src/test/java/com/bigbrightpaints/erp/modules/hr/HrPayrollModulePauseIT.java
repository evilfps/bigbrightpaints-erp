package com.bigbrightpaints.erp.modules.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.util.List;
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

class HrPayrollModulePauseIT extends AbstractIntegrationTest {

    private static final String TENANT_CODE = "PAY-PAUSE";
    private static final String ROOT_CODE = "ROOT";
    private static final String ADMIN_EMAIL = "pay.pause.admin@test.com";
    private static final String SUPER_ADMIN_EMAIL = "pay.pause.superadmin@test.com";
    private static final String PASSWORD = "Pause123!";

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void seedUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Payroll Pause Admin", TENANT_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, PASSWORD, "Payroll Pause Super Admin", ROOT_CODE,
                List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    }

    @Test
    void pausedModule_blocksPayrollSurfaces_andRedactsPortalDashboard() {
        Company tenant = companyRepository.findByCodeIgnoreCase(TENANT_CODE).orElseThrow();
        assertThat(tenant.getEnabledModules()).doesNotContain("HR_PAYROLL");

        HttpHeaders tenantHeaders = authHeaders(loginToken(ADMIN_EMAIL, TENANT_CODE), TENANT_CODE);

        ResponseEntity<Map> payrollRuns = rest.exchange(
                "/api/v1/payroll/runs",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                Map.class);
        assertThat(payrollRuns.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> payrollBatchPayment = rest.exchange(
                "/api/v1/accounting/payroll/payments/batch",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), tenantHeaders),
                Map.class);
        assertThat(payrollBatchPayment.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> workforce = rest.exchange(
                "/api/v1/portal/workforce",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                Map.class);
        assertThat(workforce.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> dashboard = rest.exchange(
                "/api/v1/portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> dashboardData = (Map<String, Object>) dashboard.getBody().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hrPulse = (List<Map<String, Object>>) dashboardData.get("hrPulse");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> highlights = (List<Map<String, Object>>) dashboardData.get("highlights");
        assertThat(hrPulse).isEmpty();
        assertThat(highlights)
                .extracting(item -> item.get("label"))
                .doesNotContain("Active workforce");
    }

    @Test
    void superAdmin_canReEnableHrPayroll_andSmokeRoutesRecover() {
        Company tenant = companyRepository.findByCodeIgnoreCase(TENANT_CODE).orElseThrow();
        HttpHeaders rootHeaders = authHeaders(loginToken(SUPER_ADMIN_EMAIL, ROOT_CODE), ROOT_CODE);

        ResponseEntity<Map> updateModules = rest.exchange(
                "/api/v1/superadmin/tenants/" + tenant.getId() + "/modules",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "enabledModules",
                        List.of("MANUFACTURING", "HR_PAYROLL", "PURCHASING", "PORTAL", "REPORTS_ADVANCED")),
                        rootHeaders),
                Map.class);
        assertThat(updateModules.getStatusCode()).isEqualTo(HttpStatus.OK);

        Company updated = companyRepository.findById(tenant.getId()).orElseThrow();
        assertThat(updated.getEnabledModules()).contains("HR_PAYROLL");

        HttpHeaders tenantHeaders = authHeaders(loginToken(ADMIN_EMAIL, TENANT_CODE), TENANT_CODE);

        ResponseEntity<Map> payrollRuns = rest.exchange(
                "/api/v1/payroll/runs",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                Map.class);
        assertThat(payrollRuns.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> workforce = rest.exchange(
                "/api/v1/portal/workforce",
                HttpMethod.GET,
                new HttpEntity<>(tenantHeaders),
                Map.class);
        assertThat(workforce.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String loginToken(String email, String companyCode) {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD, "companyCode", companyCode),
                Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String token, String companyCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Code", companyCode);
        return headers;
    }
}
