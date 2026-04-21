package com.bigbrightpaints.erp.core.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.AccessDeniedAuditMarker;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.MfaChallengeResponse;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class CoreFallbackExceptionHandlerTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void handleAccessDenied_usesPortalRoleActionMessageWhenAvailable() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "accounting.user", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTING"))));
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/dispatch/confirm");
    request.setRequestURI("/api/v1/dispatch/confirm");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleAccessDenied(new AccessDeniedException("denied"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo(
            "Factory must complete shipment confirmation from the dispatch workspace."
                + " Accounting can reconcile downstream order markers only after dispatch is"
                + " confirmed.");
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode())
        .containsEntry(
            "message",
            "Factory must complete shipment confirmation from the dispatch workspace."
                + " Accounting can reconcile downstream order markers only after dispatch is"
                + " confirmed.")
        .containsKey("traceId");
  }

  @Test
  void handleAccessDenied_fallsBackToGenericMessageWhenNoRoleSpecificGuidanceExists() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin.user", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/superadmin/settings");
    request.setRequestURI("/api/v1/superadmin/settings");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleAccessDenied(new AccessDeniedException("denied"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Access denied");
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode())
        .containsEntry("message", "Access denied")
        .containsKey("traceId");
  }

  @Test
  void handleAccessDenied_marksRequestAsAuditedWhenFallbackAuditRuns() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "accounting.user", "N/A", List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTING"))));
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    AuditService auditService = Mockito.mock(AuditService.class);
    ReflectionTestUtils.setField(handler, "auditService", auditService);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/dispatch/confirm");
    request.setRequestURI("/api/v1/dispatch/confirm");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    handler.handleAccessDenied(new AccessDeniedException("denied"), request);

    assertThat(AccessDeniedAuditMarker.isCurrentRequestAlreadyAudited(request)).isTrue();
    verify(auditService)
        .logAuthFailure(eq(AuditEvent.ACCESS_DENIED), any(String.class), isNull(), any(Map.class));
  }

  @Test
  void handleAuthenticationException_usesLockedContractForLockedUsers() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRequestURI("/api/v1/auth/login");
    request.setRemoteAddr("127.0.0.1");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleAuthenticationException(new LockedException("locked"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo(ErrorCode.AUTH_ACCOUNT_LOCKED.getDefaultMessage());
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.AUTH_ACCOUNT_LOCKED.getCode())
        .containsEntry("message", ErrorCode.AUTH_ACCOUNT_LOCKED.getDefaultMessage())
        .containsKey("traceId");
  }

  @Test
  void handleIllegalState_redactsInternalMessageFromFallbackResponse() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounting/post");
    request.setRequestURI("/api/v1/accounting/post");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleIllegalState(
            new IllegalStateException("raw sql state 23505 duplicate ledger mutation"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage());
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.BUSINESS_INVALID_STATE.getCode())
        .containsEntry("message", ErrorCode.BUSINESS_INVALID_STATE.getDefaultMessage())
        .containsKey("traceId");
    assertThat(response.getBody().data().values())
        .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("23505"));
  }

  @Test
  void handleRuntime_redactsInternalMessageFromFallbackResponse() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/export");
    request.setRequestURI("/api/v1/reports/export");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleRuntime(
            new RuntimeException("jdbc password=secret host=db-internal"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Internal error");
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.SYSTEM_INTERNAL_ERROR.getCode())
        .containsEntry("message", "An internal error occurred. Please try again later.")
        .containsKey("traceId")
        .containsKey("timestamp");
    assertThat(response.getBody().data().values())
        .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain("secret"));
  }

  @Test
  void handleCreditLimitExceeded_returnsUserFacingContract() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/accounting/settlements/dealers");
    request.setRequestURI("/api/v1/accounting/settlements/dealers");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleCreditLimitExceeded(
            new CreditLimitExceededException("Dealer credit limit exceeded"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Dealer credit limit exceeded");
    assertThat(response.getBody().data())
        .containsEntry("code", "CREDIT_LIMIT_EXCEEDED")
        .containsEntry("message", "Dealer credit limit exceeded")
        .containsKey("traceId");
  }

  @Test
  void handleMfaExceptions_returnCanonicalAuthResponses() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRequestURI("/api/v1/auth/login");

    ResponseEntity<ApiResponse<MfaChallengeResponse>> requiredResponse =
        handler.handleMfaRequired(new MfaRequiredException("MFA required"), request);
    assertThat(requiredResponse.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    assertThat(requiredResponse.getBody()).isNotNull();
    assertThat(requiredResponse.getBody().message())
        .isEqualTo(ErrorCode.AUTH_MFA_REQUIRED.getDefaultMessage());
    assertThat(requiredResponse.getBody().data()).isEqualTo(new MfaChallengeResponse(true));

    ResponseEntity<ApiResponse<Map<String, Object>>> invalidResponse =
        handler.handleInvalidMfa(new InvalidMfaException("invalid"), request);
    assertThat(invalidResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(invalidResponse.getBody()).isNotNull();
    assertThat(invalidResponse.getBody().data())
        .containsEntry("code", ErrorCode.AUTH_MFA_INVALID.getCode())
        .containsEntry("message", ErrorCode.AUTH_MFA_INVALID.getDefaultMessage())
        .containsKey("traceId");
  }

  @Test
  void handleAuthSecurityContract_preservesExplicitStatusAndDetails() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/superadmin/settings");
    request.setRequestURI("/api/v1/superadmin/settings");
    AuthSecurityContractException ex =
        new AuthSecurityContractException(HttpStatus.FORBIDDEN, "AUTH_SCOPE_DENIED", "Scope denied")
            .withDetail("scope", "admin");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleAuthSecurityContract(ex, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Scope denied");
    assertThat(response.getBody().data())
        .containsEntry("code", "AUTH_SCOPE_DENIED")
        .containsEntry("message", "Scope denied")
        .containsEntry("scope", "admin")
        .containsKey("traceId");
  }

  @Test
  void handleDataIntegrityViolation_mapsDuplicateConflicts() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/catalog/items");
    request.setRequestURI("/api/v1/catalog/items");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleDataIntegrityViolation(
            new DataIntegrityViolationException("duplicate key value violates unique constraint"),
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Operation failed");
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.BUSINESS_DUPLICATE_ENTRY.getCode())
        .containsEntry("message", "Duplicate entry found")
        .containsKey("traceId");
  }

  @Test
  void handleGenericException_returnsUnexpectedErrorEnvelope() {
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/unknown");
    request.setRequestURI("/api/v1/unknown");

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        handler.handleGenericException(new Exception("boom"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Unexpected error");
    assertThat(response.getBody().data())
        .containsEntry("code", ErrorCode.UNKNOWN_ERROR.getCode())
        .containsEntry("message", "An unexpected error occurred")
        .containsKey("traceId")
        .containsKey("timestamp");
  }
}
