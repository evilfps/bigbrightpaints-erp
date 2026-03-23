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

        Map<String, Object> employeeRequest = Map.ofEntries(
                Map.entry("firstName", "Priya"),
                Map.entry("lastName", "Menon"),
                Map.entry("email", "priya.menon@acme.test"),
                Map.entry("phone", "9999911111"),
                Map.entry("role", "HR_SPECIALIST"),
                Map.entry("hiredDate", LocalDate.now()),
                Map.entry("dateOfBirth", LocalDate.of(1992, 3, 10)),
                Map.entry("gender", "FEMALE"),
                Map.entry("department", "People Operations"),
                Map.entry("designation", "HR Manager"),
                Map.entry("dateOfJoining", LocalDate.now()),
                Map.entry("employmentType", "FULL_TIME"),
                Map.entry("employeeType", "STAFF"),
                Map.entry("paymentSchedule", "MONTHLY"),
                Map.entry("monthlySalary", 65000),
                Map.entry("workingDaysPerMonth", 26),
                Map.entry("standardHoursPerDay", 8),
                Map.entry("pfNumber", "PF-7788"),
                Map.entry("esiNumber", "ESI-8899"),
                Map.entry("panNumber", "ABCDE1234F"),
                Map.entry("taxRegime", "NEW"),
                Map.entry("bankAccountNumber", "123456789012"),
                Map.entry("bankName", "HDFC Bank"),
                Map.entry("ifscCode", "HDFC0001234"),
                Map.entry("bankBranch", "MG Road")
        );

        ResponseEntity<Map> createEmployee = rest.exchange(
                "/api/v1/hr/employees",
                HttpMethod.POST,
                new HttpEntity<>(employeeRequest, headers),
                Map.class);
        assertThat(createEmployee.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> createdEmployeeData = (Map<?, ?>) createEmployee.getBody().get("data");
        assertThat(createdEmployeeData.get("department")).isEqualTo("People Operations");
        assertThat(createdEmployeeData.get("designation")).isEqualTo("HR Manager");
        assertThat(createdEmployeeData.get("taxRegime")).isEqualTo("NEW");
        assertThat(createdEmployeeData.get("panNumber")).isEqualTo("ABCDE1234F");
        assertThat(createdEmployeeData.get("salaryStructureTemplateId")).isNull();

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
        headers.add("X-Company-Code", COMPANY_CODE);
        return headers;
    }
}
