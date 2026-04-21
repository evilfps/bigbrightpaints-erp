package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("concurrency")
class AccountCodeCaseInsensitiveUniquenessIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "changeme";

  @Autowired private AccountRepository accountRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;

  @Test
  void createAccount_caseVariantDuplicateReturnsConflictWithDuplicateEntryContract() {
    String companyCode = "COA-CI-" + shortId();
    Company company = dataSeeder.ensureCompany(companyCode, "Account CI " + companyCode);
    String userEmail = "account-ci-" + shortId().toLowerCase(Locale.ROOT) + "@bbp.com";
    dataSeeder.ensureUser(
        userEmail, PASSWORD, "Account CI User", companyCode, List.of("ROLE_ACCOUNTING"));
    HttpHeaders headers = authHeaders(userEmail, companyCode);

    String canonicalCode = "cash-" + shortId().toLowerCase(Locale.ROOT);
    ResponseEntity<Map> createLowercaseResponse =
        rest.exchange(
            "/api/v1/accounting/accounts",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("code", canonicalCode, "name", "Cash Lowercase", "type", "ASSET"), headers),
            Map.class);
    assertThat(createLowercaseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    String uppercaseVariant = canonicalCode.toUpperCase(Locale.ROOT);
    ResponseEntity<Map> createUppercaseVariantResponse =
        rest.exchange(
            "/api/v1/accounting/accounts",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("code", uppercaseVariant, "name", "Cash Uppercase", "type", "ASSET"),
                headers),
            Map.class);
    assertThat(createUppercaseVariantResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    Map<String, Object> duplicateErrorData = responseData(createUppercaseVariantResponse);
    assertThat(duplicateErrorData)
        .containsEntry("code", ErrorCode.BUSINESS_DUPLICATE_ENTRY.getCode())
        .containsEntry("path", "/api/v1/accounting/accounts");
    assertThat(String.valueOf(duplicateErrorData.get("reason")))
        .contains("already exists")
        .contains(uppercaseVariant);
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) duplicateErrorData.get("details");
    if (details != null) {
      assertThat(details).containsEntry("field", "code").containsEntry("code", uppercaseVariant);
    }

    Company persistedCompany = companyRepository.findById(company.getId()).orElseThrow();
    long accountCount =
        accountRepository.findByCompanyOrderByCodeAsc(persistedCompany).stream()
            .filter(account -> account.getCode().equalsIgnoreCase(canonicalCode))
            .count();
    assertThat(accountCount).isEqualTo(1L);
  }

  @Test
  void persistAccount_caseVariantConcurrentInsertFailsClosedWithSingleRow() {
    String companyCode = "COA-CI-RACE-" + shortId();
    Company company = dataSeeder.ensureCompany(companyCode, "Account CI Race " + companyCode);
    String canonicalCode = "race-cash-" + shortId().toLowerCase(Locale.ROOT);
    Long companyId = company.getId();

    CoderedConcurrencyHarness.RunResult<Long> result =
        CoderedConcurrencyHarness.run(
            2,
            2,
            Duration.ofSeconds(20),
            threadIndex -> () -> saveCaseVariantAccount(companyId, canonicalCode, threadIndex),
            CoderedRetry::isRetryable);

    long successCount =
        result.outcomes().stream()
            .filter(CoderedConcurrencyHarness.Outcome.Success.class::isInstance)
            .count();
    @SuppressWarnings("unchecked")
    List<CoderedConcurrencyHarness.Outcome.Failure<Long>> failures =
        result.outcomes().stream()
            .filter(CoderedConcurrencyHarness.Outcome.Failure.class::isInstance)
            .map(outcome -> (CoderedConcurrencyHarness.Outcome.Failure<Long>) outcome)
            .toList();

    assertThat(successCount).isEqualTo(1L);
    assertThat(failures).hasSize(1);
    assertThat(isCaseInsensitiveDuplicateFailure(failures.getFirst().error())).isTrue();

    Company persistedCompany = companyRepository.findById(companyId).orElseThrow();
    long accountCount =
        accountRepository.findByCompanyOrderByCodeAsc(persistedCompany).stream()
            .filter(account -> account.getCode().equalsIgnoreCase(canonicalCode))
            .count();
    assertThat(accountCount).isEqualTo(1L);
  }

  private Long saveCaseVariantAccount(Long companyId, String canonicalCode, int threadIndex) {
    Company company = companyRepository.findById(companyId).orElseThrow();
    Account account = new Account();
    account.setCompany(company);
    account.setCode(
        threadIndex == 0
            ? canonicalCode.toLowerCase(Locale.ROOT)
            : canonicalCode.toUpperCase(Locale.ROOT));
    account.setName("Concurrent Cash " + threadIndex);
    account.setType(AccountType.ASSET);
    return accountRepository.saveAndFlush(account).getId();
  }

  private boolean isCaseInsensitiveDuplicateFailure(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("uq_accounts_company_code_ci")
            || (normalized.contains("duplicate key")
                && normalized.contains("company_id")
                && normalized.contains("lower(code"))) {
          return true;
        }
      }
      cursor = cursor.getCause();
    }
    return false;
  }

  private HttpHeaders authHeaders(String email, String companyCode) {
    Map<String, Object> loginPayload =
        Map.of("email", email, "password", PASSWORD, "companyCode", companyCode);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(loginResponse.getBody()).isNotNull();

    Object accessToken = loginResponse.getBody().get("accessToken");
    if (accessToken == null && loginResponse.getBody().get("data") instanceof Map<?, ?> data) {
      accessToken = data.get("accessToken");
    }
    assertThat(accessToken).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(String.valueOf(accessToken));
    headers.set("X-Company-Code", companyCode);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseData(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
  }

  private String shortId() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }
}
