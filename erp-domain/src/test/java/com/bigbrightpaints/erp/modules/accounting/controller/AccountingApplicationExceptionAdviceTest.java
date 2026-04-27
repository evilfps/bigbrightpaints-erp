package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.auditaccess.AuditAccessService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@Tag("critical")
class AccountingApplicationExceptionAdviceTest {

  private static final String REPLAY_REASON_SUPPLIER =
      "Idempotency key already used for another supplier";
  private static final String REPLAY_REASON_DEALER =
      "Idempotency key already used for another dealer";
  private static final String REPLAY_REASON_PARTNER_TYPE =
      "Idempotency key already used for another partner type";

  @Test
  void handleApplicationException_returnsStructuredReasonPayload() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Supplier payable account AP-SKEINA requires a supplier context")
            .withDetail("accountCode", "AP-SKEINA");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/journal-entries");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ApiResponse<Map<String, Object>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.success()).isFalse();
    assertThat(body.message())
        .isEqualTo("Supplier payable account AP-SKEINA requires a supplier context");
    assertThat(body.data()).containsEntry("code", ErrorCode.VALIDATION_INVALID_REFERENCE.getCode());
    assertThat(body.data())
        .containsEntry("message", "Supplier payable account AP-SKEINA requires a supplier context");
    assertThat(body.data())
        .containsEntry("reason", "Supplier payable account AP-SKEINA requires a supplier context");
    assertThat(body.data()).containsEntry("path", "/api/v1/accounting/journal-entries");
    assertThat(body.data()).containsKey("traceId");
    assertThat(body.data()).containsKey("details");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) body.data().get("details");
    assertThat(details).containsEntry("accountCode", "AP-SKEINA");
  }

  @Test
  void handleApplicationException_keepsBadRequestContractWithStructuredPayload() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.BUSINESS_INVALID_STATE, "Payroll must be posted before payment");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/payroll/payments");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    ApiResponse<Map<String, Object>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.success()).isFalse();
    assertThat(body.data()).containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
    assertThat(body.data()).containsEntry("reason", "Payroll must be posted before payment");
    assertThat(body.data()).containsEntry("path", "/api/v1/accounting/payroll/payments");
    assertThat(body.data()).containsKey("traceId");
  }

  @Test
  void handleApplicationException_businessDuplicateMapsToConflictEnvelope() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.BUSINESS_DUPLICATE_ENTRY, "Account code 'AST-100' already exists");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/accounts");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    ApiResponse<Map<String, Object>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.success()).isFalse();
    assertThat(body.data())
        .containsEntry("code", ErrorCode.BUSINESS_DUPLICATE_ENTRY.getCode())
        .containsEntry("reason", "Account code 'AST-100' already exists")
        .containsEntry("path", "/api/v1/accounting/accounts");
  }

  @Test
  void handleApplicationException_preservesPartnerReplayDetailsForSupplierPath() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_SUPPLIER)
            .withDetail(
                IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-AP-RACE-PARTNER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/suppliers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.CONFLICT,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_SUPPLIER,
            "/api/v1/accounting/settlements/suppliers");
    Map<String, Object> details = requireDetails(body);
    assertPartnerReplayDetails(details, "IDEMP-AP-RACE-PARTNER", "SUPPLIER", 1L);
  }

  @Test
  void handleApplicationException_preservesPartnerReplayDetailsForDealerPath() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_DEALER)
            .withDetail(
                IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-DR-RACE-PARTNER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "DEALER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/dealers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.CONFLICT,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_DEALER,
            "/api/v1/accounting/settlements/dealers");
    Map<String, Object> details = requireDetails(body);
    assertPartnerReplayDetails(details, "IDEMP-DR-RACE-PARTNER", "DEALER", 1L);
  }

  @Test
  void handleApplicationException_preservesFallbackPartnerReplayReason() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_PARTNER_TYPE);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/partners");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.CONFLICT,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_PARTNER_TYPE,
            "/api/v1/accounting/settlements/partners");
    assertThat(body.data()).doesNotContainKey("details");
  }

  @Test
  void handleApplicationException_partnerReplayConflictWithoutDetailsOmitsDetailsKey() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_SUPPLIER);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/suppliers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.CONFLICT,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_SUPPLIER,
            "/api/v1/accounting/settlements/suppliers");
    assertThat(body.data()).doesNotContainKey("details");
  }

  @Test
  void handleApplicationException_internalConcurrencyFailureMapsToConflictEnvelope() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Sales journal reference already reserved but mapping not found");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/sales/returns");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    assertReplayErrorEnvelope(
        response,
        HttpStatus.CONFLICT,
        ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
        "Sales journal reference already reserved but mapping not found",
        "/api/v1/accounting/sales/returns");
  }

  @Test
  void handleApplicationException_invalidDiscrepancyTypeMapsToBadRequestEnvelope() {
    AccountingApplicationExceptionAdvice advice = advice();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Invalid reconciliation discrepancy type: BANK");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/reconciliation/discrepancies");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        advice.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.BAD_REQUEST,
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Invalid reconciliation discrepancy type: BANK",
            "/api/v1/accounting/reconciliation/discrepancies");
    assertThat(body.data())
        .containsEntry("message", "Invalid reconciliation discrepancy type: BANK");
  }

  @Test
  void supplierStatement_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/statements/suppliers/42").param("from", "2026-02-30"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid from date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.from").value("2026-02-30"));
  }

  @Test
  void supplierAging_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/aging/suppliers/42").param("asOf", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid asOf date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.asOf").value("not-a-date"));
  }

  @Test
  void transactionAudit_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/audit/transactions").param("from", "2026-02-30"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid from date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.from").value("2026-02-30"));
  }

  @Test
  void transactionAuditDetail_missingJournalEntryReturnsNotFoundEnvelope() throws Exception {
    AuditAccessService auditAccessService = mock(AuditAccessService.class);
    when(auditAccessService.getAccountingTransactionDetail(999L))
        .thenThrow(
            new ApplicationException(
                ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Journal entry not found"));

    accountingMvc(auditAccessService)
        .perform(get("/api/v1/accounting/audit/transactions/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Journal entry not found"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.BUSINESS_ENTITY_NOT_FOUND.getCode()));
  }

  @Test
  void balanceAsOf_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/accounts/42/balance/as-of").param("date", "bad-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid date date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.date").value("bad-date"));
  }

  @Test
  void trialBalanceAsOf_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/trial-balance/as-of").param("date", "2026-99-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid date date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.date").value("2026-99-01"));
  }

  @Test
  void accountActivity_invalidDateReturnsCanonicalEnvelope() throws Exception {
    accountingMvc()
        .perform(
            get("/api/v1/accounting/accounts/42/activity")
                .param("startDate", "2026-03-40")
                .param("endDate", "2026-03-31"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message")
                .value("Invalid account activity date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.startDate").value("2026-03-40"))
        .andExpect(jsonPath("$.data.details.endDate").value("2026-03-31"));
  }

  @Test
  void accountActivity_missingDateRangeReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(get("/api/v1/accounting/accounts/42/activity"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message")
                .value("Account activity requires startDate/endDate (or from/to) query parameters"))
        .andExpect(
            jsonPath("$.data.code").value(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD.getCode()));
  }

  @Test
  void compareBalances_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingMvc()
        .perform(
            get("/api/v1/accounting/accounts/42/balance/compare")
                .param("from", "2026-03-01")
                .param("to", "invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid to date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.to").value("invalid"));
  }

  @Test
  void configurationHealth_applicationExceptionUsesAccountingAdviceEnvelope() throws Exception {
    AccountingService accountingService = mock(AccountingService.class);
    when(accountingService.getConfigurationHealthReport())
        .thenThrow(
            new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE, "Configuration health is unavailable"));

    MockMvcBuilders.standaloneSetup(new AccountingConfigurationController(accountingService))
        .setControllerAdvice(advice(), globalExceptionHandler())
        .build()
        .perform(get("/api/v1/accounting/configuration/health"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Configuration health is unavailable"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.BUSINESS_INVALID_STATE.getCode()))
        .andExpect(jsonPath("$.data.path").value("/api/v1/accounting/configuration/health"));
  }

  private ApiResponse<Map<String, Object>> assertReplayErrorEnvelope(
      ResponseEntity<ApiResponse<Map<String, Object>>> response,
      HttpStatus status,
      ErrorCode code,
      String reason,
      String path) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    ApiResponse<Map<String, Object>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.success()).isFalse();
    assertThat(body.message()).isEqualTo(reason);
    assertThat(body.data()).containsEntry("code", code.getCode());
    assertThat(body.data()).containsEntry("reason", reason);
    assertThat(body.data()).containsEntry("path", path);
    assertThat(body.data()).containsKey("traceId");
    return body;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> requireDetails(ApiResponse<Map<String, Object>> body) {
    assertThat(body.data()).containsKey("details");
    return (Map<String, Object>) body.data().get("details");
  }

  private void assertPartnerReplayDetails(
      Map<String, Object> details, String idempotencyKey, String partnerType, Long partnerId) {
    assertThat(details)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
        .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
  }

  private AccountingApplicationExceptionAdvice advice() {
    return new AccountingApplicationExceptionAdvice(globalExceptionHandler());
  }

  private GlobalExceptionHandler globalExceptionHandler() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    ReflectionTestUtils.setField(handler, "activeProfile", "test");
    return handler;
  }

  private StatementReportController statementReportController() {
    return new StatementReportController(
        new StatementReportControllerSupport(
            mock(AccountingService.class),
            mock(SalesReturnService.class),
            mock(StatementService.class),
            mock(AuditService.class)));
  }

  private AccountingAuditController auditController() {
    return auditController(null);
  }

  private AccountingAuditController auditController(AuditAccessService auditAccessService) {
    return new AccountingAuditController(auditAccessService);
  }

  private MockMvc accountingMvc() {
    return accountingMvc(null);
  }

  private MockMvc accountingMvc(AuditAccessService auditAccessService) {
    return MockMvcBuilders.standaloneSetup(
            statementReportController(), auditController(auditAccessService))
        .setControllerAdvice(advice())
        .build();
  }
}
