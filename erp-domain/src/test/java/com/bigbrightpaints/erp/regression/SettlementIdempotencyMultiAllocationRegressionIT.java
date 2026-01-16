package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Settlement idempotency supports multiple allocations")
class SettlementIdempotencyMultiAllocationRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-009";

    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void multiAllocationSettlementAllowsSharedIdempotencyKey() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "LF-009 Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);

        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER")
                .orElseThrow();

        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
        entry.setReferenceNumber("LF-009-SETTLE");
        entry.setEntryDate(LocalDate.of(2026, 1, 12));
        entry.setStatus("POSTED");
        entry.setCurrency("INR");
        JournalEntry savedEntry = journalEntryRepository.save(entry);

        Invoice first = new Invoice();
        first.setCompany(company);
        first.setDealer(dealer);
        first.setInvoiceNumber("LF-009-INV-1");
        first.setStatus("ISSUED");
        first.setTotalAmount(new BigDecimal("100.00"));
        first.setOutstandingAmount(new BigDecimal("100.00"));
        first.setCurrency("INR");

        Invoice second = new Invoice();
        second.setCompany(company);
        second.setDealer(dealer);
        second.setInvoiceNumber("LF-009-INV-2");
        second.setStatus("ISSUED");
        second.setTotalAmount(new BigDecimal("200.00"));
        second.setOutstandingAmount(new BigDecimal("200.00"));
        second.setCurrency("INR");

        Invoice savedFirst = invoiceRepository.save(first);
        Invoice savedSecond = invoiceRepository.save(second);

        PartnerSettlementAllocation firstAllocation = new PartnerSettlementAllocation();
        firstAllocation.setCompany(company);
        firstAllocation.setPartnerType(PartnerType.DEALER);
        firstAllocation.setDealer(dealer);
        firstAllocation.setInvoice(savedFirst);
        firstAllocation.setJournalEntry(savedEntry);
        firstAllocation.setSettlementDate(LocalDate.of(2026, 1, 12));
        firstAllocation.setAllocationAmount(new BigDecimal("60.00"));
        firstAllocation.setDiscountAmount(BigDecimal.ZERO);
        firstAllocation.setWriteOffAmount(BigDecimal.ZERO);
        firstAllocation.setFxDifferenceAmount(BigDecimal.ZERO);
        firstAllocation.setCurrency("INR");
        firstAllocation.setIdempotencyKey("SETTLE-009");

        PartnerSettlementAllocation secondAllocation = new PartnerSettlementAllocation();
        secondAllocation.setCompany(company);
        secondAllocation.setPartnerType(PartnerType.DEALER);
        secondAllocation.setDealer(dealer);
        secondAllocation.setInvoice(savedSecond);
        secondAllocation.setJournalEntry(savedEntry);
        secondAllocation.setSettlementDate(LocalDate.of(2026, 1, 12));
        secondAllocation.setAllocationAmount(new BigDecimal("140.00"));
        secondAllocation.setDiscountAmount(BigDecimal.ZERO);
        secondAllocation.setWriteOffAmount(BigDecimal.ZERO);
        secondAllocation.setFxDifferenceAmount(BigDecimal.ZERO);
        secondAllocation.setCurrency("INR");
        secondAllocation.setIdempotencyKey("SETTLE-009");

        settlementAllocationRepository.saveAll(List.of(firstAllocation, secondAllocation));

        List<PartnerSettlementAllocation> allocations =
                settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, "SETTLE-009");

        assertThat(allocations).hasSize(2);
        assertThat(allocations)
                .extracting(row -> row.getInvoice() != null ? row.getInvoice().getId() : null)
                .containsExactlyInAnyOrder(savedFirst.getId(), savedSecond.getId());
    }
}
