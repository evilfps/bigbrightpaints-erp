package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Period closing, lock, reopen")
class PeriodCloseLockIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "PERIOD";
    private static final String ADMIN_EMAIL = "period-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "period123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;

    private HttpHeaders headers;
    private Company company;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Period Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY_CODE);
    }

    @Test
    @DisplayName("Direct close is rejected in favor of request-close plus approve-close")
    void directCloseRequiresMakerCheckerWorkflow() {
        Long periodId = currentPeriodId(TestDateUtils.safeDate(company));
        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Month close", "force", true), headers),
                Map.class);

        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(closeResp.getBody().get("message")))
                .contains("/request-close")
                .contains("approve");
    }

    private Long currentPeriodId(LocalDate forDate) {
        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/accounting/periods",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        List<Map<String, Object>> list = extractPeriodDtos(resp.getBody().get("data"));
        assertThat(list).isNotEmpty();
        for (Map<String, Object> dto : list) {
            Object startObj = dto.get("startDate");
            LocalDate start;
            if (startObj instanceof List<?> arr && arr.size() == 3) {
                start = LocalDate.of(((Number) arr.get(0)).intValue(), ((Number) arr.get(1)).intValue(), ((Number) arr.get(2)).intValue());
            } else {
                start = LocalDate.parse(String.valueOf(startObj));
            }
            if (start.getYear() == forDate.getYear() && start.getMonthValue() == forDate.getMonthValue()) {
                return ((Number) dto.get("id")).longValue();
            }
        }
        throw new AssertionError("No period found for date " + forDate);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPeriodDtos(Object data) {
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data instanceof Map<?, ?> wrapper) {
            Object nested = wrapper.get("items");
            if (nested == null) {
                nested = wrapper.get("content");
            }
            if (nested == null) {
                nested = wrapper.get("data");
            }
            if (nested instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        }
        throw new AssertionError("Unexpected periods payload: " + data);
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        assertThat(token).isNotBlank();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Code", companyCode);
        return h;
    }
}
