package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

class CR_PayrollLegacyEndpointGatedIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CR-PAY-LEGACY";
    private static final String USER_EMAIL = "payroll.legacy@test.com";
    private static final String USER_PASSWORD = "payroll123";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @BeforeEach
    void seedUser() {
        dataSeeder.ensureUser(USER_EMAIL, USER_PASSWORD, "Payroll Legacy", COMPANY_CODE,
                List.of("ROLE_ACCOUNTING"));
        enableModule(COMPANY_CODE, CompanyModule.HR_PAYROLL);
    }

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void legacyHrPayrollRunEndpoint_returnsGone_andCreatesNoRun() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE)
                .orElseGet(() -> dataSeeder.ensureCompany(COMPANY_CODE, "Payroll Legacy Co"));
        int beforeCount = payrollRunRepository.findByCompanyOrderByCreatedAtDesc(company).size();

        Map<String, Object> body = Map.of(
                "runDate", LocalDate.now(),
                "totalAmount", new BigDecimal("1500.00"),
                "notes", "Legacy payroll run",
                "idempotencyKey", "LEGACY-" + UUID.randomUUID()
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/hr/payroll-runs",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(loginToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("success", false);
        Object data = response.getBody().get("data");
        assertThat(data).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) data;
        assertThat(payload.get("canonicalPath")).isEqualTo("/api/v1/payroll/runs");
        assertThat(payrollRunRepository.findByCompanyOrderByCreatedAtDesc(company)).hasSize(beforeCount);
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", USER_EMAIL,
                "password", USER_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class)
                .getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Company-Code", COMPANY_CODE);
        return headers;
    }
}
