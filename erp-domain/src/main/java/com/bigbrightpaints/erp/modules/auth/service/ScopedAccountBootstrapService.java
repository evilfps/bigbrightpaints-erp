package com.bigbrightpaints.erp.modules.auth.service;

import java.util.Collection;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.util.PasswordUtils;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

@Service
public class ScopedAccountBootstrapService {

  private static final Logger log = LoggerFactory.getLogger(ScopedAccountBootstrapService.class);

  private final UserAccountRepository userAccountRepository;
  private final PasswordEncoder passwordEncoder;
  private final EmailService emailService;
  private final AuthScopeService authScopeService;

  public ScopedAccountBootstrapService(
      UserAccountRepository userAccountRepository,
      PasswordEncoder passwordEncoder,
      EmailService emailService,
      AuthScopeService authScopeService) {
    this.userAccountRepository = userAccountRepository;
    this.passwordEncoder = passwordEncoder;
    this.emailService = emailService;
    this.authScopeService = authScopeService;
  }

  public boolean isCredentialProvisioningReady() {
    return emailService.isCredentialEmailDeliveryEnabled();
  }

  @Transactional
  public UserAccount provisionTenantAccount(
      Company company, String email, String displayName, Collection<Role> roles) {
    if (company == null || company.getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company must be persisted before account provisioning");
    }
    String scopeCode = authScopeService.requireScopeCode(company.getCode());
    String temporaryPassword = generateTemporaryPassword();
    emailService.assertCredentialEmailDeliveryReady(email);
    UserAccount account =
        createScopedAccount(scopeCode, email, displayName, roles, temporaryPassword);
    account.setCompany(company);
    UserAccount saved = userAccountRepository.save(account);
    scheduleCredentialEmailDelivery(saved, temporaryPassword, scopeCode);
    return saved;
  }

  private void scheduleCredentialEmailDelivery(
      UserAccount saved, String temporaryPassword, String scopeCode) {
    String email = saved.getEmail();
    String displayName = saved.getDisplayName();
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              deliverCredentialEmailAfterCommit(email, displayName, temporaryPassword, scopeCode);
            }
          });
      return;
    }
    emailService.sendUserCredentialsEmailRequired(email, displayName, temporaryPassword, scopeCode);
  }

  private void deliverCredentialEmailAfterCommit(
      String email, String displayName, String temporaryPassword, String scopeCode) {
    try {
      emailService.sendUserCredentialsEmailRequired(
          email, displayName, temporaryPassword, scopeCode);
    } catch (RuntimeException ex) {
      log.error(
          "Scoped account provisioned but credential email delivery failed after commit in scope"
              + " {}",
          scopeCode,
          ex);
    }
  }

  private UserAccount createScopedAccount(
      String scopeCode,
      String email,
      String displayName,
      Collection<Role> roles,
      String temporaryPassword) {
    String normalizedEmail = normalizeEmail(email);
    String normalizedScopeCode = authScopeService.requireScopeCode(scopeCode);
    if (userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
        normalizedEmail, normalizedScopeCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "User already exists for scope: " + normalizedScopeCode);
    }
    String resolvedDisplayName = normalizeDisplayName(displayName);
    UserAccount account =
        new UserAccount(
            normalizedEmail,
            normalizedScopeCode,
            passwordEncoder.encode(temporaryPassword),
            resolvedDisplayName);
    account.setMustChangePassword(true);
    if (roles != null) {
      roles.stream().filter(java.util.Objects::nonNull).forEach(account::addRole);
    }
    return account;
  }

  private String normalizeEmail(String email) {
    if (!StringUtils.hasText(email)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "email is required");
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeDisplayName(String displayName) {
    if (!StringUtils.hasText(displayName)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "displayName is required");
    }
    return displayName.trim();
  }

  private String generateTemporaryPassword() {
    return PasswordUtils.generateTemporaryPassword(14);
  }
}
