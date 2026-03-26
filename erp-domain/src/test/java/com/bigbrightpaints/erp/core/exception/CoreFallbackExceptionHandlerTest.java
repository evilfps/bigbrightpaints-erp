package com.bigbrightpaints.erp.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class CoreFallbackExceptionHandlerTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void handleAccessDenied_usesPortalRoleActionMessageWhenAvailable() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "accounting.user",
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTING"))));
    CoreFallbackExceptionHandler handler = new CoreFallbackExceptionHandler();
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/dispatch/confirm");
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
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/settings");
    request.setRequestURI("/api/v1/admin/settings");

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
}
