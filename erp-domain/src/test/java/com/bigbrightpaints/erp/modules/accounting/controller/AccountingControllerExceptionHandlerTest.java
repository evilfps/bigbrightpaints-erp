package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("critical")
class AccountingControllerExceptionHandlerTest {

  private static final String REPLAY_REASON_SUPPLIER =
      "Idempotency key already used for another supplier";
  private static final String REPLAY_REASON_DEALER =
      "Idempotency key already used for another dealer";
  private static final String REPLAY_REASON_PARTNER_TYPE =
      "Idempotency key already used for another partner type";

  @Test
  void handleApplicationException_returnsStructuredReasonPayload() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(
                ErrorCode.VALIDATION_INVALID_REFERENCE,
                "Supplier payable account AP-SKEINA requires a supplier context")
            .withDetail("accountCode", "AP-SKEINA");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/journal-entries");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

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
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.BUSINESS_INVALID_STATE, "Payroll must be posted before payment");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/payroll/payments");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

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
  void handleApplicationException_preservesPartnerReplayDetailsForSupplierPath() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_SUPPLIER)
            .withDetail(
                IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-AP-RACE-PARTNER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/suppliers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.BAD_REQUEST,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_SUPPLIER,
            "/api/v1/accounting/settlements/suppliers");
    Map<String, Object> details = requireDetails(body);
    assertPartnerReplayDetails(details, "IDEMP-AP-RACE-PARTNER", "SUPPLIER", 1L);
  }

  @Test
  void handleApplicationException_preservesPartnerReplayDetailsForDealerPath() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_DEALER)
            .withDetail(
                IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-DR-RACE-PARTNER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "DEALER")
            .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/dealers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.BAD_REQUEST,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_DEALER,
            "/api/v1/accounting/settlements/dealers");
    Map<String, Object> details = requireDetails(body);
    assertPartnerReplayDetails(details, "IDEMP-DR-RACE-PARTNER", "DEALER", 1L);
  }

  @Test
  void handleApplicationException_preservesFallbackPartnerReplayReason() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_PARTNER_TYPE);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/partners");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.BAD_REQUEST,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_PARTNER_TYPE,
            "/api/v1/accounting/settlements/partners");
    assertThat(body.data()).doesNotContainKey("details");
  }

  @Test
  void handleApplicationException_partnerReplayConflictWithoutDetailsOmitsDetailsKey() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, REPLAY_REASON_SUPPLIER);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/settlements/suppliers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

    ApiResponse<Map<String, Object>> body =
        assertReplayErrorEnvelope(
            response,
            HttpStatus.BAD_REQUEST,
            ErrorCode.CONCURRENCY_CONFLICT,
            REPLAY_REASON_SUPPLIER,
            "/api/v1/accounting/settlements/suppliers");
    assertThat(body.data()).doesNotContainKey("details");
  }

  @Test
  void handleApplicationException_invalidDiscrepancyTypeMapsToBadRequestEnvelope() {
    AccountingController controller = controller();
    ApplicationException ex =
        new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Invalid reconciliation discrepancy type: BANK");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/reconciliation/discrepancies");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.handleApplicationException(ex, request);

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
    accountingControllerMvc()
        .perform(get("/api/v1/accounting/statements/suppliers/42").param("from", "2026-02-30"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Invalid from date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.from").value("2026-02-30"));
  }

  @Test
  void supplierAging_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingControllerMvc()
        .perform(get("/api/v1/accounting/aging/suppliers/42").param("asOf", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Invalid asOf date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.asOf").value("not-a-date"));
  }

  @Test
  void auditDigest_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingControllerMvc()
        .perform(get("/api/v1/accounting/audit/digest").param("to", "2026-13-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Invalid to date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.to").value("2026-13-01"));
  }

  @Test
  void transactionAudit_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingControllerMvc()
        .perform(get("/api/v1/accounting/audit/transactions").param("from", "2026-02-30"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid from date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.from").value("2026-02-30"));
  }

  @Test
  void balanceAsOf_invalidDateReturnsValidationEnvelope() throws Exception {
    accountingControllerMvc()
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
    accountingControllerMvc()
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
    accountingControllerMvc()
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
    accountingControllerMvc()
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
    accountingControllerMvc()
        .perform(
            get("/api/v1/accounting/accounts/42/balance/compare")
                .param("date1", "2026-03-01")
                .param("date2", "invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message").value("Invalid date2 date format; expected ISO date yyyy-MM-dd"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_INVALID_DATE.getCode()))
        .andExpect(jsonPath("$.data.details.date2").value("invalid"));
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

  private AccountingController controller() {
    return new AccountingController(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null);
  }

  private MockMvc accountingControllerMvc() {
    return MockMvcBuilders.standaloneSetup(controller())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }
}
