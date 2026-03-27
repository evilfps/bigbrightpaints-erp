package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

@ExtendWith(MockitoExtension.class)
class ScopedAccountBootstrapServiceTest {

  @Mock private UserAccountRepository userAccountRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private EmailService emailService;
  @Mock private AuthScopeService authScopeService;

  private ScopedAccountBootstrapService scopedAccountBootstrapService;

  @BeforeEach
  void setUp() {
    scopedAccountBootstrapService =
        new ScopedAccountBootstrapService(
            userAccountRepository, passwordEncoder, emailService, authScopeService);
  }

  @Test
  void isCredentialProvisioningReady_delegatesToEmailService() {
    when(emailService.isCredentialEmailDeliveryEnabled()).thenReturn(true);

    assertThat(scopedAccountBootstrapService.isCredentialProvisioningReady()).isTrue();
  }

  @Test
  void provisionTenantAccount_rejectsUnpersistedCompany() {
    Company company = new Company();

    assertThatThrownBy(
            () ->
                scopedAccountBootstrapService.provisionTenantAccount(
                    company, "user@example.com", "User", List.<Role>of()))
        .hasMessageContaining("Company must be persisted");

    verifyNoInteractions(authScopeService, emailService, userAccountRepository);
  }

  @Test
  void provisionTenantAccount_rejectsDuplicateScopedEmail() {
    Company company = persistedCompany("ACME", 7L);
    when(authScopeService.requireScopeCode("ACME")).thenReturn("ACME");
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", "ACME"))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                scopedAccountBootstrapService.provisionTenantAccount(
                    company, "user@example.com", "User", List.<Role>of()))
        .hasMessageContaining("User already exists for scope: ACME");

    verify(userAccountRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void provisionTenantAccount_rejectsBlankEmail() {
    Company company = persistedCompany("ACME", 7L);
    when(authScopeService.requireScopeCode("ACME")).thenReturn("ACME");

    assertThatThrownBy(
            () ->
                scopedAccountBootstrapService.provisionTenantAccount(
                    company, "   ", "User", List.<Role>of()))
        .hasMessageContaining("email is required");
  }

  @Test
  void provisionTenantAccount_rejectsBlankDisplayName() {
    Company company = persistedCompany("ACME", 7L);
    when(authScopeService.requireScopeCode("ACME")).thenReturn("ACME");
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", "ACME"))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                scopedAccountBootstrapService.provisionTenantAccount(
                    company, "user@example.com", "   ", List.<Role>of()))
        .hasMessageContaining("displayName is required");
  }

  private Company persistedCompany(String code, Long id) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code);
    company.setTimezone("UTC");
    org.springframework.test.util.ReflectionTestUtils.setField(company, "id", id);
    return company;
  }
}
