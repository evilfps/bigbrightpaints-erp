package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: GST config health and returns require tax accounts")
class GstConfigurationRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-011";
  private static final String ADMIN_EMAIL = "lf011@erp.test";
  private static final String ADMIN_PASSWORD = "lf011";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;

  private HttpHeaders headers;

  @BeforeEach
  void setUp() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "LF-011 Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setDefaultGstRate(new java.math.BigDecimal("18.00"));
    company.setDefaultInventoryAccountId(
        requireAccountId(company, "INV", "RAW-MATERIAL-INVENTORY"));
    company.setDefaultCogsAccountId(requireAccountId(company, "COGS", "FG-COGS", "RM-CONSUMPTION"));
    company.setDefaultRevenueAccountId(requireAccountId(company, "REV", "SERVICE-REVENUE"));
    company.setDefaultDiscountAccountId(requireAccountId(company, "DISC", "SALES-RETURNS"));
    company.setDefaultTaxAccountId(requireAccountId(company, "GST-OUT", "TAX-PAYABLE"));
    company.setGstInputTaxAccountId(null);
    company.setGstOutputTaxAccountId(null);
    company.setGstPayableAccountId(null);
    companyRepository.save(company);

    headers = createHeaders(login());
  }

  @Test
  void configHealthFlagsMissingGstAccounts() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/configuration/health",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data.get("healthy")).isEqualTo(false);

    List<Map<String, Object>> issues = (List<Map<String, Object>>) data.get("issues");
    issues =
        issues.stream().filter(issue -> COMPANY_CODE.equals(issue.get("companyCode"))).toList();
    assertThat(issues).isNotEmpty();
    assertThat(issues)
        .anyMatch(
            issue ->
                "TAX_ACCOUNT".equals(issue.get("domain"))
                    && "GST_INPUT".equals(issue.get("reference")));
    assertThat(issues)
        .anyMatch(
            issue ->
                "TAX_ACCOUNT".equals(issue.get("domain"))
                    && "GST_OUTPUT".equals(issue.get("reference")));
    assertThat(issues)
        .anyMatch(
            issue ->
                "TAX_ACCOUNT".equals(issue.get("domain"))
                    && "GST_PAYABLE".equals(issue.get("reference")));
  }

  @Test
  void configHealthFlagsNonGstCompanyCarryingGstAccounts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setDefaultGstRate(java.math.BigDecimal.ZERO);
    company.setGstInputTaxAccountId(company.getDefaultInventoryAccountId());
    company.setGstOutputTaxAccountId(company.getDefaultRevenueAccountId());
    company.setGstPayableAccountId(company.getDefaultTaxAccountId());
    companyRepository.save(company);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/configuration/health",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data.get("healthy")).isEqualTo(false);

    List<Map<String, Object>> issues = (List<Map<String, Object>>) data.get("issues");
    issues =
        issues.stream().filter(issue -> COMPANY_CODE.equals(issue.get("companyCode"))).toList();
    assertThat(issues)
        .anyMatch(
            issue ->
                "TAX_ACCOUNT".equals(issue.get("domain"))
                    && "NON_GST_MODE".equals(issue.get("reference"))
                    && issue
                        .get("message")
                        .toString()
                        .contains("Non-GST mode company cannot have GST tax accounts configured"));
  }

  @Test
  void gstReturnFailsWithValidationErrorWhenAccountsMissing() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/gst/return", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).containsEntry("success", false);
    Object message = response.getBody().get("message");
    assertThat(message).isInstanceOf(String.class);
    assertThat((String) message).contains("GST tax accounts are not configured");
  }

  private String login() {
    Map<String, Object> request =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
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

  private Long requireAccountId(Company company, String... codes) {
    for (String code : codes) {
      Account account =
          accountRepository.findByCompanyAndCodeIgnoreCase(company, code).orElse(null);
      if (account != null) {
        return account.getId();
      }
    }
    throw new IllegalStateException("Required account missing for company " + company.getCode());
  }
}
