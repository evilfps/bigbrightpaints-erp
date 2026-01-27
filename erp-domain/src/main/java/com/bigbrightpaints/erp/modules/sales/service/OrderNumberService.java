package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequence;
import com.bigbrightpaints.erp.modules.sales.domain.OrderSequenceRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderNumberService {

    private static final int DEFAULT_PADDING = 5;
    private static final int MAX_RETRIES = 5;

    private final OrderSequenceRepository orderSequenceRepository;
    private final AuditService auditService;
    private final TransactionTemplate newTxTemplate;

    public OrderNumberService(OrderSequenceRepository orderSequenceRepository,
                              AuditService auditService,
                              PlatformTransactionManager txManager) {
        this.orderSequenceRepository = orderSequenceRepository;
        this.auditService = auditService;
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newTxTemplate = template;
    }

    public String nextOrderNumber(Company company) {
        int fiscalYear = resolveFiscalYear(company);
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String orderNumber = newTxTemplate.execute(status -> {
                    OrderSequence sequence = orderSequenceRepository.findByCompanyAndFiscalYear(company, fiscalYear)
                            .orElseGet(() -> initializeSequence(company, fiscalYear));
                    long nextNumber = sequence.consumeAndIncrement();
                    orderSequenceRepository.saveAndFlush(sequence);
                    return formatOrderNumber(company.getCode(), fiscalYear, nextNumber);
                });
                if (orderNumber != null) {
                    auditOrderNumber(company, fiscalYear, parseSequence(orderNumber), orderNumber);
                    return orderNumber;
                }
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
                lastError = ex;
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Failed to generate order number for company " + company.getCode());
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

    private long parseSequence(String orderNumber) {
        if (orderNumber == null || orderNumber.isBlank()) {
            return 0L;
        }
        int lastDash = orderNumber.lastIndexOf('-');
        if (lastDash < 0 || lastDash + 1 >= orderNumber.length()) {
            return 0L;
        }
        try {
            return Long.parseLong(orderNumber.substring(lastDash + 1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
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
