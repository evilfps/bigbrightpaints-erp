package com.bigbrightpaints.erp.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.bigbrightpaints.erp.core.domain.NumberSequence;
import com.bigbrightpaints.erp.core.domain.NumberSequenceRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@ExtendWith(MockitoExtension.class)
class NumberSequenceServiceTest {

  @Mock private NumberSequenceRepository repository;
  @Mock private CompanyRepository companyRepository;
  @Mock private PlatformTransactionManager txManager;

  private NumberSequenceService numberSequenceService;

  @BeforeEach
  void setup() {
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(companyRepository.existsById(anyLong())).thenReturn(true);
    numberSequenceService = new NumberSequenceService(repository, companyRepository, txManager);
    lenient()
        .when(repository.saveAndFlush(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void retriesOnTransientSequenceContention() {
    Company company = companyWithId(1L);
    NumberSequence sequence = new NumberSequence();
    when(repository.findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST")))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenReturn(Optional.of(sequence));

    long value = numberSequenceService.nextValue(company, "TEST");

    assertThat(value).isEqualTo(1L);
    verify(repository, times(2)).findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST"));
    verify(repository).saveAndFlush(sequence);
  }

  @Test
  void throwsLastErrorAfterMaxRetries() {
    Company company = companyWithId(1L);
    when(repository.findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST")))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> numberSequenceService.nextValue(company, "TEST"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("duplicate key");

    verify(repository, times(5)).findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST"));
  }

  @Test
  void stopsRetryingWhenBackoffIsInterrupted() {
    Company company = companyWithId(1L);
    when(repository.findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST")))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> numberSequenceService.nextValue(company, "TEST"))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("duplicate key");
    } finally {
      Thread.interrupted();
    }

    verify(repository).findWithLockByCompanyAndSequenceKey(eq(company), eq("TEST"));
  }

  private Company companyWithId(Long id) {
    Company company = new Company();
    company.setCode("SEQ");
    company.setTimezone("UTC");
    setId(company, id);
    return company;
  }

  private void setId(Company company, Long id) {
    try {
      Field idField = Company.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(company, id);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
