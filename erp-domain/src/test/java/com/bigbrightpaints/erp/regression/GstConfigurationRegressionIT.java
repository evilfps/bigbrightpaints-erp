package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: GST config health and returns require tax accounts")
class GstConfigurationRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-011";
    private static final String ADMIN_EMAIL = "lf011@erp.test";
    private static final String ADMIN_PASSWORD = "lf011";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;

    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "LF-011 Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        company.setGstInputTaxAccountId(null);
        company.setGstOutputTaxAccountId(null);
        companyRepository.save(company);

        headers = createHeaders(login());
    }

    @Test
    void configHealthFlagsMissingGstAccounts() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/configuration/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("healthy")).isEqualTo(false);

        List<Map<String, Object>> issues = (List<Map<String, Object>>) data.get("issues");
        assertThat(issues).isNotEmpty();
        assertThat(issues).anyMatch(issue -> "TAX_ACCOUNT".equals(issue.get("domain"))
                && "GST_INPUT".equals(issue.get("reference")));
        assertThat(issues).anyMatch(issue -> "TAX_ACCOUNT".equals(issue.get("domain"))
                && "GST_OUTPUT".equals(issue.get("reference")));
    }

    @Test
    void gstReturnFailsWithValidationErrorWhenAccountsMissing() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/gst/return",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("success", false);
        Object message = response.getBody().get("message");
        assertThat(message).isInstanceOf(String.class);
        assertThat((String) message).contains("GST tax accounts are not configured");
    }

    private String login() {
        Map<String, Object> request = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
        return response.getBody().get("accessToken").toString();
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(token);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.set("X-Company-Code", COMPANY_CODE);
        return httpHeaders;
    }
}
