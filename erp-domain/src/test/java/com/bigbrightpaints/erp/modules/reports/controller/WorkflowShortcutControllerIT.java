package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class WorkflowShortcutControllerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "RPT-WORKFLOW";
  private static final String ACCOUNTING_EMAIL = "workflow-shortcuts-accounting@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountingPeriodRepository accountingPeriodRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;

  private Company company;
  private AccountingPeriod period;
  private Account bankAccount;
  private HttpHeaders headers;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Workflow Shortcuts Accounting",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));

    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    period = ensureOpenCurrentPeriod();
    bankAccount = ensureAssetBankAccount();
    headers = authHeaders();
  }

  @Test
  void workflowShortcuts_exposeConnectedBusinessFlowsAndExplicitDraftCapability() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/reports/workflow-shortcuts",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();

    Map<String, Object> data = castMap(response.getBody().get("data"));
    assertThat(String.valueOf(data.get("connectedBusinessFlowModel")))
        .containsIgnoringCase("connected business flows");

    List<Map<String, Object>> workflows = castListOfMap(data.get("workflows"));
    Map<String, Map<String, Object>> workflowByKey =
        workflows.stream()
            .collect(
                Collectors.toMap(
                    workflow -> String.valueOf(workflow.get("workflowKey")), Function.identity()));

    assertThat(workflowByKey.keySet())
        .containsExactlyInAnyOrder(
            "ORDER_TO_INVOICE", "PROCURE_TO_PAY", "PERIOD_CLOSE_RECONCILIATION");

    List<String> orderToInvoiceRoutes = stepRoutes(workflowByKey.get("ORDER_TO_INVOICE"), "steps");
    assertThat(orderToInvoiceRoutes)
        .containsExactly(
            "/api/v1/sales/orders",
            "/api/v1/sales/orders/{id}/confirm",
            "/api/v1/dispatch/confirm",
            "/api/v1/invoices?orderId={salesOrderId}");

    List<String> procureToPayRoutes = stepRoutes(workflowByKey.get("PROCURE_TO_PAY"), "steps");
    assertThat(procureToPayRoutes)
        .containsExactly(
            "/api/v1/suppliers",
            "/api/v1/purchasing/purchase-orders",
            "/api/v1/purchasing/purchase-orders/{id}/approve",
            "/api/v1/purchasing/goods-receipts",
            "/api/v1/purchasing/raw-material-purchases",
            "/api/v1/accounting/suppliers/{supplierId}/auto-settle");

    Map<String, Object> periodClose = workflowByKey.get("PERIOD_CLOSE_RECONCILIATION");
    assertThat(periodClose.get("draftCapable")).isEqualTo(Boolean.TRUE);
    List<Map<String, Object>> draftCapabilities =
        castListOfMap(periodClose.get("draftCapabilities"));
    assertThat(draftCapabilities).hasSize(1);
    Map<String, Object> bankSessionDraft = draftCapabilities.getFirst();
    assertThat(bankSessionDraft.get("draftKey")).isEqualTo("BANK_RECONCILIATION_SESSION");
    assertThat(bankSessionDraft.get("saveRoute"))
        .isEqualTo("/api/v1/accounting/reconciliation/bank/sessions");
    assertThat(bankSessionDraft.get("resumeRoute"))
        .isEqualTo("/api/v1/accounting/reconciliation/bank/sessions/{sessionId}");
    assertThat(bankSessionDraft.get("promoteRoute"))
        .isEqualTo("/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete");
    assertThat(bankSessionDraft.get("pendingStatus")).isEqualTo("IN_PROGRESS");
    assertThat(bankSessionDraft.get("promotedStatus")).isEqualTo("COMPLETED");
  }

  @Test
  void declaredDraftCapability_saveAndResumeRemainSideEffectFreeUntilPromotion() {
    long journalCountBefore =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).size();
    assertPeriodBankReconciled(period.getId(), false);

    Map<String, Object> saveDraftRequest = new LinkedHashMap<>();
    saveDraftRequest.put("bankAccountId", bankAccount.getId());
    saveDraftRequest.put("statementDate", period.getStartDate().plusDays(1));
    saveDraftRequest.put("statementEndingBalance", "1000.00");
    saveDraftRequest.put("accountingPeriodId", period.getId());
    saveDraftRequest.put("note", "period-close draft");

    ResponseEntity<Map> saveResponse =
        rest.exchange(
            "/api/v1/accounting/reconciliation/bank/sessions",
            HttpMethod.POST,
            new HttpEntity<>(saveDraftRequest, headers),
            Map.class);

    assertThat(saveResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(saveResponse.getBody()).isNotNull();
    Map<String, Object> saveData = castMap(saveResponse.getBody().get("data"));
    Long sessionId = asLong(saveData.get("sessionId"));
    assertThat(sessionId).isNotNull();
    assertThat(saveData.get("status")).isEqualTo("IN_PROGRESS");

    ResponseEntity<Map> resumeResponse =
        rest.exchange(
            "/api/v1/accounting/reconciliation/bank/sessions/" + sessionId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(resumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resumeResponse.getBody()).isNotNull();
    Map<String, Object> resumeData = castMap(resumeResponse.getBody().get("data"));
    assertThat(asLong(resumeData.get("sessionId"))).isEqualTo(sessionId);
    assertThat(resumeData.get("status")).isEqualTo("IN_PROGRESS");

    long journalCountAfterSaveResume =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).size();
    assertThat(journalCountAfterSaveResume).isEqualTo(journalCountBefore);
    assertPeriodBankReconciled(period.getId(), false);

    ResponseEntity<Map> promoteResponse =
        rest.exchange(
            "/api/v1/accounting/reconciliation/bank/sessions/" + sessionId + "/complete",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("note", "promote draft", "accountingPeriodId", period.getId()), headers),
            Map.class);

    assertThat(promoteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(promoteResponse.getBody()).isNotNull();
    Map<String, Object> promotedData = castMap(promoteResponse.getBody().get("data"));
    assertThat(asLong(promotedData.get("sessionId"))).isEqualTo(sessionId);
    assertThat(promotedData.get("status")).isEqualTo("COMPLETED");
    assertPeriodBankReconciled(period.getId(), true);

    long journalCountAfterPromotion =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).size();
    assertThat(journalCountAfterPromotion).isEqualTo(journalCountBefore);
  }

  private List<String> stepRoutes(Map<String, Object> flow, String stepsField) {
    return castListOfMap(flow.get(stepsField)).stream()
        .map(step -> String.valueOf(step.get("route")))
        .toList();
  }

  private AccountingPeriod ensureOpenCurrentPeriod() {
    LocalDate today = LocalDate.now();
    AccountingPeriod existing =
        accountingPeriodRepository
            .findByCompanyAndYearAndMonth(company, today.getYear(), today.getMonthValue())
            .orElseGet(AccountingPeriod::new);
    existing.setCompany(company);
    existing.setYear(today.getYear());
    existing.setMonth(today.getMonthValue());
    existing.setStartDate(today.withDayOfMonth(1));
    existing.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
    existing.setStatus(AccountingPeriodStatus.OPEN);
    existing.setBankReconciled(false);
    existing.setBankReconciledAt(null);
    existing.setBankReconciledBy(null);
    existing.setClosingJournalEntryId(null);
    return accountingPeriodRepository.saveAndFlush(existing);
  }

  private Account ensureAssetBankAccount() {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, "WF-BANK")
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode("WF-BANK");
              account.setName("Workflow Draft Bank");
              account.setType(AccountType.ASSET);
              return accountRepository.saveAndFlush(account);
            });
  }

  private void assertPeriodBankReconciled(Long periodId, boolean expectedBankReconciled) {
    ResponseEntity<Map> periodsResponse =
        rest.exchange(
            "/api/v1/accounting/periods", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(periodsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(periodsResponse.getBody()).isNotNull();
    List<Map<String, Object>> periods = castListOfMap(periodsResponse.getBody().get("data"));

    Map<String, Object> targetPeriod =
        periods.stream()
            .filter(periodMap -> periodId.equals(asLong(periodMap.get("id"))))
            .findFirst()
            .orElseThrow();

    assertThat(targetPeriod.get("status")).isEqualTo("OPEN");
    assertThat(targetPeriod.get("bankReconciled")).isEqualTo(expectedBankReconciled);
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> payload =
        Map.of(
            "email", ACCOUNTING_EMAIL,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.setBearerAuth(String.valueOf(login.getBody().get("accessToken")));
    authHeaders.set("X-Company-Code", COMPANY_CODE);
    authHeaders.setContentType(MediaType.APPLICATION_JSON);
    return authHeaders;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castListOfMap(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private Long asLong(Object value) {
    if (value == null) {
      return null;
    }
    return Long.valueOf(String.valueOf(value));
  }
}
