package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingControllerExceptionHandlerTest {

    private static final String REPLAY_REASON_SUPPLIER = "Idempotency key already used for another supplier";
    private static final String REPLAY_REASON_DEALER = "Idempotency key already used for another dealer";
    private static final String REPLAY_REASON_PARTNER_TYPE = "Idempotency key already used for another partner type";

    @Test
    void handleApplicationException_returnsStructuredReasonPayload() {
        AccountingController controller = controller();
        ApplicationException ex = new ApplicationException(
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
        assertThat(body.message()).isEqualTo("Supplier payable account AP-SKEINA requires a supplier context");
        assertThat(body.data()).containsEntry("code", ErrorCode.VALIDATION_INVALID_REFERENCE.getCode());
        assertThat(body.data()).containsEntry("message", "Supplier payable account AP-SKEINA requires a supplier context");
        assertThat(body.data()).containsEntry("reason", "Supplier payable account AP-SKEINA requires a supplier context");
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
        ApplicationException ex = new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE,
                "Payroll must be posted before payment");
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
    void handleApplicationException_fallsClosedToErrorCodeDefaultMessageWhenReasonBlank() {
        AccountingController controller = controller();
        ApplicationException ex = new ApplicationException(
                ErrorCode.BUSINESS_INVALID_STATE,
                "   ");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/periods/17/close");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage());
        assertThat(body.data()).containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        assertThat(body.data()).containsEntry("reason", ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage());
        assertThat(body.data()).containsEntry("path", "/api/v1/accounting/periods/17/close");
        assertThat(body.data()).containsKey("traceId");
    }

    @Test
    void handleApplicationException_failsClosedToUnknownErrorCodeWhenCodeMissing() {
        AccountingController controller = controller();
        ApplicationException ex = new ApplicationException(
                null,
                (String) null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/workflows/post");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo(ErrorCode.UNKNOWN_ERROR.getDefaultMessage());
        assertThat(body.data()).containsEntry("code", ErrorCode.UNKNOWN_ERROR.getCode());
        assertThat(body.data()).containsEntry("reason", ErrorCode.UNKNOWN_ERROR.getDefaultMessage());
        assertThat(body.data()).containsEntry("path", "/api/v1/accounting/workflows/post");
        assertThat(body.data()).containsKey("traceId");
    }

    @Test
    void handleApplicationException_preservesPartnerReplayDetailsForSupplierPath() {
        AccountingController controller = controller();
        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                REPLAY_REASON_SUPPLIER)
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-AP-RACE-PARTNER")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "SUPPLIER")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/settlements/suppliers");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = assertReplayErrorEnvelope(
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
        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                REPLAY_REASON_DEALER)
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, "IDEMP-DR-RACE-PARTNER")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, "DEALER")
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, 1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/settlements/dealers");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = assertReplayErrorEnvelope(
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
        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                REPLAY_REASON_PARTNER_TYPE);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/settlements/partners");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = assertReplayErrorEnvelope(
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
        ApplicationException ex = new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                REPLAY_REASON_SUPPLIER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/settlements/suppliers");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = assertReplayErrorEnvelope(
                response,
                HttpStatus.BAD_REQUEST,
                ErrorCode.CONCURRENCY_CONFLICT,
                REPLAY_REASON_SUPPLIER,
                "/api/v1/accounting/settlements/suppliers");
        assertThat(body.data()).doesNotContainKey("details");
    }

    @Test
    void handleIllegalStateException_preservesFailClosedReasonWithConflictStatus() {
        AccountingController controller = controller();
        IllegalStateException ex = new IllegalStateException("Uninvoiced goods receipts exist in this period (2)");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/periods/17/close");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleIllegalStateException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Uninvoiced goods receipts exist in this period (2)");
        assertThat(body.data()).containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        assertThat(body.data()).containsEntry("message", "Uninvoiced goods receipts exist in this period (2)");
        assertThat(body.data()).containsEntry("reason", "Uninvoiced goods receipts exist in this period (2)");
        assertThat(body.data()).containsEntry("path", "/api/v1/accounting/periods/17/close");
        assertThat(body.data()).containsKey("traceId");
    }

    @Test
    void handleIllegalStateException_usesDeterministicFallbackWhenReasonBlank() {
        AccountingController controller = controller();
        IllegalStateException ex = new IllegalStateException("  ");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/accounting/periods/17/close");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.handleIllegalStateException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("Invalid accounting state");
        assertThat(body.data()).containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        assertThat(body.data()).containsEntry("reason", "Invalid accounting state");
        assertThat(body.data()).containsEntry("path", "/api/v1/accounting/periods/17/close");
        assertThat(body.data()).containsKey("traceId");
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

    private void assertPartnerReplayDetails(Map<String, Object> details,
                                            String idempotencyKey,
                                            String partnerType,
                                            Long partnerId) {
        assertThat(details)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType)
                .containsEntry(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
    }

    private AccountingController controller() {
        return new AccountingController(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
