package com.bigbrightpaints.erp.core.audit;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(recycledRequest.getHeader(anyString())).thenThrow(new IllegalStateException("request recycled"));
        when(recycledRequest.getMethod()).thenThrow(new IllegalStateException("request recycled"));
        when(recycledRequest.getRequestURI()).thenThrow(new IllegalStateException("request recycled"));
        when(recycledRequest.getRemoteAddr()).thenThrow(new IllegalStateException("request recycled"));
        when(recycledRequest.getSession(false)).thenThrow(new IllegalStateException("request recycled"));
        when(recycledRequest.getAttribute(anyString())).thenThrow(new IllegalStateException("request recycled"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(recycledRequest));

        assertThatCode(() -> auditService.logEvent(AuditEvent.SECURITY_ALERT, AuditStatus.WARNING, Map.of("k", "v")))
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
        CompanyContextHolder.setCompanyCode("42");

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "auditor",
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
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

    private AuditService createService() {
        AuditService service = new AuditService();
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogRepository);
        ReflectionTestUtils.setField(service, "companyRepository", companyRepository);
        ReflectionTestUtils.setField(service, "self", service);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }
}
