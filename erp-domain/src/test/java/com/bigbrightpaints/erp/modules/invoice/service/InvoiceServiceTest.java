package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private SalesService salesService;
    @Mock
    private InvoiceNumberService invoiceNumberService;
    @Mock
    private SalesJournalService salesJournalService;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private JournalReferenceResolver journalReferenceResolver;
    @Mock
    private DealerLedgerService dealerLedgerService;

    private InvoiceService invoiceService;
    private Company company;

    @BeforeEach
    void setup() {
        invoiceService = new InvoiceService(
                companyContextService,
                invoiceRepository,
                salesService,
                invoiceNumberService,
                salesJournalService,
                companyEntityLookup,
                journalReferenceResolver,
                dealerLedgerService
        );
        company = new Company();
        company.setTimezone("UTC");
    }

    @Test
    void issueInvoiceForOrder_setsSalesJournalEntryIdOnOrder() {
        Long orderId = 44L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-44");
        order.setCurrency("INR");
        order.setSubtotalAmount(new BigDecimal("100.00"));

        SalesOrderItem item = new SalesOrderItem();
        item.setProductCode("SKU-1");
        item.setDescription("Paint");
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setLineSubtotal(new BigDecimal("100.00"));
        order.getItems().add(item);

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);
        when(invoiceNumberService.nextInvoiceNumber(company)).thenReturn("INV-44");
        when(journalReferenceResolver.findExistingEntry(eq(company), anyString())).thenReturn(Optional.empty());
        when(salesJournalService.postSalesJournal(eq(order), any(), anyString(), any(), anyString())).thenReturn(100L);

        JournalEntry entry = new JournalEntry();
        ReflectionTestUtils.setField(entry, "id", 100L);
        when(companyEntityLookup.requireJournalEntry(company, 100L)).thenReturn(entry);

        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getSalesJournalEntryId()).isEqualTo(100L);
        assertThat(dto.journalEntryId()).isEqualTo(100L);
    }
}
