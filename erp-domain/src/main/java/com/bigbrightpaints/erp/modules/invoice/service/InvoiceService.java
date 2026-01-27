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
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
    private final SalesOrderRepository salesOrderRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final SalesJournalService salesJournalService;
    private final CompanyEntityLookup companyEntityLookup;
    private final JournalReferenceResolver journalReferenceResolver;
    private final DealerLedgerService dealerLedgerService;
    private final PackagingSlipRepository packagingSlipRepository;

    public InvoiceService(CompanyContextService companyContextService,
                          InvoiceRepository invoiceRepository,
                          SalesService salesService,
                          SalesOrderRepository salesOrderRepository,
                          InvoiceNumberService invoiceNumberService,
                          SalesJournalService salesJournalService,
                          CompanyEntityLookup companyEntityLookup,
                          JournalReferenceResolver journalReferenceResolver,
                          DealerLedgerService dealerLedgerService,
                          PackagingSlipRepository packagingSlipRepository) {
        this.companyContextService = companyContextService;
        this.invoiceRepository = invoiceRepository;
        this.salesService = salesService;
        this.salesOrderRepository = salesOrderRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.salesJournalService = salesJournalService;
        this.companyEntityLookup = companyEntityLookup;
        this.journalReferenceResolver = journalReferenceResolver;
        this.dealerLedgerService = dealerLedgerService;
        this.packagingSlipRepository = packagingSlipRepository;
    }

    @Transactional
    public InvoiceDto issueInvoiceForOrder(Long salesOrderId) {
        Company company = companyContextService.requireCurrentCompany();
        List<Invoice> existingInvoices = invoiceRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId);
        if (!existingInvoices.isEmpty()) {
            if (existingInvoices.size() == 1) {
                Invoice existing = existingInvoices.get(0);
                SalesOrder existingOrder = salesService.getOrderWithItems(salesOrderId);
                if (existingOrder.getFulfillmentInvoiceId() == null && existing.getId() != null) {
                    existingOrder.setFulfillmentInvoiceId(existing.getId());
                    salesOrderRepository.save(existingOrder);
                }
                linkInvoiceToPackagingSlip(existingOrder, existing);
                return toDto(existing);
            }
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Multiple invoices exist for order; issue invoices per dispatch");
        }
        SalesOrder order = salesService.getOrderWithItems(salesOrderId);
        if (order.getDealer() == null) {
            throw new IllegalStateException("Dealer is required to issue an invoice");
        }
        List<PackagingSlip> slips = Optional
                .ofNullable(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, salesOrderId))
                .orElse(List.of());
        if (!slips.isEmpty()) {
            if (slips.size() > 1) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Multiple packing slips exist for order; issue invoices per dispatch");
            }
            PackagingSlip slip = slips.get(0);
            DispatchConfirmResponse response = salesService.confirmDispatch(new DispatchConfirmRequest(
                    slip.getId(),
                    null,
                    null,
                    null,
                    null,
                    Boolean.FALSE,
                    null,
                    null
            ));
            if (response == null || response.finalInvoiceId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dispatch confirmation did not produce an invoice");
            }
            return getInvoice(response.finalInvoiceId());
        }
        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                "Packing slip is required before issuing an invoice; issue invoices per dispatch");
    }

    private void linkInvoiceToPackagingSlip(SalesOrder order, Invoice invoice) {
        if (order == null || invoice == null || invoice.getId() == null) {
            return;
        }
        Company company = order.getCompany();
        if (company == null || order.getId() == null) {
            return;
        }
        List<PackagingSlip> slips = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId());
        if (slips.size() == 1) {
            PackagingSlip slip = slips.get(0);
            if (slip.getInvoiceId() == null) {
                slip.setInvoiceId(invoice.getId());
                packagingSlipRepository.save(slip);
            }
        }
    }

    private BigDecimal currency(BigDecimal value) {
        return MoneyUtils.roundCurrency(value);
    }

    @Transactional
    public List<InvoiceDto> listInvoices(int page, int size) {
        Company company = companyContextService.requireCurrentCompany();
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<Long> invoiceIds = invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, pageable);
        List<Long> ids = invoiceIds.getContent();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Invoice> invoices = invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, ids);
        return invoices.stream().map(this::toDto).toList();
    }

    @Transactional
    public List<InvoiceDto> listDealerInvoices(Long dealerId, int page, int size) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = companyEntityLookup.requireDealer(company, dealerId);
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<Long> invoiceIds = invoiceRepository.findIdsByCompanyAndDealerOrderByIssueDateDescIdDesc(company, dealer, pageable);
        List<Long> ids = invoiceIds.getContent();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Invoice> invoices = invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, ids);
        return invoices.stream().map(this::toDto).toList();
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
        String reference = resolveInvoiceReference(order, invoice);
        Optional<JournalEntry> existing = journalReferenceResolver.findExistingEntry(company, reference);
        return existing.orElseGet(() -> createInvoiceJournal(order, invoice, reference));
    }

    private String resolveInvoiceReference(SalesOrder order, Invoice invoice) {
        if (invoice != null && StringUtils.hasText(invoice.getInvoiceNumber())) {
            return invoice.getInvoiceNumber().trim();
        }
        return SalesOrderReference.invoiceReference(order);
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
                        line.getLineTotal(),
                        line.getTaxableAmount(),
                        line.getTaxAmount(),
                        line.getDiscountAmount()))
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
