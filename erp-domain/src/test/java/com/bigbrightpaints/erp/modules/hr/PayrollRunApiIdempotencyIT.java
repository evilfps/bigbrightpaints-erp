package com.bigbrightpaints.erp.modules.hr;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HR Payroll API: Idempotent run creation")
public class PayrollRunApiIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private static final String COMPANY_CODE = "PAY-IDEMP";
    private static final String ADMIN_EMAIL = "pay-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "pay123";

    @BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Payroll Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
    }

    @Test
    void payrollRunCreate_isIdempotentByPeriodAndRunType() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);

        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        LocalDate periodEnd = today.withDayOfMonth(today.lengthOfMonth());

        Map<String, Object> runRequest = Map.of(
                "runType", "MONTHLY",
                "periodStart", periodStart,
                "periodEnd", periodEnd,
                "remarks", "Idempotency check"
        );

        ResponseEntity<Map> first = rest.exchange("/api/v1/payroll/runs",
                HttpMethod.POST, new HttpEntity<>(runRequest, headers), Map.class);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> firstData = (Map<?, ?>) first.getBody().get("data");
        Long firstId = ((Number) firstData.get("id")).longValue();

        ResponseEntity<Map> second = rest.exchange("/api/v1/payroll/runs",
                HttpMethod.POST, new HttpEntity<>(runRequest, headers), Map.class);
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> secondData = (Map<?, ?>) second.getBody().get("data");
        Long secondId = ((Number) secondData.get("id")).longValue();

        assertThat(secondId).isEqualTo(firstId);
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
