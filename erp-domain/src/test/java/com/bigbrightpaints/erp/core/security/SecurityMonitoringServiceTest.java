package com.bigbrightpaints.erp.core.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;

@ExtendWith(MockitoExtension.class)
class SecurityMonitoringServiceTest {

  @Mock private AuditService auditService;
  @Mock private AuditLogRepository auditLogRepository;
  @Mock private TokenBlacklistService tokenBlacklistService;
  @Mock private EmailService emailService;

  private SecurityMonitoringService securityMonitoringService;

  @BeforeEach
  void setUp() {
    securityMonitoringService = new SecurityMonitoringService();
    ReflectionTestUtils.setField(securityMonitoringService, "auditService", auditService);
    ReflectionTestUtils.setField(
        securityMonitoringService, "auditLogRepository", auditLogRepository);
    ReflectionTestUtils.setField(
        securityMonitoringService, "tokenBlacklistService", tokenBlacklistService);
    ReflectionTestUtils.setField(securityMonitoringService, "emailService", emailService);
    ReflectionTestUtils.setField(securityMonitoringService, "maxFailedLogins", 1);
    ReflectionTestUtils.setField(securityMonitoringService, "suspiciousActivityThreshold", 100);
  }

  @Test
  void recordFailedLogin_sendsConfiguredSecurityAlertEmail() {
    ReflectionTestUtils.setField(
        securityMonitoringService, "securityNotificationEmail", "security@example.com");

    securityMonitoringService.recordFailedLogin("user@example.com", "127.0.0.1");

    verify(tokenBlacklistService).revokeAllUserTokens("user@example.com");
    verify(emailService)
        .sendSimpleEmail(
            eq("security@example.com"),
            eq("Security alert: Brute force attack"),
            contains("Subject: user@example.com"));
  }

  @Test
  void recordFailedLogin_skipsSecurityAlertEmailWhenRecipientMissing() {
    ReflectionTestUtils.setField(securityMonitoringService, "securityNotificationEmail", " ");

    securityMonitoringService.recordFailedLogin("user@example.com", "127.0.0.1");

    verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
  }
}
