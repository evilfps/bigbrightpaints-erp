package com.bigbrightpaints.erp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@SpringJUnitConfig(classes = AuditServiceAsyncIT.TestConfig.class)
@ActiveProfiles("audit-async-it")
class AuditServiceAsyncIT {

  @TestConfiguration
  @Profile("audit-async-it")
  @EnableAsync(proxyTargetClass = true)
  static class TestConfig implements AsyncConfigurer {
    @Bean
    AuditService auditService() {
      return new AuditService();
    }

    @Bean
    AuditLogRepository auditLogRepository() {
      return mock(AuditLogRepository.class);
    }

    @Bean
    CompanyRepository companyRepository() {
      return mock(CompanyRepository.class);
    }

    @Bean(name = "auditAsyncExecutor")
    Executor auditAsyncExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(1);
      executor.setMaxPoolSize(1);
      executor.setQueueCapacity(16);
      executor.setThreadNamePrefix("audit-async-it-");
      executor.initialize();
      return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
      return auditAsyncExecutor();
    }
  }

  @org.springframework.beans.factory.annotation.Autowired private AuditService auditService;

  @org.springframework.beans.factory.annotation.Autowired
  private AuditLogRepository auditLogRepository;

  @org.springframework.beans.factory.annotation.Autowired
  private CompanyRepository companyRepository;

  @BeforeEach
  void beforeEach() {
    reset(auditLogRepository, companyRepository);
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
  }

  @Test
  void logAuthSuccess_asyncProxyPreservesExplicitAttributionAfterContextMutation()
      throws Exception {
    Company company = new Company();
    company.setCode("COMP-ASYNC");
    ReflectionTestUtils.setField(company, "id", 77L);
    when(companyRepository.findByCodeIgnoreCase("COMP-ASYNC")).thenReturn(Optional.of(company));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<AuditLog> savedRef = new AtomicReference<>();
    when(auditLogRepository.save(any(AuditLog.class)))
        .thenAnswer(
            invocation -> {
              AuditLog log = invocation.getArgument(0);
              savedRef.set(log);
              latch.countDown();
              return log;
            });

    SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
    callerContext.setAuthentication(
        new UsernamePasswordAuthenticationToken("ambient-before", "n/a"));
    SecurityContextHolder.setContext(callerContext);
    CompanyContextHolder.setCompanyCode("AMBIENT-COMPANY");

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "explicit-user", "COMP-ASYNC", Map.of("origin", "async-it"));

    callerContext.setAuthentication(
        new UsernamePasswordAuthenticationToken("ambient-mutated", "n/a"));
    CompanyContextHolder.setCompanyCode("MUTATED-COMPANY");

    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    AuditLog saved = savedRef.get();
    assertThat(saved).isNotNull();
    assertThat(saved.getEventType()).isEqualTo(AuditEvent.LOGIN_SUCCESS);
    assertThat(saved.getStatus()).isEqualTo(AuditStatus.SUCCESS);
    assertThat(saved.getUsername()).isEqualTo("explicit-user");
    assertThat(saved.getUserId()).isEqualTo("explicit-user");
    assertThat(saved.getCompanyId()).isEqualTo(77L);
    assertThat(saved.getMetadata()).containsEntry("origin", "async-it");
  }

  @Test
  void logAuthSuccess_asyncProxyCapturesAuthenticatedPublicIdWhenOverrideMatches() throws Exception {
    Company company = new Company();
    company.setCode("COMP-ASYNC");
    ReflectionTestUtils.setField(company, "id", 77L);
    when(companyRepository.findByCodeIgnoreCase("COMP-ASYNC")).thenReturn(Optional.of(company));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<AuditLog> savedRef = new AtomicReference<>();
    when(auditLogRepository.save(any(AuditLog.class)))
        .thenAnswer(
            invocation -> {
              AuditLog log = invocation.getArgument(0);
              savedRef.set(log);
              latch.countDown();
              return log;
            });

    UserAccount authenticatedUser =
        new UserAccount("actor@bbp.com", "COMP-ASYNC", "hash", "Authenticated Actor");
    SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
    callerContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new UserPrincipal(authenticatedUser), "n/a", java.util.List.of()));
    SecurityContextHolder.setContext(callerContext);

    auditService.logAuthSuccess(
        AuditEvent.LOGIN_SUCCESS, "actor@bbp.com", "COMP-ASYNC", Map.of("origin", "async-it"));

    callerContext.setAuthentication(new UsernamePasswordAuthenticationToken("mutated", "n/a"));

    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    AuditLog saved = savedRef.get();
    assertThat(saved).isNotNull();
    assertThat(saved.getUsername()).isEqualTo("actor@bbp.com");
    assertThat(saved.getUserId()).isEqualTo(authenticatedUser.getPublicId().toString());
    assertThat(saved.getMetadata()).containsEntry("actorPublicId", authenticatedUser.getPublicId().toString());
  }

  @Test
  void logAuthFailure_unknownCompanyCodeKeepsActorWithoutCompanyAttribution() throws Exception {
    when(companyRepository.findByCodeIgnoreCase("UNKNOWN-COMPANY")).thenReturn(Optional.empty());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<AuditLog> savedRef = new AtomicReference<>();
    when(auditLogRepository.save(any(AuditLog.class)))
        .thenAnswer(
            invocation -> {
              AuditLog log = invocation.getArgument(0);
              savedRef.set(log);
              latch.countDown();
              return log;
            });

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE,
        "failed-user",
        "UNKNOWN-COMPANY",
        Map.of("reason", "bad-password"));

    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    AuditLog saved = savedRef.get();
    assertThat(saved).isNotNull();
    assertThat(saved.getEventType()).isEqualTo(AuditEvent.LOGIN_FAILURE);
    assertThat(saved.getStatus()).isEqualTo(AuditStatus.FAILURE);
    assertThat(saved.getUsername()).isEqualTo("failed-user");
    assertThat(saved.getUserId()).isEqualTo("failed-user");
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata()).containsEntry("reason", "bad-password");
  }

  @Test
  void logAuthFailure_blankOverridesDoNotUseAmbientContextInAsyncPath() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<AuditLog> savedRef = new AtomicReference<>();
    when(auditLogRepository.save(any(AuditLog.class)))
        .thenAnswer(
            invocation -> {
              AuditLog log = invocation.getArgument(0);
              savedRef.set(log);
              latch.countDown();
              return log;
            });

    SecurityContext ambientContext = SecurityContextHolder.createEmptyContext();
    ambientContext.setAuthentication(
        new UsernamePasswordAuthenticationToken("ambient-user", "n/a"));
    SecurityContextHolder.setContext(ambientContext);
    CompanyContextHolder.setCompanyCode("AMBIENT-COMPANY");

    auditService.logAuthFailure(
        AuditEvent.LOGIN_FAILURE, " ", " ", Map.of("reason", "blank-overrides"));

    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    AuditLog saved = savedRef.get();
    assertThat(saved).isNotNull();
    assertThat(saved.getUsername()).isEqualTo("UNKNOWN_AUTH_ACTOR");
    assertThat(saved.getUserId()).isEqualTo("UNKNOWN_AUTH_ACTOR");
    assertThat(saved.getCompanyId()).isNull();
    assertThat(saved.getMetadata())
        .containsEntry("reason", "blank-overrides")
        .containsEntry("authActorResolution", "UNRESOLVED")
        .containsEntry("authCompanyResolution", "UNRESOLVED");
  }
}
