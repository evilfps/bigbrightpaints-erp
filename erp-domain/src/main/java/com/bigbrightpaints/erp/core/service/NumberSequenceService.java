package com.bigbrightpaints.erp.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bigbrightpaints.erp.core.domain.NumberSequence;
import com.bigbrightpaints.erp.core.domain.NumberSequenceRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class NumberSequenceService {

  private static final Logger log = LoggerFactory.getLogger(NumberSequenceService.class);
  private static final int MAX_RETRIES = 5;

  private final NumberSequenceRepository repository;
  private final CompanyRepository companyRepository;
  private final TransactionTemplate newTxTemplate;

  public NumberSequenceService(
      NumberSequenceRepository repository,
      CompanyRepository companyRepository,
      PlatformTransactionManager txManager) {
    this.repository = repository;
    this.companyRepository = companyRepository;
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.newTxTemplate = template;
  }

  public long nextValue(Company company, String sequenceKey) {
    DataIntegrityViolationException lastError = null;
    boolean preferRequiresNew = canUseRequiresNew(company);
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        Long value =
            preferRequiresNew
                ? newTxTemplate.execute(status -> acquireNextValue(company, sequenceKey))
                : acquireNextValue(company, sequenceKey);
        if (value != null) {
          return value;
        }
      } catch (JpaObjectRetrievalFailureException | EntityNotFoundException notFound) {
        if (preferRequiresNew) {
          log.debug(
              "Company not visible in suspended transaction for sequence {}; retrying in current"
                  + " transaction",
              sequenceKey);
          preferRequiresNew = false;
          continue;
        }
        throw notFound;
      } catch (DataIntegrityViolationException ex) {
        lastError = ex;
        log.warn(
            "Sequence contention for key {} (company {}), retrying attempt {}/{}",
            sequenceKey,
            company != null ? company.getId() : null,
            attempt,
            MAX_RETRIES);
      }
    }
    if (lastError != null) {
      throw lastError;
    }
    throw new IllegalStateException("Failed to generate next value for " + sequenceKey);
  }

  private NumberSequence initializeSequence(Company company, String key) {
    NumberSequence sequence = new NumberSequence();
    sequence.setCompany(company);
    sequence.setSequenceKey(key);
    sequence.setNextValue(1L);
    return repository.save(sequence);
  }

  private Long acquireNextValue(Company company, String sequenceKey) {
    NumberSequence sequence =
        repository
            .findWithLockByCompanyAndSequenceKey(company, sequenceKey)
            .orElseGet(() -> initializeSequence(company, sequenceKey));
    long next = sequence.consumeAndIncrement();
    repository.saveAndFlush(sequence);
    return next;
  }

  private boolean canUseRequiresNew(Company company) {
    if (company == null || company.getId() == null) {
      return false;
    }
    return companyRepository.existsById(company.getId());
  }
}
