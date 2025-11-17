package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceLineDto;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class InvoiceService {

    private final CompanyContextService companyContextService;
    private final InvoiceRepository invoiceRepository;
    private final DealerRepository dealerRepository;
    private final SalesService salesService;
    private final InvoiceNumberService invoiceNumberService;
    private final JournalEntryRepository journalEntryRepository;
    private final SalesJournalService salesJournalService;

    public InvoiceService(CompanyContextService companyContextService,
                          InvoiceRepository invoiceRepository,
                          DealerRepository dealerRepository,
                          SalesService salesService,
                          InvoiceNumberService invoiceNumberService,
                          JournalEntryRepository journalEntryRepository,
                          SalesJournalService salesJournalService) {
        this.companyContextService = companyContextService;
        this.invoiceRepository = invoiceRepository;
        this.dealerRepository = dealerRepository;
        this.salesService = salesService;
        this.invoiceNumberService = invoiceNumberService;
        this.journalEntryRepository = journalEntryRepository;
        this.salesJournalService = salesJournalService;
    }

    @Transactional
    public InvoiceDto issueInvoiceForOrder(Long salesOrderId) {
        Invoice existing = invoiceRepository.findBySalesOrderId(salesOrderId).orElse(null);
        if (existing != null) {
            return toDto(existing);
        }
        SalesOrder order = salesService.getOrderWithItems(salesOrderId);
        if (order.getDealer() == null) {
            throw new IllegalStateException("Dealer is required to issue an invoice");
        }
        Invoice invoice = new Invoice();
        invoice.setCompany(order.getCompany());
        invoice.setDealer(order.getDealer());
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber(invoiceNumberService.nextInvoiceNumber(order.getCompany()));
        invoice.setCurrency(order.getCurrency());
        invoice.setIssueDate(currentDate(order.getCompany()));
        invoice.setDueDate(invoice.getIssueDate().plusDays(15));
        invoice.setStatus("ISSUED");
        invoice.setNotes("Auto-issued for order " + order.getOrderNumber());

        BigDecimal computedSubtotal = BigDecimal.ZERO;
        BigDecimal computedTax = BigDecimal.ZERO;
        for (SalesOrderItem item : order.getItems()) {
            InvoiceLine line = new InvoiceLine();
            line.setInvoice(invoice);
            line.setProductCode(item.getProductCode());
            line.setDescription(item.getDescription());
            line.setQuantity(item.getQuantity());
            line.setUnitPrice(item.getUnitPrice());
            BigDecimal lineSubtotal = item.getLineSubtotal() != null
                    ? item.getLineSubtotal()
                    : multiply(item.getQuantity(), item.getUnitPrice());
            BigDecimal lineTax = item.getGstAmount() != null ? item.getGstAmount() : BigDecimal.ZERO;
            BigDecimal taxRate = item.getGstRate() != null ? item.getGstRate() : BigDecimal.ZERO;
            line.setTaxRate(taxRate);
            line.setLineTotal(lineSubtotal.add(lineTax));
            computedSubtotal = computedSubtotal.add(lineSubtotal);
            computedTax = computedTax.add(lineTax);
            invoice.getLines().add(line);
        }
        BigDecimal subtotal = order.getSubtotalAmount() != null && order.getSubtotalAmount().compareTo(BigDecimal.ZERO) > 0
                ? order.getSubtotalAmount()
                : computedSubtotal;
        BigDecimal taxTotal = order.getGstTotal() != null && order.getGstTotal().compareTo(BigDecimal.ZERO) > 0
                ? order.getGstTotal()
                : computedTax;
        invoice.setSubtotal(subtotal);
        invoice.setTaxTotal(taxTotal);
        invoice.setTotalAmount(subtotal.add(taxTotal));
        JournalEntry journalEntry = resolveInvoiceJournal(order, invoice);
        if (journalEntry != null) {
            invoice.setJournalEntry(journalEntry);
        }
        Invoice saved = invoiceRepository.save(invoice);

        return toDto(saved);
    }

    public List<InvoiceDto> listInvoices() {
        Company company = companyContextService.requireCurrentCompany();
        return invoiceRepository.findByCompanyOrderByIssueDateDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public List<InvoiceDto> listDealerInvoices(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
        return invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
                .map(this::toDto)
                .toList();
    }

    public InvoiceDto getInvoice(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        return toDto(invoice);
    }

    private JournalEntry resolveInvoiceJournal(SalesOrder order, Invoice invoice) {
        Company company = order.getCompany();
        String reference = "SALE-" + order.getId();
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseGet(() -> createInvoiceJournal(order, invoice, reference));
    }

    private JournalEntry createInvoiceJournal(SalesOrder order, Invoice invoice, String reference) {
        Long entryId = salesJournalService.postSalesJournal(
                order,
                invoice.getTotalAmount(),
                reference,
                invoice.getIssueDate(),
                "Invoice " + invoice.getInvoiceNumber());
        if (entryId == null) {
            return null;
        }
        return journalEntryRepository.findByCompanyAndId(order.getCompany(), entryId)
                .orElseThrow(() -> new IllegalStateException("Sales journal entry not found for invoice reference " + reference));
    }

    private LocalDate currentDate(Company company) {
        ZoneId zone = ZoneId.of(company.getTimezone());
        return LocalDate.now(zone);
    }

    private BigDecimal multiply(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return BigDecimal.ZERO;
        }
        return a.multiply(b);
    }

    private InvoiceDto toDto(Invoice invoice) {
        List<InvoiceLineDto> lineDtos = invoice.getLines().stream()
                .map(line -> new InvoiceLineDto(
                        line.getId(),
                        line.getProductCode(),
                        line.getDescription(),
                        line.getQuantity(),
                        line.getUnitPrice(),
                        line.getTaxRate(),
                        line.getLineTotal()))
                .toList();
        Dealer dealer = invoice.getDealer();
        return new InvoiceDto(
                invoice.getId(),
                invoice.getPublicId(),
                invoice.getInvoiceNumber(),
                invoice.getStatus(),
                invoice.getSubtotal(),
                invoice.getTaxTotal(),
                invoice.getTotalAmount(),
                invoice.getCurrency(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                dealer != null ? dealer.getId() : null,
                dealer != null ? dealer.getName() : null,
                invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null,
                invoice.getJournalEntry() != null ? invoice.getJournalEntry().getId() : null,
                invoice.getCreatedAt(),
                lineDtos
        );
    }
}
