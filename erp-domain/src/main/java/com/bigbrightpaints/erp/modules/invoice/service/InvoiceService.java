package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceLineDto;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class InvoiceService {

    private final CompanyContextService companyContextService;
    private final InvoiceRepository invoiceRepository;
    private final SalesService salesService;
    private final InvoiceNumberService invoiceNumberService;
    private final SalesJournalService salesJournalService;
    private final CompanyEntityLookup companyEntityLookup;
    private final JournalReferenceResolver journalReferenceResolver;
    private final DealerLedgerService dealerLedgerService;

    public InvoiceService(CompanyContextService companyContextService,
                          InvoiceRepository invoiceRepository,
                          SalesService salesService,
                          InvoiceNumberService invoiceNumberService,
                          SalesJournalService salesJournalService,
                          CompanyEntityLookup companyEntityLookup,
                          JournalReferenceResolver journalReferenceResolver,
                          DealerLedgerService dealerLedgerService) {
        this.companyContextService = companyContextService;
        this.invoiceRepository = invoiceRepository;
        this.salesService = salesService;
        this.invoiceNumberService = invoiceNumberService;
        this.salesJournalService = salesJournalService;
        this.companyEntityLookup = companyEntityLookup;
        this.journalReferenceResolver = journalReferenceResolver;
        this.dealerLedgerService = dealerLedgerService;
    }

    @Transactional
    public InvoiceDto issueInvoiceForOrder(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<Invoice> existingInvoices = invoiceRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
        if (!existingInvoices.isEmpty()) {
            if (existingInvoices.size() == 1) {
                return toDto(existingInvoices.get(0));
            }
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Multiple invoices exist for order; issue invoices per dispatch");
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
                    : MoneyUtils.safeMultiply(item.getQuantity(), item.getUnitPrice());
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
        invoice.setOutstandingAmount(subtotal.add(taxTotal));
        JournalEntry journalEntry = resolveInvoiceJournal(order, invoice);
        if (journalEntry != null) {
            invoice.setJournalEntry(journalEntry);
            if (order.getSalesJournalEntryId() == null) {
                order.setSalesJournalEntryId(journalEntry.getId());
            }
        }
        Invoice saved = invoiceRepository.save(invoice);
        dealerLedgerService.syncInvoiceLedger(saved, null);

        return toDto(saved);
    }

    @Transactional
    public List<InvoiceDto> listInvoices() {
        Company company = companyContextService.requireCurrentCompany();
        return invoiceRepository.findByCompanyOrderByIssueDateDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public List<InvoiceDto> listDealerInvoices(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = companyEntityLookup.requireDealer(company, dealerId);
        return invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public InvoiceDto getInvoice(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.findByCompanyAndId(company, id)
                .orElseGet(() -> companyEntityLookup.requireInvoice(company, id));
        return toDto(invoice);
    }

    private JournalEntry resolveInvoiceJournal(SalesOrder order, Invoice invoice) {
        Company company = order.getCompany();
        String reference = SalesOrderReference.invoiceReference(order);
        Optional<JournalEntry> existing = journalReferenceResolver.findExistingEntry(company, reference);
        return existing.orElseGet(() -> createInvoiceJournal(order, invoice, reference));
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
        return companyEntityLookup.requireJournalEntry(order.getCompany(), entryId);
    }

    private LocalDate currentDate(Company company) {
        ZoneId zone = ZoneId.of(company.getTimezone());
        return LocalDate.now(zone);
    }

    public record InvoiceWithEmail(InvoiceDto invoice, String dealerEmail, String companyName) {}

    @Transactional
    public InvoiceWithEmail getInvoiceWithDealerEmail(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.findByCompanyAndId(company, id)
                .orElseGet(() -> companyEntityLookup.requireInvoice(company, id));
        String dealerEmail = invoice.getDealer() != null ? invoice.getDealer().getEmail() : null;
        String companyName = invoice.getCompany() != null ? invoice.getCompany().getName() : null;
        return new InvoiceWithEmail(toDto(invoice), dealerEmail, companyName);
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
                invoice.getOutstandingAmount(),
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
