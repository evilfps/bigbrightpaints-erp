package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequence;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequenceRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class OrderNumberService {

    private static final int DEFAULT_PADDING = 5;

    private final OrderSequenceRepository orderSequenceRepository;

    public OrderNumberService(OrderSequenceRepository orderSequenceRepository) {
        this.orderSequenceRepository = orderSequenceRepository;
    }

    @Transactional
    public String nextOrderNumber(Company company) {
        int fiscalYear = resolveFiscalYear(company);
        OrderSequence sequence = orderSequenceRepository.findByCompanyAndFiscalYear(company, fiscalYear)
                .orElseGet(() -> initializeSequence(company, fiscalYear));
        long nextNumber = sequence.consumeAndIncrement();
        orderSequenceRepository.save(sequence);
        return formatOrderNumber(company.getCode(), fiscalYear, nextNumber);
    }

    private OrderSequence initializeSequence(Company company, int fiscalYear) {
        OrderSequence sequence = new OrderSequence();
        sequence.setCompany(company);
        sequence.setFiscalYear(fiscalYear);
        sequence.setNextNumber(1L);
        return orderSequenceRepository.save(sequence);
    }

    private int resolveFiscalYear(Company company) {
        ZoneId zone = ZoneId.of(company.getTimezone());
        return LocalDate.now(zone).getYear();
    }

    private String formatOrderNumber(String companyCode, int fiscalYear, long sequenceNumber) {
        return "%s-%d-%0" + DEFAULT_PADDING + "d".formatted(companyCode, fiscalYear, sequenceNumber);
    }
}
