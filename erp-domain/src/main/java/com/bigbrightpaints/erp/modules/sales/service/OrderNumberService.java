package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequence;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequenceRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OrderNumberService {

    private static final int DEFAULT_PADDING = 5;

    private final OrderSequenceRepository orderSequenceRepository;
    private final AuditService auditService;

    public OrderNumberService(OrderSequenceRepository orderSequenceRepository, AuditService auditService) {
        this.orderSequenceRepository = orderSequenceRepository;
        this.auditService = auditService;
    }

    @Transactional
    public String nextOrderNumber(Company company) {
        int fiscalYear = resolveFiscalYear(company);
        OrderSequence sequence = orderSequenceRepository.findByCompanyAndFiscalYear(company, fiscalYear)
                .orElseGet(() -> initializeSequence(company, fiscalYear));
        long nextNumber = sequence.consumeAndIncrement();
        orderSequenceRepository.save(sequence);
        String orderNumber = formatOrderNumber(company.getCode(), fiscalYear, nextNumber);
        auditOrderNumber(company, fiscalYear, nextNumber, orderNumber);
        return orderNumber;
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
        String pattern = "%s-%d-%0" + DEFAULT_PADDING + "d";
        return pattern.formatted(companyCode, fiscalYear, sequenceNumber);
    }

    private void auditOrderNumber(Company company, int fiscalYear, long value, String orderNumber) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("sequenceKey", "ORDER-" + company.getCode() + "-" + fiscalYear);
        metadata.put("orderNumber", orderNumber);
        if (company.getId() != null) {
            metadata.put("companyId", company.getId().toString());
        }
        auditService.logSuccess(AuditEvent.ORDER_NUMBER_GENERATED, metadata);
    }
}
