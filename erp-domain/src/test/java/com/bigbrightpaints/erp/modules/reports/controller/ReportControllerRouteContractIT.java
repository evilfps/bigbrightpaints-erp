package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class ReportControllerRouteContractIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "REPORT-ROUTE-SEC";
  private static final String ACCOUNTING_EMAIL = "report-accounting@bbp.com";
  private static final String SALES_EMAIL = "report-sales@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private Dealer dealer;

  @BeforeEach
  void setup() {
    UserAccount portalUser =
        dataSeeder.ensureUser(
            "report-dealer@bbp.com",
            PASSWORD,
            "Report Dealer",
            COMPANY_CODE,
            List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL, PASSWORD, "Report Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(SALES_EMAIL, PASSWORD, "Report Sales", COMPANY_CODE, List.of("ROLE_SALES"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "REPORT-DEALER")
            .orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode("REPORT-DEALER");
    dealer.setName("Report Dealer");
    dealer.setCompanyName("Report Dealer Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setCreditLimit(new BigDecimal("100000.00"));
    dealer.setPortalUser(portalUser);
    dealerRepository.saveAndFlush(dealer);
  }

  @Test
  void reportController_exposes_only_canonical_report_paths() {
    Map<String, Set<String>> patternsByMethod = new LinkedHashMap<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (mapping, handlerMethod) -> {
              if (!ReportController.class.equals(handlerMethod.getBeanType())) {
                return;
              }
              patternsByMethod
                  .computeIfAbsent(handlerMethod.getMethod().getName(), ignored -> new TreeSet<>())
                  .addAll(extractPatterns(mapping));
            });

    assertThat(patternsByMethod)
        .containsEntry("agedDebtors", Set.of("/api/v1/reports/aged-debtors"))
        .containsEntry("balanceSheetHierarchy", Set.of("/api/v1/reports/balance-sheet/hierarchy"))
        .containsEntry(
            "incomeStatementHierarchy", Set.of("/api/v1/reports/income-statement/hierarchy"))
        .containsEntry("agedReceivables", Set.of("/api/v1/reports/aging/receivables"));

    Set<String> allPatterns =
        patternsByMethod.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(allPatterns).noneMatch(path -> path.startsWith("/api/v1/accounting/reports/"));
    assertThat(allPatterns).noneMatch(path -> path.contains("/aging/dealer/"));
    assertThat(allPatterns).noneMatch(path -> path.contains("/dso/dealer/"));
  }

  @Test
  void retiredDealerReportAliases_areNotFoundForAccounting() {
    HttpHeaders headers = authHeaders();
    List<String> retiredPaths =
        List.of(
            "/api/v1/reports/aging/dealer/" + dealer.getId(),
            "/api/v1/reports/aging/dealer/" + dealer.getId() + "/detailed",
            "/api/v1/reports/dso/dealer/" + dealer.getId());

    for (String path : retiredPaths) {
      ResponseEntity<Map> response =
          rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void settlementReceiptMutations_forbidSalesBeforeBodyValidation() {
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL);
    List<String> sensitiveAccountingWritePaths =
        List.of(
            "/api/v1/accounting/receipts/dealer",
            "/api/v1/accounting/receipts/dealer/hybrid",
            "/api/v1/accounting/settlements/dealers",
            "/api/v1/accounting/dealers/" + dealer.getId() + "/auto-settle",
            "/api/v1/accounting/settlements/suppliers",
            "/api/v1/accounting/suppliers/1/auto-settle");

    for (String path : sensitiveAccountingWritePaths) {
      ResponseEntity<Map> denied =
          rest.exchange(path, HttpMethod.POST, new HttpEntity<>(Map.of(), salesHeaders), Map.class);
      assertThat(denied.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void settlementReceiptMutations_accountingStillReachesRequestValidation() {
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL);
    List<String> sensitiveAccountingWritePaths =
        List.of(
            "/api/v1/accounting/receipts/dealer",
            "/api/v1/accounting/receipts/dealer/hybrid",
            "/api/v1/accounting/settlements/dealers",
            "/api/v1/accounting/dealers/" + dealer.getId() + "/auto-settle",
            "/api/v1/accounting/settlements/suppliers",
            "/api/v1/accounting/suppliers/1/auto-settle");

    for (String path : sensitiveAccountingWritePaths) {
      ResponseEntity<Map> validationFailure =
          rest.exchange(
              path, HttpMethod.POST, new HttpEntity<>(Map.of(), accountingHeaders), Map.class);
      assertThat(validationFailure.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  private Set<String> extractPatterns(RequestMappingInfo mapping) {
    if (mapping.getPathPatternsCondition() != null) {
      return mapping.getPathPatternsCondition().getPatternValues();
    }
    if (mapping.getPatternsCondition() != null) {
      return new TreeSet<>(mapping.getPatternsCondition().getPatterns());
    }
    return Set.of();
  }

  private HttpHeaders authHeaders() {
    return authHeaders(ACCOUNTING_EMAIL);
  }

  private HttpHeaders authHeaders(String email) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }
}
