package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class ScopedAccountBootstrapServiceIT extends AbstractIntegrationTest {

  @Autowired private ScopedAccountBootstrapService scopedAccountBootstrapService;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @SpyBean private EmailService emailService;

  @Test
  void provisionTenantAccount_defersCredentialEmail_untilOuterTransactionCommits() {
    Company company = dataSeeder.ensureCompany("BOOT", "Bootstrap Ltd");
    Role adminRole =
        roleRepository
            .findByName("ROLE_ADMIN")
            .orElseGet(
                () ->
                    dataSeeder
                        .ensureUser(
                            "bootstrap-role-seed@bbp.com",
                            "Passw0rd!",
                            "Bootstrap Role Seed",
                            "BOOTROLE",
                            List.of("ROLE_ADMIN"))
                        .getRoles()
                        .stream()
                        .filter(role -> "ROLE_ADMIN".equalsIgnoreCase(role.getName()))
                        .findFirst()
                        .orElseThrow());
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      scopedAccountBootstrapService.provisionTenantAccount(
                          company,
                          "rolled-back-bootstrap@bbp.com",
                          "Rolled Back Bootstrap",
                          List.of(adminRole));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("force rollback");

    assertThat(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                "rolled-back-bootstrap@bbp.com", "BOOT"))
        .isEmpty();
    verify(emailService, never())
        .sendUserCredentialsEmailRequired(
            eq("rolled-back-bootstrap@bbp.com"),
            eq("Rolled Back Bootstrap"),
            anyString(),
            eq("BOOT"));
  }

  @Test
  void provisionTenantAccount_sendsCredentialEmail_afterSuccessfulCommit() {
    Company company = dataSeeder.ensureCompany("BOOT2", "Bootstrap 2 Ltd");
    Role adminRole =
        roleRepository
            .findByName("ROLE_ADMIN")
            .orElseGet(
                () ->
                    dataSeeder
                        .ensureUser(
                            "bootstrap-role-seed-2@bbp.com",
                            "Passw0rd!",
                            "Bootstrap Role Seed 2",
                            "BOOTROLE2",
                            List.of("ROLE_ADMIN"))
                        .getRoles()
                        .stream()
                        .filter(role -> "ROLE_ADMIN".equalsIgnoreCase(role.getName()))
                        .findFirst()
                        .orElseThrow());
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    transactionTemplate.executeWithoutResult(
        status ->
            scopedAccountBootstrapService.provisionTenantAccount(
                company, "committed-bootstrap@bbp.com", "Committed Bootstrap", List.of(adminRole)));

    assertThat(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                "committed-bootstrap@bbp.com", "BOOT2"))
        .isPresent();
    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("committed-bootstrap@bbp.com"), eq("Committed Bootstrap"), anyString(), eq("BOOT2"));
  }

  @Test
  void provisionTenantAccount_keepsCommittedAccount_whenAfterCommitEmailFails() {
    Company company = dataSeeder.ensureCompany("BOOT3", "Bootstrap 3 Ltd");
    Role adminRole =
        roleRepository
            .findByName("ROLE_ADMIN")
            .orElseGet(
                () ->
                    dataSeeder
                        .ensureUser(
                            "bootstrap-role-seed-3@bbp.com",
                            "Passw0rd!",
                            "Bootstrap Role Seed 3",
                            "BOOTROLE3",
                            List.of("ROLE_ADMIN"))
                        .getRoles()
                        .stream()
                        .filter(role -> "ROLE_ADMIN".equalsIgnoreCase(role.getName()))
                        .findFirst()
                        .orElseThrow());
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    doThrow(new MailSendException("smtp down"))
        .when(emailService)
        .sendUserCredentialsEmailRequired(
            eq("mail-failure-bootstrap@bbp.com"),
            eq("Mail Failure Bootstrap"),
            anyString(),
            eq("BOOT3"));

    transactionTemplate.executeWithoutResult(
        status ->
            scopedAccountBootstrapService.provisionTenantAccount(
                company,
                "mail-failure-bootstrap@bbp.com",
                "Mail Failure Bootstrap",
                List.of(adminRole)));

    assertThat(
            userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                "mail-failure-bootstrap@bbp.com", "BOOT3"))
        .isPresent();
    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("mail-failure-bootstrap@bbp.com"),
            eq("Mail Failure Bootstrap"),
            anyString(),
            eq("BOOT3"));
  }
}
