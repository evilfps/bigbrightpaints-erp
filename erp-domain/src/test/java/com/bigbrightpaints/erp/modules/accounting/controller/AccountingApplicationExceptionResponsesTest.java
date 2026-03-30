package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@Tag("critical")
class AccountingApplicationExceptionResponsesTest {

  @Test
  void badRequest_handlesNullRequestPathAndOmitsEmptyDetails() {
    ApplicationException ex =
        new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid journal entry");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        AccountingApplicationExceptionResponses.badRequest(ex, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.VALIDATION_INVALID_INPUT.getCode())
        .containsEntry("message", "Invalid journal entry")
        .containsEntry("reason", "Invalid journal entry")
        .containsEntry("path", null)
        .containsKey("traceId")
        .doesNotContainKey("details");
  }

  @Test
  void mappedStatus_includesStructuredDetailsWhenPresent() {
    ApplicationException ex =
        new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Invalid journal entry")
            .withDetail("field", "reference");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        AccountingApplicationExceptionResponses.mappedStatus(ex, new MockHttpServletRequest());

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).containsKey("details");
    @SuppressWarnings("unchecked")
    Map<String, Object> details = (Map<String, Object>) response.getBody().data().get("details");
    assertThat(details).containsEntry("field", "reference");
  }

  @ParameterizedTest
  @MethodSource("explicitStatusMappings")
  void mappedStatus_appliesExplicitOverrides(ErrorCode errorCode, HttpStatus expectedStatus) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/accounting/audit");
    ApplicationException ex = new ApplicationException(errorCode, "boom");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        AccountingApplicationExceptionResponses.mappedStatus(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).containsEntry("path", "/api/v1/accounting/audit");
  }

  private static Stream<Arguments> explicitStatusMappings() {
    return Stream.of(
        Arguments.of(ErrorCode.BUSINESS_ENTITY_NOT_FOUND, HttpStatus.NOT_FOUND),
        Arguments.of(ErrorCode.FILE_NOT_FOUND, HttpStatus.NOT_FOUND),
        Arguments.of(ErrorCode.AUTH_MFA_REQUIRED, HttpStatus.PRECONDITION_REQUIRED),
        Arguments.of(ErrorCode.MODULE_DISABLED, HttpStatus.FORBIDDEN),
        Arguments.of(ErrorCode.SYSTEM_RATE_LIMIT_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS));
  }

  @ParameterizedTest
  @MethodSource("prefixStatusMappings")
  void mappedStatus_mapsErrorCodePrefixes(ErrorCode errorCode, HttpStatus expectedStatus) {
    ApplicationException ex = new ApplicationException(errorCode, "boom");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        AccountingApplicationExceptionResponses.mappedStatus(ex, new MockHttpServletRequest());

    assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
  }

  private static Stream<Arguments> prefixStatusMappings() {
    return Stream.of(
        Arguments.of(ErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED),
        Arguments.of(ErrorCode.VALIDATION_INVALID_INPUT, HttpStatus.BAD_REQUEST),
        Arguments.of(ErrorCode.BUSINESS_INVALID_STATE, HttpStatus.CONFLICT),
        Arguments.of(ErrorCode.CONCURRENCY_CONFLICT, HttpStatus.CONFLICT),
        Arguments.of(ErrorCode.DUPLICATE_ENTITY, HttpStatus.CONFLICT),
        Arguments.of(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
        Arguments.of(ErrorCode.INTEGRATION_TIMEOUT, HttpStatus.BAD_GATEWAY),
        Arguments.of(ErrorCode.FILE_UPLOAD_FAILED, HttpStatus.UNPROCESSABLE_ENTITY),
        Arguments.of(ErrorCode.UNKNOWN_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Test
  void determineHttpStatus_defaultsToInternalServerErrorWhenErrorCodeIsNull() {
    HttpStatus status =
        ReflectionTestUtils.invokeMethod(
            AccountingApplicationExceptionResponses.class, "determineHttpStatus", (Object) null);

    assertThat(status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
