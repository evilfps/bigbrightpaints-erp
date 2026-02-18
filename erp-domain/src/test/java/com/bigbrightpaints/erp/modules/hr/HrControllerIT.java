package com.bigbrightpaints.erp.modules.hr;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class HrControllerIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "hr-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "hr123";

    @BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "HR Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    }

    @Test
    void employeeLifecycle_and_payroll_run() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);

        Map<String, Object> employeeRequest = Map.of(
                "firstName", "Priya",
                "lastName", "Menon",
                "email", "priya.menon@acme.test",
                "role", "HR_SPECIALIST",
                "hiredDate", LocalDate.now()
        );

        ResponseEntity<Map> createEmployee = rest.exchange(
                "/api/v1/hr/employees",
                HttpMethod.POST,
                new HttpEntity<>(employeeRequest, headers),
                Map.class);
        assertThat(createEmployee.getStatusCode()).isEqualTo(HttpStatus.OK);

        LocalDate periodEnd = LocalDate.now();
        LocalDate periodStart = periodEnd.minusDays(6);
        Map<String, Object> payrollRequest = Map.of(
                "runType", "WEEKLY",
                "periodStart", periodStart,
                "periodEnd", periodEnd,
                "remarks", "Test payroll run"
        );

        ResponseEntity<Map> payrollResp = rest.exchange(
                "/api/v1/payroll/runs",
                HttpMethod.POST,
                new HttpEntity<>(payrollRequest, headers),
                Map.class);
        assertThat(payrollResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> employees = rest.exchange(
                "/api/v1/hr/employees",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(employees.getStatusCode()).isEqualTo(HttpStatus.OK);
        List list = (List) employees.getBody().get("data");
        assertThat(list).isNotEmpty();

        ResponseEntity<Map> payrollRuns = rest.exchange(
                "/api/v1/hr/payroll-runs",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(payrollRuns.getStatusCode()).isEqualTo(HttpStatus.GONE);
        Map<?, ?> legacyGetData = (Map<?, ?>) payrollRuns.getBody().get("data");
        assertThat(legacyGetData.get("canonicalPath")).isEqualTo("/api/v1/payroll/runs");

        ResponseEntity<Map> legacyCreatePayroll = rest.exchange(
                "/api/v1/hr/payroll-runs",
                HttpMethod.POST,
                new HttpEntity<>(payrollRequest, headers),
                Map.class);
        assertThat(legacyCreatePayroll.getStatusCode()).isEqualTo(HttpStatus.GONE);
        Map<?, ?> legacyCreateData = (Map<?, ?>) legacyCreatePayroll.getBody().get("data");
        assertThat(legacyCreateData.get("canonicalPath")).isEqualTo("/api/v1/payroll/runs");
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Company-Id", COMPANY_CODE);
        return headers;
    }
}
