package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequence;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceSequenceRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class InvoiceNumberService {

    private final InvoiceSequenceRepository sequenceRepository;

    public InvoiceNumberService(InvoiceSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    @Transactional
    public String nextInvoiceNumber(Company company) {
        int fiscalYear = resolveFiscalYear(company);
        InvoiceSequence sequence = sequenceRepository.findByCompanyAndFiscalYear(company, fiscalYear)
                .orElseGet(() -> initializeSequence(company, fiscalYear));
        long next = sequence.consumeAndIncrement();
        sequenceRepository.save(sequence);
        return "%s-INV-%d-%05d".formatted(company.getCode(), fiscalYear, next);
    }

    private InvoiceSequence initializeSequence(Company company, int fiscalYear) {
        InvoiceSequence sequence = new InvoiceSequence();
        sequence.setCompany(company);
        sequence.setFiscalYear(fiscalYear);
        sequence.setNextNumber(1L);
        return sequenceRepository.save(sequence);
    }

    private int resolveFiscalYear(Company company) {
        ZoneId zone = ZoneId.of(company.getTimezone());
        return LocalDate.now(zone).getYear();
    }
}
