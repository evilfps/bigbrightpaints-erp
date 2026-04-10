package com.bigbrightpaints.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequence;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequenceRepository;

@ExtendWith(MockitoExtension.class)
class OrderNumberServiceTest {

  @Mock private OrderSequenceRepository orderSequenceRepository;
  @Mock private AuditService auditService;
  @Mock private PlatformTransactionManager txManager;
  @Mock private CompanyClock companyClock;

  private OrderNumberService orderNumberService;

  @BeforeEach
  void setup() {
    lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    lenient().when(companyClock.today(any())).thenReturn(LocalDate.of(2024, 1, 1));
    orderNumberService =
        new OrderNumberService(orderSequenceRepository, auditService, txManager, companyClock);
    lenient()
        .when(orderSequenceRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void generatesOrderNumbersPerCompany() {
    Company first = new Company();
    first.setCode("C1");
    first.setTimezone("UTC");
    Company second = new Company();
    second.setCode("C2");
    second.setTimezone("UTC");

    OrderSequence seq1 = new OrderSequence();
    OrderSequence seq2 = new OrderSequence();
    when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(first), anyInt()))
        .thenReturn(Optional.of(seq1));
    when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(second), anyInt()))
        .thenReturn(Optional.of(seq2));

    String orderNumber1 = orderNumberService.nextOrderNumber(first);
    String orderNumber2 = orderNumberService.nextOrderNumber(second);

    assertThat(orderNumber1).startsWith("C1-");
    assertThat(orderNumber2).startsWith("C2-");
    assertThat(orderNumber1).isNotEqualTo(orderNumber2);
    verify(orderSequenceRepository).saveAndFlush(seq1);
    verify(orderSequenceRepository).saveAndFlush(seq2);
    verify(auditService, times(2)).logSuccess(eq(AuditEvent.ORDER_NUMBER_GENERATED), anyMap());
  }

  @Test
  void retriesOnTransientSequenceContention() {
    Company company = new Company();
    company.setCode("C1");
    company.setTimezone("UTC");

    OrderSequence sequence = new OrderSequence();
    when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenThrow(new OptimisticLockingFailureException("stale row"))
        .thenReturn(Optional.of(sequence));

    String orderNumber = orderNumberService.nextOrderNumber(company);

    assertThat(orderNumber).isEqualTo("C1-2024-00001");
    verify(orderSequenceRepository, times(3)).findByCompanyAndFiscalYear(eq(company), eq(2024));
    verify(orderSequenceRepository).saveAndFlush(sequence);
    verify(auditService).logSuccess(eq(AuditEvent.ORDER_NUMBER_GENERATED), anyMap());
  }

  @Test
  void throwsLastErrorAfterMaxRetries() {
    Company company = new Company();
    company.setCode("C1");
    company.setTimezone("UTC");

    when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> orderNumberService.nextOrderNumber(company))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("duplicate key");

    verify(orderSequenceRepository, times(5)).findByCompanyAndFiscalYear(eq(company), eq(2024));
  }

  @Test
  void stopsRetryingWhenBackoffIsInterrupted() {
    Company company = new Company();
    company.setCode("C1");
    company.setTimezone("UTC");

    when(orderSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new OptimisticLockingFailureException("stale row"));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> orderNumberService.nextOrderNumber(company))
          .isInstanceOf(OptimisticLockingFailureException.class)
          .hasMessageContaining("stale row");
    } finally {
      Thread.interrupted();
    }

    verify(orderSequenceRepository).findByCompanyAndFiscalYear(eq(company), eq(2024));
  }

  @Test
  void parseSequence_returnsZeroWhenSuffixOverflowsLong() {
    long parsed =
        ReflectionTestUtils.invokeMethod(
            orderNumberService, "parseSequence", "C1-2024-92233720368547758070");

    assertThat(parsed).isZero();
  }
}
