package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReferenceNumberService {

    private static final int DEFAULT_PADDING = 4;

    private final NumberSequenceService numberSequenceService;

    public ReferenceNumberService(NumberSequenceService numberSequenceService) {
        this.numberSequenceService = numberSequenceService;
    }

    public String nextJournalReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "JRN-%s-%s".formatted(company.getCode(), formatPeriod(period));
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String dealerReceiptReference(Company company, Dealer dealer) {
        String normalized = sanitize(dealer.getCode());
        String key = "RCPT-%s".formatted(normalized);
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String salesOrderReference(Company company, String orderNumber) {
        String normalized = sanitize(orderNumber);
        String key = "SALE-%s".formatted(normalized);
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String supplierPaymentReference(Company company, Supplier supplier) {
        String normalized = sanitize(supplier.getCode());
        String key = "SUP-%s".formatted(normalized);
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String payrollPaymentReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "PAYROLL-%s".formatted(formatPeriod(period));
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String rawMaterialReceiptReference(Company company, String batchCode) {
        String normalized = sanitize(batchCode);
        String key = "RM-%s".formatted(normalized);
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String costAllocationReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "COST-ALLOC-%s".formatted(formatPeriod(period));
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String reversalReference(String originalReference) {
        return originalReference + "-REV";
    }

    private ZoneId resolveZone(Company company) {
        return ZoneId.of(company.getTimezone() == null ? "UTC" : company.getTimezone());
    }

    private String formatPeriod(YearMonth period) {
        return "%04d%02d".formatted(period.getYear(), period.getMonthValue());
    }

    private String formatted(String key, long sequence) {
        return "%s-%0" + DEFAULT_PADDING + "d".formatted(key, sequence);
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "GEN";
        }
        return value.replaceAll("[^A-Z0-9]", "").toUpperCase();
    }
}
