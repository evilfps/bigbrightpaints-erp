package com.bigbrightpaints.erp.modules.invoice.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequence;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequenceRepository;

@Service
public class InvoiceNumberService {

  private static final long RETRY_BACKOFF_MILLIS = 10L;
  private static final int MAX_RETRIES = 5;

  private final InvoiceSequenceRepository sequenceRepository;
  private final TransactionTemplate newTxTemplate;
  private final CompanyClock companyClock;

  public InvoiceNumberService(
      InvoiceSequenceRepository sequenceRepository,
      PlatformTransactionManager txManager,
      CompanyClock companyClock) {
    this.sequenceRepository = sequenceRepository;
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.newTxTemplate = template;
    this.companyClock = companyClock;
  }

  public String nextInvoiceNumber(Company company) {
    int fiscalYear = resolveFiscalYear(company);
    RuntimeException lastError = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        String invoiceNumber =
            newTxTemplate.execute(
                status -> {
                  InvoiceSequence sequence =
                      sequenceRepository
                          .findByCompanyAndFiscalYear(company, fiscalYear)
                          .orElseGet(() -> initializeSequence(company, fiscalYear));
                  long next = sequence.consumeAndIncrement();
                  sequenceRepository.saveAndFlush(sequence);
                  return "%s-INV-%d-%05d".formatted(company.getCode(), fiscalYear, next);
                });
        if (invoiceNumber != null) {
          return invoiceNumber;
        }
      } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
        lastError = ex;
        if (attempt == MAX_RETRIES || !pauseBeforeRetry(attempt)) {
          break;
        }
      }
    }
    if (lastError != null) {
      throw lastError;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
        "Failed to generate invoice number for company " + company.getCode());
  }

  private InvoiceSequence initializeSequence(Company company, int fiscalYear) {
    InvoiceSequence sequence = new InvoiceSequence();
    sequence.setCompany(company);
    sequence.setFiscalYear(fiscalYear);
    sequence.setNextNumber(1L);
    return sequenceRepository.save(sequence);
  }

  private int resolveFiscalYear(Company company) {
    return companyClock.today(company).getYear();
  }

  private boolean pauseBeforeRetry(int attempt) {
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(RETRY_BACKOFF_MILLIS * attempt));
    return !Thread.currentThread().isInterrupted();
  }
}
