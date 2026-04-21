package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRetry;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRetryRepository;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.audittrail.MlInteractionEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class AccountingComplianceAuditServiceSyncAuditIT {

  @Mock private AuditActionEventRepository auditActionEventRepository;
  @Mock private AuditActionEventRetryRepository auditActionEventRetryRepository;
  @Mock private MlInteractionEventRepository mlInteractionEventRepository;
  @Mock private CompanyContextService companyContextService;

  @Test
  void recordPeriodTransition_syncAuditFailureDoesNotAbortCallerTransaction() {
    when(auditActionEventRepository.save(any(AuditActionEvent.class)))
        .thenThrow(new RuntimeException("db unavailable"));
    when(auditActionEventRetryRepository.save(any(AuditActionEventRetry.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    EnterpriseAuditTrailService target =
        new EnterpriseAuditTrailService(
            auditActionEventRepository,
            auditActionEventRetryRepository,
            mlInteractionEventRepository,
            companyContextService,
            new ObjectMapper(),
            "test-audit-key");

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:accounting-compliance-audit-sync-it;DB_CLOSE_DELAY=-1");
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

    ProxyFactory proxyFactory = new ProxyFactory(target);
    proxyFactory.setProxyTargetClass(true);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    EnterpriseAuditTrailService proxiedService =
        (EnterpriseAuditTrailService) proxyFactory.getProxy();

    ReflectionTestUtils.setField(target, "businessEventAsyncEnabled", false);
    ReflectionTestUtils.setField(target, "self", proxiedService);

    AccountingComplianceAuditService accountingComplianceAuditService =
        new AccountingComplianceAuditService(proxiedService, new ObjectMapper());

    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 77L);
    company.setCode("BBP");
    company.setBaseCurrency("INR");

    AccountingPeriod period = new AccountingPeriod();
    ReflectionTestUtils.setField(period, "id", 302L);
    period.setCompany(company);
    period.setYear(2026);
    period.setMonth(3);
    period.setStartDate(LocalDate.of(2026, 3, 1));
    period.setEndDate(LocalDate.of(2026, 3, 31));

    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThatCode(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        accountingComplianceAuditService.recordPeriodTransition(
                            company,
                            period,
                            "PERIOD_REOPENED",
                            "CLOSED",
                            "OPEN",
                            "Reopen for correction")))
        .doesNotThrowAnyException();

    verify(auditActionEventRepository).save(any(AuditActionEvent.class));
    verify(auditActionEventRetryRepository).save(any(AuditActionEventRetry.class));
  }
}
