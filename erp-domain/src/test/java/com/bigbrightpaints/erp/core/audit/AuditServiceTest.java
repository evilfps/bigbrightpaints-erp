package com.bigbrightpaints.erp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

import jakarta.servlet.http.HttpServletRequest;

class AuditServiceTest {

  private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
  private final CompanyRepository companyRepository = mock(CompanyRepository.class);
  private final AuditService auditService = createService();

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void logEvent_recycledRequestContext_doesNotFail() {
    HttpServletRequest recycledRequest = mock(HttpServletRequest.class);
    when(recycledRequest.getHeader(anyString()))
        .thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getMethod()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getRequestURI()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getRemoteAddr()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getSession(false))
        .thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getAttribute(anyString()))
        .thenThrow(new IllegalStateException("request recycled"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(recycledRequest));

    assertThatCode(
            () ->
                auditService.logEvent(
                    AuditEvent.SECURITY_ALERT, AuditStatus.WARNING, Map.of("k", "v")))
        .doesNotThrowAnyException();

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getEventType()).isEqualTo(AuditEvent.SECURITY_ALERT);
    assertThat(saved.getStatus()).isEqualTo(AuditStatus.WARNING);
    assertThat(saved.getIpAddress()).isNull();
    assertThat(saved.getRequestMethod()).isNull();
    assertThat(saved.getRequestPath()).isNull();
  }

  @Test
  void logEvent_capturesRequestMetadataFromCallerThread() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.11");
    when(request.getHeader("User-Agent")).thenReturn("erp-test-agent");
    when(request.getHeader("X-Trace-Id")).thenReturn("trace-123");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/api/v1/accounting/journal");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(companyRepository.findByCodeIgnoreCase("42")).thenReturn(Optional.empty());
    when(companyRepository.findById(42L)).thenReturn(Optional.of(companyWithId(42L, "COMP-42")));
    CompanyContextHolder.setCompanyCode("42");

    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "auditor", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    SecurityContextHolder.setContext(securityContext);

    auditService.logEvent(AuditEvent.DATA_CREATE, AuditStatus.SUCCESS, Map.of("source", "test"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isEqualTo(42L);
    assertThat(saved.getUsername()).isEqualTo("auditor");
    assertThat(saved.getIpAddress()).isEqualTo("203.0.113.11");
    assertThat(saved.getUserAgent()).isEqualTo("erp-test-agent");
    assertThat(saved.getRequestMethod()).isEqualTo("POST");
    assertThat(saved.getRequestPath()).isEqualTo("/api/v1/accounting/journal");
    assertThat(saved.getTraceId()).isEqualTo("trace-123");
    assertThat(saved.getMetadata()).containsEntry("source", "test");
  }

  @Test
  void logAuthSuccess_withoutSecurityOrRequestContext_preservesActorAndCompanyOverride() {
    Company company = companyWithId(88L, "COMP-A");
    when(companyRepository.findByCodeIgnoreCase("COMP-A")).thenReturn(Optional.of(company));

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "alice", "COMP-A", Map.of("authChannel", "password"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getEventType()).isEqualTo(AuditEvent.LOGIN_SUCCESS);
    assertThat(saved.getStatus()).isEqualTo(AuditStatus.SUCCESS);
    assertThat(saved.getUsername()).isEqualTo("alice");
    assertThat(saved.getUserId()).isEqualTo("alice");
    assertThat(saved.getCompanyId()).isEqualTo(88L);
    assertThat(saved.getMetadata()).containsEntry("authChannel", "password");
  }

  @Test
  void logAuthSuccess_withAuthenticatedPrincipalPrefersImmutablePublicIdForUserId() {
    Company company = companyWithId(88L, "COMP-A");
    when(companyRepository.findByCodeIgnoreCase("COMP-A")).thenReturn(Optional.of(company));
    UserAccount authenticatedUser =
        new UserAccount("actor@bbp.com", "COMP-A", "hash", "Authenticated Actor");

    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new UserPrincipal(authenticatedUser), "n/a", java.util.List.of()));
    SecurityContextHolder.setContext(securityContext);

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "actor@bbp.com", "COMP-A", Map.of("authChannel", "password"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getUsername()).isEqualTo("actor@bbp.com");
    assertThat(saved.getUserId()).isEqualTo(authenticatedUser.getPublicId().toString());
    assertThat(saved.getCompanyId()).isEqualTo(88L);
  }

  @Test
  void logAuthSuccess_withDifferentOverrideAndAuthenticatedPrincipalKeepsOverrideIdentity() {
    Company company = companyWithId(88L, "COMP-A");
    when(companyRepository.findByCodeIgnoreCase("COMP-A")).thenReturn(Optional.of(company));
    UserAccount authenticatedUser =
        new UserAccount("principal@bbp.com", "COMP-A", "hash", "Authenticated Actor");

    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new UserPrincipal(authenticatedUser), "n/a", java.util.List.of()));
    SecurityContextHolder.setContext(securityContext);

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "override@bbp.com", "COMP-A", Map.of("authChannel", "password"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getUsername()).isEqualTo("override@bbp.com");
    assertThat(saved.getUserId()).isEqualTo("override@bbp.com");
    assertThat(saved.getCompanyId()).isEqualTo(88L);
  }

  @Test
  void logAuthFailure_numericCompanyCodePrefersCodeResolutionOverRawId() {
    Company numericCodeCompany = companyWithId(7L, "1001");
    when(companyRepository.findByCodeIgnoreCase("1001"))
        .thenReturn(Optional.of(numericCodeCompany));

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE,
        "numeric-code-user",
        "1001",
        Map.of("reason", "numeric-company-code"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isEqualTo(7L);
    verify(companyRepository, never()).findById(1001L);
  }

  @Test
  void logAuthFailure_unknownNumericCompanyCodeDoesNotPersistPhantomCompanyId() {
    when(companyRepository.findByCodeIgnoreCase("404")).thenReturn(Optional.empty());

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE,
        "numeric-miss-user",
        "404",
        Map.of("reason", "unknown-numeric-company"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata())
        .containsEntry("reason", "unknown-numeric-company")
        .containsEntry("authCompanyToken", "404")
        .containsEntry("authCompanyResolution", "UNRESOLVED");
    verify(companyRepository, never()).findById(404L);
  }

  @Test
  void logAuthFailure_unknownNumericCompanyCodeDoesNotFallbackToNumericCompanyId() {
    when(companyRepository.findByCodeIgnoreCase("404")).thenReturn(Optional.empty());
    when(companyRepository.findById(404L))
        .thenReturn(Optional.of(companyWithId(404L, "LEGACY-404")));

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE,
        "numeric-miss-user",
        "404",
        Map.of("reason", "unknown-numeric-company"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata())
        .containsEntry("authCompanyToken", "404")
        .containsEntry("authCompanyResolution", "UNRESOLVED");
    verify(companyRepository, never()).findById(404L);
  }

  @Test
  void logAuthSuccess_unknownNumericCompanyCodeDoesNotFallbackToNumericCompanyId() {
    when(companyRepository.findByCodeIgnoreCase("505")).thenReturn(Optional.empty());
    when(companyRepository.findById(505L))
        .thenReturn(Optional.of(companyWithId(505L, "LEGACY-505")));

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "numeric-success-user", "505", Map.of("authChannel", "password"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata())
        .containsEntry("authChannel", "password")
        .containsEntry("authCompanyToken", "505")
        .containsEntry("authCompanyResolution", "UNRESOLVED");
    verify(companyRepository, never()).findById(505L);
  }

  @Test
  void logEvent_numericRuntimeContextCodeIdCollisionFailsClosed() {
    CompanyContextHolder.setCompanyCode("1001");
    when(companyRepository.findByCodeIgnoreCase("1001"))
        .thenReturn(Optional.of(companyWithId(77L, "1001")));
    when(companyRepository.findById(1001L))
        .thenReturn(Optional.of(companyWithId(1001L, "LEGACY-1001")));

    auditService.logEvent(
        AuditEvent.DATA_READ, AuditStatus.SUCCESS, Map.of("source", "runtime-collision"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getCompanyId()).isNull();
    verify(companyRepository).findById(1001L);
  }

  @Test
  void logAuthFailure_recycledRequestContext_stillPersistsOverrideAttribution() {
    HttpServletRequest recycledRequest = mock(HttpServletRequest.class);
    when(recycledRequest.getHeader(anyString()))
        .thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getMethod()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getRequestURI()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getRemoteAddr()).thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getSession(false))
        .thenThrow(new IllegalStateException("request recycled"));
    when(recycledRequest.getAttribute(anyString()))
        .thenThrow(new IllegalStateException("request recycled"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(recycledRequest));

    Company company = companyWithId(99L, "COMP-B");
    when(companyRepository.findByCodeIgnoreCase("COMP-B")).thenReturn(Optional.of(company));

    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "unexpected-security-user",
            "n/a",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    SecurityContextHolder.setContext(securityContext);

    assertThatCode(
            () ->
                auditService.logAuthFailure(
                    AuditEvent.LOGIN_FAILURE,
                    "blocked-user",
                    "COMP-B",
                    Map.of("reason", "bad-password")))
        .doesNotThrowAnyException();

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getEventType()).isEqualTo(AuditEvent.LOGIN_FAILURE);
    assertThat(saved.getStatus()).isEqualTo(AuditStatus.FAILURE);
    assertThat(saved.getUsername()).isEqualTo("blocked-user");
    assertThat(saved.getUserId()).isEqualTo("blocked-user");
    assertThat(saved.getCompanyId()).isEqualTo(99L);
    assertThat(saved.getIpAddress()).isNull();
    assertThat(saved.getRequestMethod()).isNull();
    assertThat(saved.getRequestPath()).isNull();
    assertThat(saved.getMetadata()).containsEntry("reason", "bad-password");
  }

  @Test
  void logAuthFailure_blankOverrideDoesNotFallbackToAmbientContext() {
    CompanyContextHolder.setCompanyCode("42");
    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "security-user", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    SecurityContextHolder.setContext(securityContext);

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE, "   ", "   ", Map.of("reason", "fallback"));

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogRepository).save(auditCaptor.capture());
    AuditLog saved = auditCaptor.getValue();
    assertThat(saved.getUsername()).isEqualTo("UNKNOWN_AUTH_ACTOR");
    assertThat(saved.getUserId()).isEqualTo("UNKNOWN_AUTH_ACTOR");
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata())
        .containsEntry("reason", "fallback")
        .containsEntry("authActorResolution", "UNRESOLVED")
        .containsEntry("authCompanyResolution", "UNRESOLVED");
  }

  @Test
  void parseNumericToken_returnsNullWhenAllDigitValueOverflowsLong() {
    Long parsed =
        ReflectionTestUtils.invokeMethod(auditService, "parseNumericToken", "92233720368547758070");

    assertThat(parsed).isNull();
  }

  @Test
  void parseNumericToken_parsesTrimmedDigitValue() {
    Long parsed = ReflectionTestUtils.invokeMethod(auditService, "parseNumericToken", " 42 ");

    assertThat(parsed).isEqualTo(42L);
  }

  private AuditService createService() {
    AuditService service = new AuditService();
    ReflectionTestUtils.setField(service, "auditLogRepository", auditLogRepository);
    ReflectionTestUtils.setField(service, "companyRepository", companyRepository);
    ReflectionTestUtils.setField(service, "self", service);
    when(auditLogRepository.save(any(AuditLog.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return service;
  }

  private Company companyWithId(Long id, String code) {
    Company company = new Company();
    company.setCode(code);
    ReflectionTestUtils.setField(company, "id", id);
    return company;
  }
}
