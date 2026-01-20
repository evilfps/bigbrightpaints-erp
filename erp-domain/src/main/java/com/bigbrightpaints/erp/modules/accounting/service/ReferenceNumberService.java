package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReferenceNumberService {

    private static final int DEFAULT_PADDING = 4;
    private static final Logger log = LoggerFactory.getLogger(ReferenceNumberService.class);

    private final NumberSequenceService numberSequenceService;
    private final AuditService auditService;

    public ReferenceNumberService(NumberSequenceService numberSequenceService, AuditService auditService) {
        this.numberSequenceService = numberSequenceService;
        this.auditService = auditService;
    }

    public String nextJournalReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "JRN-%s-%s".formatted(company.getCode(), formatPeriod(period));
        return generate(company, key, "journal");
    }

    public String dealerReceiptReference(Company company, Dealer dealer) {
        String normalized = sanitize(dealer.getCode());
        String key = "RCPT-%s".formatted(normalized);
        return generate(company, key, "dealer-receipt");
    }

    public String salesOrderReference(Company company, String orderNumber) {
        String normalized = sanitize(orderNumber);
        String key = "SALE-%s".formatted(normalized);
        return generate(company, key, "sales-journal");
    }

    public String supplierPaymentReference(Company company, Supplier supplier) {
        String normalized = sanitize(supplier.getCode());
        String key = "SUP-%s".formatted(normalized);
        return generate(company, key, "supplier-payment");
    }

    public String payrollPaymentReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "PAYROLL-%s".formatted(formatPeriod(period));
        return generate(company, key, "payroll");
    }

    public String rawMaterialReceiptReference(Company company, String batchCode) {
        String normalized = sanitize(batchCode);
        String key = "RM-%s".formatted(normalized);
        return generate(company, key, "raw-material");
    }

    public String costAllocationReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "COST-ALLOC-%s".formatted(formatPeriod(period));
        return generate(company, key, "cost-allocation");
    }

    public String invoiceJournalReference(Company company) {
        YearMonth period = YearMonth.now(resolveZone(company));
        String key = "INVJ-%s-%s".formatted(company.getCode(), formatPeriod(period));
        return generate(company, key, "invoice-journal");
    }

    public String purchaseReference(Company company, Supplier supplier, String invoiceNumber) {
        String supplierCode = sanitize(supplier != null ? supplier.getCode() : null);
        String invoicePart = sanitize(invoiceNumber);
        String key = "RMP-%s-%s-%s".formatted(company.getCode(), supplierCode, invoicePart);
        return generate(company, key, "purchase");
    }

    public String purchaseReturnReference(Company company, Supplier supplier) {
        String supplierCode = sanitize(supplier != null ? supplier.getCode() : null);
        String key = "PRN-%s-%s".formatted(company.getCode(), supplierCode);
        return generate(company, key, "purchase-return");
    }

    public String inventoryAdjustmentReference(Company company, String adjustmentType) {
        String key = "ADJ-%s-%s".formatted(company.getCode(), sanitize(adjustmentType));
        return generate(company, key, "inventory-adjustment");
    }

    public String openingStockReference(Company company) {
        String companyCode = sanitize(company != null ? company.getCode() : null);
        String key = "OPEN-STOCK-%s".formatted(companyCode);
        return generate(company, key, "opening-stock");
    }

    public String reversalReference(String originalReference) {
        return originalReference + "-REV";
    }

    private ZoneId resolveZone(Company company) {
        try {
            return ZoneId.of(company.getTimezone() == null ? "UTC" : company.getTimezone());
        } catch (Exception ex) {
            log.warn("Invalid timezone '{}' for company {}, defaulting to UTC", company.getTimezone(), company.getCode());
            return ZoneId.of("UTC");
        }
    }

    private String formatPeriod(YearMonth period) {
        return "%04d%02d".formatted(period.getYear(), period.getMonthValue());
    }

    private String generate(Company company, String key, String category) {
        String reference = formatted(key, numberSequenceService.nextValue(company, key));
        auditReference(company, key, reference, category);
        return reference;
    }

    private String formatted(String key, long sequence) {
        String format = "%s-%0" + DEFAULT_PADDING + "d";
        return format.formatted(key, sequence);
    }

    private void auditReference(Company company, String key, String reference, String category) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("category", category);
        metadata.put("sequenceKey", key);
        if (reference != null) {
            metadata.put("reference", reference);
        }
        if (company != null) {
            if (company.getId() != null) {
                metadata.put("companyId", company.getId().toString());
            }
            if (StringUtils.hasText(company.getCode())) {
                metadata.put("companyCode", company.getCode());
            }
        }
        auditService.logSuccess(AuditEvent.REFERENCE_GENERATED, metadata);
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "GEN";
        }
        return value.toUpperCase().replaceAll("[^A-Z0-9-]", "");
    }
}
