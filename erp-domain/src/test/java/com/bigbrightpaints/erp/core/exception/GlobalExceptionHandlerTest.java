package com.bigbrightpaints.erp.core.exception;

import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void productionProfilesHideDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev, production");

        ApplicationException ex = new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "invalid")
                .withDetail("field", "value");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).doesNotContainKey("details");
    }

    @Test
    void nonProductionProfilesIncludeDetails() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "dev,qa");

        ApplicationException ex = new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "invalid")
                .withDetail("field", "value");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleApplicationException(ex, request);

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsKey("details");
        assertThat(body.data().get("details"))
                .isInstanceOf(Map.class);
    }

    @Test
    void illegalArgumentInProductionReturnsReadableReason() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        setActiveProfile(handler, "prod,seed");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/hr/employees");

        IllegalArgumentException ex = new IllegalArgumentException(
                "No enum constant com.bigbrightpaints.erp.modules.hr.domain.Employee.PaymentSchedule.");

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleIllegalArgument(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiResponse<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("Invalid option provided");
        assertThat(body.data()).containsEntry("reason", "Invalid option provided");
    }

    private static void setActiveProfile(GlobalExceptionHandler handler, String value) throws Exception {
        Field field = GlobalExceptionHandler.class.getDeclaredField("activeProfile");
        field.setAccessible(true);
        field.set(handler, value);
    }
}
