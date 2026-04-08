package com.bigbrightpaints.erp.modules.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

class InvoicePdfServiceHydrationIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INV-PDF-HYDRATION";
  private static final String PASSWORD = "changeme";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private CompanyScopedInvoiceLookupService invoiceLookupService;
  @Autowired private InvoicePdfService invoicePdfService;

  @PersistenceContext private EntityManager entityManager;

  private Company company;
  private Dealer dealer;
  private SalesOrder salesOrder;
  private Invoice invoice;

  @BeforeEach
  void setUp() {
    UserAccount portalUser =
        dataSeeder.ensureUser(
            "invoice-hydration-dealer@bbp.com",
            PASSWORD,
            "Invoice Hydration Dealer",
            COMPANY_CODE,
            List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        "invoice-hydration-admin@bbp.com",
        PASSWORD,
        "Invoice Hydration Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN"));

    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "INV-HYDRATE-DEALER")
            .orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode("INV-HYDRATE-DEALER");
    dealer.setName("Invoice Hydration Dealer Pvt Ltd");
    dealer.setCompanyName("Invoice Hydration Dealer Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setCreditLimit(new BigDecimal("100000.00"));
    dealer.setStatus("ACTIVE");
    dealer.setPortalUser(portalUser);
    dealer = dealerRepository.saveAndFlush(dealer);

    salesOrder =
        salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer).stream()
            .filter(existing -> "SO-INV-HYDRATE-1".equals(existing.getOrderNumber()))
            .findFirst()
            .orElseGet(SalesOrder::new);
    salesOrder.setCompany(company);
    salesOrder.setDealer(dealer);
    salesOrder.setOrderNumber("SO-INV-HYDRATE-1");
    salesOrder.setStatus("INVOICED");
    salesOrder.setCurrency("INR");
    salesOrder.setSubtotalAmount(new BigDecimal("1000.00"));
    salesOrder.setGstTotal(new BigDecimal("180.00"));
    salesOrder.setGstRate(new BigDecimal("18.00"));
    salesOrder.setGstTreatment("INCLUSIVE");
    salesOrder.setGstInclusive(true);
    salesOrder.setGstRoundingAdjustment(BigDecimal.ZERO);
    salesOrder.setTotalAmount(new BigDecimal("1180.00"));
    salesOrder = salesOrderRepository.saveAndFlush(salesOrder);

    invoice =
        invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
            .filter(existing -> "INV-HYDRATE-001".equals(existing.getInvoiceNumber()))
            .findFirst()
            .orElseGet(Invoice::new);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setSalesOrder(salesOrder);
    invoice.setInvoiceNumber("INV-HYDRATE-001");
    invoice.setStatus("OPEN");
    invoice.setIssueDate(LocalDate.now().minusDays(1));
    invoice.setDueDate(LocalDate.now().plusDays(14));
    invoice.setSubtotal(new BigDecimal("1000.00"));
    invoice.setTaxTotal(new BigDecimal("180.00"));
    invoice.setTotalAmount(new BigDecimal("1180.00"));
    invoice.setOutstandingAmount(new BigDecimal("1180.00"));
    invoice.setCurrency("INR");

    invoice.getLines().clear();
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductCode("FG-HYDRATE-1");
    line.setDescription("Hydration Paint");
    line.setQuantity(new BigDecimal("10"));
    line.setUnitPrice(new BigDecimal("100.00"));
    line.setTaxRate(new BigDecimal("18.00"));
    line.setLineTotal(new BigDecimal("1180.00"));
    line.setTaxableAmount(new BigDecimal("1000.00"));
    line.setTaxAmount(new BigDecimal("180.00"));
    line.setDiscountAmount(BigDecimal.ZERO);
    line.setCgstAmount(new BigDecimal("90.00"));
    line.setSgstAmount(new BigDecimal("90.00"));
    line.setIgstAmount(BigDecimal.ZERO);
    invoice.getLines().add(line);

    invoice = invoiceRepository.saveAndFlush(invoice);
  }

  @Test
  void requireInvoicePdf_hydratesDealerAndSalesOrderForDetachedRenderPath() {
    Invoice hydrated = invoiceLookupService.requireInvoicePdf(company, invoice.getId());
    entityManager.detach(hydrated);

    assertThatNoException()
        .isThrownBy(
            () -> {
              assertThat(hydrated.getDealer().getName()).isEqualTo(dealer.getName());
              assertThat(hydrated.getSalesOrder().getOrderNumber())
                  .isEqualTo(salesOrder.getOrderNumber());
              assertThat(hydrated.getLines()).hasSize(1);
              assertThat(hydrated.getLines().getFirst().getDescription())
                  .isEqualTo("Hydration Paint");
            });
  }

  @Test
  void renderInvoicePdf_returnsPdfDocumentForHydratedInvoice() {
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    try {
      InvoicePdfService.PdfDocument pdf = invoicePdfService.renderInvoicePdf(invoice.getId());
      assertThat(pdf.fileName()).contains("INV-HYDRATE-001");
      assertThat(pdf.content()).isNotEmpty();
    } finally {
      CompanyContextHolder.clear();
    }
  }
}
