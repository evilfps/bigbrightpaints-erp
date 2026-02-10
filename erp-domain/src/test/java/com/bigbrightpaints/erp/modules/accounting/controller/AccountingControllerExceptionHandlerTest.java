package com.bigbrightpaints.erp.modules.accounting.controller;

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

    @Test
    void handleApplicationException_returnsStructuredReasonPayload() {
        AccountingController controller = new AccountingController(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        AccountingController controller = new AccountingController(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        assertThat(body.data()).containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode());
        assertThat(body.data()).containsEntry("reason", "Payroll must be posted before payment");
        assertThat(body.data()).containsEntry("path", "/api/v1/accounting/payroll/payments");
        assertThat(body.data()).containsKey("traceId");
    }
}
