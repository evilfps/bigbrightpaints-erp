package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Aged debtors uses outstanding amounts")
class AgedDebtorsOutstandingRegressionIT extends AbstractIntegrationTest {

    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private ReportService reportService;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void agedDebtorsBucketsUseOutstandingAmount() {
        String companyCode = uniqueCompanyCode("LF-002");
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);

        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setCode("DEAL-" + companyCode);
        dealer.setName("Dealer " + companyCode);
        dealer = dealerRepository.save(dealer);

        BigDecimal totalAmount = new BigDecimal("100.00");
        BigDecimal outstandingAmount = new BigDecimal("40.00");

        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-" + companyCode);
        invoice.setStatus("ISSUED");
        invoice.setSubtotal(totalAmount);
        invoice.setTaxTotal(BigDecimal.ZERO);
        invoice.setTotalAmount(totalAmount);
        invoice.setOutstandingAmount(outstandingAmount);
        invoice.setIssueDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().minusDays(10));
        invoiceRepository.save(invoice);

        List<AgedDebtorDto> debtors = reportService.agedDebtors();
        assertThat(debtors).hasSize(1);

        AgedDebtorDto debtor = debtors.get(0);
        assertThat(debtor.thirtyDays()).isEqualByComparingTo(outstandingAmount);
        assertThat(debtor.current()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(debtor.sixtyDays()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(debtor.ninetyDays()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private String uniqueCompanyCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
