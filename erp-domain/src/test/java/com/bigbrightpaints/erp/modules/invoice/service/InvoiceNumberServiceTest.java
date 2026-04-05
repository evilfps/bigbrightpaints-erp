package com.bigbrightpaints.erp.modules.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequence;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequenceRepository;

@ExtendWith(MockitoExtension.class)
class InvoiceNumberServiceTest {

  @Mock private InvoiceSequenceRepository invoiceSequenceRepository;
  @Mock private PlatformTransactionManager txManager;
  @Mock private CompanyClock companyClock;

  private InvoiceNumberService invoiceNumberService;

  @BeforeEach
  void setup() {
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(companyClock.today(any())).thenReturn(LocalDate.of(2024, 1, 1));
    invoiceNumberService =
        new InvoiceNumberService(invoiceSequenceRepository, txManager, companyClock);
    lenient()
        .when(invoiceSequenceRepository.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void generatesInvoiceNumberForExistingSequence() {
    Company company = new Company();
    company.setCode("INV");
    company.setTimezone("UTC");

    InvoiceSequence sequence = new InvoiceSequence();
    when(invoiceSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenReturn(Optional.of(sequence));

    String invoiceNumber = invoiceNumberService.nextInvoiceNumber(company);

    assertThat(invoiceNumber).isEqualTo("INV-INV-2024-00001");
    verify(invoiceSequenceRepository).saveAndFlush(sequence);
  }

  @Test
  void retriesOnTransientSequenceContention() {
    Company company = new Company();
    company.setCode("INV");
    company.setTimezone("UTC");

    InvoiceSequence sequence = new InvoiceSequence();
    when(invoiceSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenThrow(new OptimisticLockingFailureException("stale row"))
        .thenReturn(Optional.of(sequence));

    String invoiceNumber = invoiceNumberService.nextInvoiceNumber(company);

    assertThat(invoiceNumber).isEqualTo("INV-INV-2024-00001");
    verify(invoiceSequenceRepository, times(3)).findByCompanyAndFiscalYear(eq(company), eq(2024));
    verify(invoiceSequenceRepository).saveAndFlush(sequence);
  }

  @Test
  void throwsLastErrorAfterMaxRetries() {
    Company company = new Company();
    company.setCode("INV");
    company.setTimezone("UTC");

    when(invoiceSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> invoiceNumberService.nextInvoiceNumber(company))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("duplicate key");

    verify(invoiceSequenceRepository, times(5)).findByCompanyAndFiscalYear(eq(company), eq(2024));
  }

  @Test
  void stopsRetryingWhenBackoffIsInterrupted() {
    Company company = new Company();
    company.setCode("INV");
    company.setTimezone("UTC");

    when(invoiceSequenceRepository.findByCompanyAndFiscalYear(eq(company), anyInt()))
        .thenThrow(new OptimisticLockingFailureException("stale row"));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> invoiceNumberService.nextInvoiceNumber(company))
          .isInstanceOf(OptimisticLockingFailureException.class)
          .hasMessageContaining("stale row");
    } finally {
      Thread.interrupted();
    }

    verify(invoiceSequenceRepository).findByCompanyAndFiscalYear(eq(company), eq(2024));
  }
}
