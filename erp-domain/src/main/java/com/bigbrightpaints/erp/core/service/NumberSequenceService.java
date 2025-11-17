package com.bigbrightpaints.erp.core.service;

import com.bigbrightpaints.erp.core.domain.NumberSequence;
import com.bigbrightpaints.erp.core.domain.NumberSequenceRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class NumberSequenceService {

    private final NumberSequenceRepository repository;

    public NumberSequenceService(NumberSequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public long nextValue(Company company, String sequenceKey) {
        NumberSequence sequence = repository.findByCompanyAndSequenceKey(company, sequenceKey)
                .orElseGet(() -> initializeSequence(company, sequenceKey));
        long value = sequence.consumeAndIncrement();
        repository.save(sequence);
        return value;
    }

    private NumberSequence initializeSequence(Company company, String key) {
        NumberSequence sequence = new NumberSequence();
        sequence.setCompany(company);
        sequence.setSequenceKey(key);
        sequence.setNextValue(1L);
        return repository.save(sequence);
    }
}
