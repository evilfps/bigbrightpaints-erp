package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesDispatchReconciliationService;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderCrudService;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class InvoiceServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private SalesOrderCrudService salesOrderCrudService;
    @Mock
    private SalesDispatchReconciliationService salesDispatchReconciliationService;
    @Mock
    private SalesOrderRepository salesOrderRepository;
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
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private PartnerSettlementAllocationRepository settlementAllocationRepository;

    private InvoiceService invoiceService;
    private Company company;

    @BeforeEach
    void setup() {
        invoiceService = new InvoiceService(
                companyContextService,
                invoiceRepository,
                salesOrderCrudService,
                salesOrderRepository,
                companyEntityLookup,
                packagingSlipRepository,
                settlementAllocationRepository
        );
        company = new Company();
        company.setTimezone("UTC");
    }

    @Test
    void issueInvoiceForOrder_failsWhenNoSlipExists() {
        Long orderId = 44L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-44");
        order.setCurrency("INR");

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));

        verifyNoInteractions(invoiceNumberService);
        verifyNoInteractions(salesJournalService);
    }

    @Test
    void issueInvoiceForOrder_returnsExistingInvoiceWhenAlreadyExists() {
        Long orderId = 55L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 123L);
        existingInvoice.setCompany(company);
        existingInvoice.setInvoiceNumber("INV-55");
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(existingInvoice));

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-55");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isEqualTo(123L);
        assertThat(dto.id()).isEqualTo(123L);
        assertThat(slip.getInvoiceId()).isEqualTo(123L);
    }

    @Test
    void issueInvoiceForOrder_throwsWhenMultipleInvoicesAlreadyExist() {
        Long orderId = 54L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(new Invoice(), new Invoice()));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));
    }

    @Test
    void issueInvoiceForOrder_failsWhenOrderDealerIsMissing() {
        Long orderId = 59L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("SO-59");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));
    }

    @Test
    void issueInvoiceForOrder_existingInvoiceDoesNotSetOrderInvoiceIdWhenMultiSlip() {
        Long orderId = 56L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 123L);
        existingInvoice.setCompany(company);
        existingInvoice.setInvoiceNumber("INV-56");
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(existingInvoice));

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-56");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        PackagingSlip otherSlip = new PackagingSlip();
        ReflectionTestUtils.setField(otherSlip, "id", 100L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(slip, otherSlip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isNull();
        assertThat(dto.id()).isEqualTo(123L);
        verify(salesOrderRepository, org.mockito.Mockito.never()).save(any(SalesOrder.class));
    }

    @Test
    void issueInvoiceForOrder_existingInvoiceClearsStaleOrderInvoiceIdWhenMultiSlip() {
        Long orderId = 57L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 123L);
        existingInvoice.setCompany(company);
        existingInvoice.setInvoiceNumber("INV-57");
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(existingInvoice));

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-57");
        order.setCurrency("INR");
        order.setFulfillmentInvoiceId(999L);
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        PackagingSlip otherSlip = new PackagingSlip();
        ReflectionTestUtils.setField(otherSlip, "id", 100L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(slip, otherSlip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isNull();
        assertThat(dto.id()).isEqualTo(123L);
        verify(salesOrderRepository).save(order);
    }

    @Test
    void issueInvoiceForOrder_existingInvoiceSetsOrderInvoiceIdWhenOnlyOtherSlipCancelled() {
        Long orderId = 58L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 123L);
        existingInvoice.setCompany(company);
        existingInvoice.setInvoiceNumber("INV-58");
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(existingInvoice));

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-58");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        PackagingSlip cancelledSlip = new PackagingSlip();
        ReflectionTestUtils.setField(cancelledSlip, "id", 100L);
        cancelledSlip.setStatus("CANCELLED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(slip, cancelledSlip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isEqualTo(123L);
        assertThat(dto.id()).isEqualTo(123L);
        assertThat(slip.getInvoiceId()).isEqualTo(123L);
        verify(salesOrderRepository).save(order);
    }

    @Test
    void issueInvoiceForOrder_requiresDispatchOwnedInvoiceBoundaryWhenSlipExistsWithoutInvoice() {
        Long orderId = 78L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-78");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip activeSlip = new PackagingSlip();
        ReflectionTestUtils.setField(activeSlip, "id", 199L);
        PackagingSlip cancelledSlip = new PackagingSlip();
        ReflectionTestUtils.setField(cancelledSlip, "id", 200L);
        cancelledSlip.setStatus("CANCELLED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(activeSlip, cancelledSlip));
        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));

        verifyNoInteractions(salesDispatchReconciliationService);
    }


    @Test
    void issueInvoiceForOrder_rejectsWhenMultipleActiveSlipsExist() {
        Long orderId = 79L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-79");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip firstSlip = new PackagingSlip();
        ReflectionTestUtils.setField(firstSlip, "id", 101L);
        firstSlip.setStatus("RESERVED");
        PackagingSlip secondSlip = new PackagingSlip();
        ReflectionTestUtils.setField(secondSlip, "id", 102L);
        secondSlip.setStatus("PENDING_STOCK");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(firstSlip, secondSlip));

        ApplicationException ex = assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));

        assertThat(ex.getMessage()).contains("Multiple packing slips exist for order; issue invoices per dispatch");
        verifyNoInteractions(invoiceNumberService);
        verifyNoInteractions(salesJournalService);
    }
    @Test
    void issueInvoiceForOrder_existingInvoiceDoesNotReplayDispatchWhenSingleActiveSlipExists() {
        Long orderId = 79L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 321L);
        existingInvoice.setCompany(company);
        existingInvoice.setInvoiceNumber("INV-79");
        existingInvoice.setStatus("ISSUED");
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(existingInvoice));

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-79");
        order.setStatus("READY_TO_SHIP");
        order.setCurrency("INR");
        ReflectionTestUtils.setField(order, "id", orderId);
        existingInvoice.setSalesOrder(order);

        com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry journalEntry =
                new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 654L);
        journalEntry.setReferenceNumber("INV-BBP-SO-79");
        journalEntry.setStatus("POSTED");
        existingInvoice.setJournalEntry(journalEntry);

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip activeSlip = new PackagingSlip();
        ReflectionTestUtils.setField(activeSlip, "id", 201L);
        activeSlip.setSalesOrder(order);
        activeSlip.setSlipNumber("PS-79");
        activeSlip.setStatus("DISPATCHED");
        PackagingSlip cancelledSlip = new PackagingSlip();
        ReflectionTestUtils.setField(cancelledSlip, "id", 202L);
        cancelledSlip.setSalesOrder(order);
        cancelledSlip.setStatus("CANCELLED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(activeSlip, cancelledSlip));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(orderId)))
                .thenReturn(List.of(activeSlip, cancelledSlip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isEqualTo(321L);
        assertThat(dto.id()).isEqualTo(321L);
        assertThat(activeSlip.getInvoiceId()).isEqualTo(321L);
        assertThat(dto.lifecycle().workflowStatus()).isEqualTo("ISSUED");
        assertThat(dto.lifecycle().accountingStatus()).isEqualTo("POSTED");
        assertThat(dto.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("SOURCE_ORDER", "SALES_ORDER"),
                        org.assertj.core.groups.Tuple.tuple("DISPATCH", "PACKAGING_SLIP"),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNTING_ENTRY", "JOURNAL_ENTRY")
                );
        verify(salesOrderRepository).save(order);
        verifyNoInteractions(salesDispatchReconciliationService);
        verifyNoInteractions(invoiceNumberService);
        verifyNoInteractions(salesJournalService);
    }

    @Test
    void issueInvoiceForOrder_returnsInvoiceLinkedToDispatchedSlip() {
        Long orderId = 77L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-77");
        order.setCurrency("INR");

        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(123L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 123L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-77");
        when(invoiceRepository.findByCompanyAndId(company, 123L)).thenReturn(Optional.of(invoice));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(dto.id()).isEqualTo(123L);
        verifyNoInteractions(salesDispatchReconciliationService, invoiceNumberService);
    }

    @Test
    void issueInvoiceForOrder_rejectsDispatchedSlipWhenInvoiceMarkerIsMissing() {
        Long orderId = 780L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-780");
        ReflectionTestUtils.setField(order, "id", orderId);
        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 7801L);
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(null);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        ApplicationException ex = assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));

        assertThat(ex.getMessage()).contains("Final invoice is created by dispatch confirmation");
        verifyNoInteractions(salesDispatchReconciliationService);
    }

    @Test
    void issueInvoiceForOrder_failsWhenDispatchConfirmationDoesNotReturnInvoiceId() {
        Long orderId = 80L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-80");
        ReflectionTestUtils.setField(order, "id", orderId);
        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 801L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));
    }

    @Test
    void issueInvoiceForOrder_failsWhenMultipleActiveSlipsExist() {
        Long orderId = 81L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        ReflectionTestUtils.setField(order, "id", orderId);
        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip firstSlip = new PackagingSlip();
        ReflectionTestUtils.setField(firstSlip, "id", 8101L);
        firstSlip.setStatus("DISPATCHED");
        PackagingSlip secondSlip = new PackagingSlip();
        ReflectionTestUtils.setField(secondSlip, "id", 8102L);
        secondSlip.setStatus("READY");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(firstSlip, secondSlip));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));
        verifyNoInteractions(salesDispatchReconciliationService);
    }

    @Test
    void issueInvoiceForOrder_failsWhenOnlyCancelledSlipsExist() {
        Long orderId = 82L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        ReflectionTestUtils.setField(order, "id", orderId);
        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip cancelledSlip = new PackagingSlip();
        ReflectionTestUtils.setField(cancelledSlip, "id", 8201L);
        cancelledSlip.setStatus("CANCELLED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId))
                .thenReturn(List.of(cancelledSlip));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(orderId));
        verifyNoInteractions(salesDispatchReconciliationService);
    }

    @Test
    void listInvoices_batchesLinkedReferenceLookupsForPage() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(11L, 12L)));

        SalesOrder firstOrder = new SalesOrder();
        ReflectionTestUtils.setField(firstOrder, "id", 101L);
        firstOrder.setCompany(company);
        firstOrder.setOrderNumber("SO-101");
        firstOrder.setStatus("READY_TO_SHIP");

        SalesOrder secondOrder = new SalesOrder();
        ReflectionTestUtils.setField(secondOrder, "id", 102L);
        secondOrder.setCompany(company);
        secondOrder.setOrderNumber("SO-102");
        secondOrder.setStatus("INVOICED");

        Invoice firstInvoice = new Invoice();
        ReflectionTestUtils.setField(firstInvoice, "id", 11L);
        firstInvoice.setCompany(company);
        firstInvoice.setInvoiceNumber("INV-11");
        firstInvoice.setStatus("ISSUED");
        firstInvoice.setSalesOrder(firstOrder);

        Invoice secondInvoice = new Invoice();
        ReflectionTestUtils.setField(secondInvoice, "id", 12L);
        secondInvoice.setCompany(company);
        secondInvoice.setInvoiceNumber("INV-12");
        secondInvoice.setStatus("ISSUED");
        secondInvoice.setSalesOrder(secondOrder);

        when(invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, List.of(11L, 12L)))
                .thenReturn(List.of(firstInvoice, secondInvoice));

        PackagingSlip firstSlip = new PackagingSlip();
        ReflectionTestUtils.setField(firstSlip, "id", 201L);
        firstSlip.setSalesOrder(firstOrder);
        firstSlip.setSlipNumber("PS-201");
        firstSlip.setStatus("DISPATCHED");

        PackagingSlip secondSlip = new PackagingSlip();
        ReflectionTestUtils.setField(secondSlip, "id", 202L);
        secondSlip.setSalesOrder(secondOrder);
        secondSlip.setSlipNumber("PS-202");
        secondSlip.setStatus("DISPATCHED");

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(101L, 102L)))
                .thenReturn(List.of(firstSlip, secondSlip));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(101L, 102L)))
                .thenReturn(List.of(firstInvoice, secondInvoice));

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation allocation =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        ReflectionTestUtils.setField(allocation, "id", 301L);
        allocation.setInvoice(secondInvoice);
        allocation.setIdempotencyKey("settlement-301");
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(11L, 12L)))
                .thenReturn(List.of(allocation));

        List<InvoiceDto> invoices = invoiceService.listInvoices(0, 50);

        assertThat(invoices).hasSize(2);
        assertThat(invoices.get(0).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("SOURCE_ORDER", "DISPATCH");
        assertThat(invoices.get(1).linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType)
                .contains("SOURCE_ORDER", "DISPATCH", "SETTLEMENT");

        verify(packagingSlipRepository).findAllByCompanyAndSalesOrderIdIn(company, List.of(101L, 102L));
        verify(invoiceRepository).findByCompanyAndSalesOrder_IdIn(company, List.of(101L, 102L));
        verify(settlementAllocationRepository).findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(11L, 12L));
        verify(packagingSlipRepository, org.mockito.Mockito.never()).findAllByCompanyAndSalesOrderId(any(), anyLong());
        verify(invoiceRepository, org.mockito.Mockito.never()).findAllByCompanyAndSalesOrderId(any(), anyLong());
        verify(settlementAllocationRepository, org.mockito.Mockito.never()).findByCompanyAndInvoiceOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void listDealerInvoices_batchesLookupsForDealerPage() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 901L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireDealer(company, 901L)).thenReturn(dealer);
        when(invoiceRepository.findIdsByCompanyAndDealerOrderByIssueDateDescIdDesc(company, dealer, PageRequest.of(0, 25)))
                .thenReturn(new PageImpl<>(List.of(81L)));

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 701L);
        order.setCompany(company);
        order.setOrderNumber("SO-701");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 81L);
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-81");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        when(invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, List.of(81L))).thenReturn(List.of(invoice));

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 702L);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-702");
        slip.setStatus("DISPATCHED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(701L))).thenReturn(List.of(slip));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(701L))).thenReturn(List.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(81L))).thenReturn(List.of());

        List<InvoiceDto> invoices = invoiceService.listDealerInvoices(901L, 0, 25);

        assertThat(invoices).singleElement().extracting(InvoiceDto::invoiceNumber).isEqualTo("INV-81");
        verify(packagingSlipRepository).findAllByCompanyAndSalesOrderIdIn(company, List.of(701L));
        verify(invoiceRepository).findByCompanyAndSalesOrder_IdIn(company, List.of(701L));
        verify(invoiceRepository, org.mockito.Mockito.never()).findAllByCompanyAndSalesOrderId(any(), anyLong());
    }

    @Test
    void listInvoices_skipsInvoiceCountBatchWhenDispatchLinksAreExplicit() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(91L)));

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 191L);
        order.setCompany(company);
        order.setOrderNumber("SO-191");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 91L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-91");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);
        when(invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, List.of(91L)))
                .thenReturn(List.of(invoice));

        PackagingSlip explicitSlip = new PackagingSlip();
        ReflectionTestUtils.setField(explicitSlip, "id", 192L);
        explicitSlip.setSalesOrder(order);
        explicitSlip.setSlipNumber("PS-192");
        explicitSlip.setStatus("DISPATCHED");
        explicitSlip.setInvoiceId(91L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(191L)))
                .thenReturn(List.of(explicitSlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(91L)))
                .thenReturn(List.of());

        assertThat(invoiceService.listInvoices(0, 10)).singleElement().extracting(InvoiceDto::invoiceNumber).isEqualTo("INV-91");

        verify(invoiceRepository, org.mockito.Mockito.never()).findByCompanyAndSalesOrder_IdIn(any(), any());
    }

    @Test
    void getInvoice_filtersDispatchReferencesToCurrentInvoice() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1701L);
        order.setCompany(company);
        order.setOrderNumber("SO-1701");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1702L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1702");
        invoice.setStatus("ISSUED");

        PackagingSlip matchingSlip = new PackagingSlip();
        ReflectionTestUtils.setField(matchingSlip, "id", 1703L);
        matchingSlip.setSalesOrder(order);
        matchingSlip.setSlipNumber("PS-1703");
        matchingSlip.setStatus("DISPATCHED");
        matchingSlip.setInvoiceId(1702L);

        PackagingSlip unrelatedSlip = new PackagingSlip();
        ReflectionTestUtils.setField(unrelatedSlip, "id", 1704L);
        unrelatedSlip.setSalesOrder(order);
        unrelatedSlip.setSlipNumber("PS-1704");
        unrelatedSlip.setStatus("DISPATCHED");
        unrelatedSlip.setInvoiceId(1705L);

        when(invoiceRepository.findByCompanyAndId(company, 1702L)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1701L)))
                .thenReturn(List.of(matchingSlip, unrelatedSlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1702L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1702L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .extracting(LinkedBusinessReferenceDto::documentNumber)
                .containsExactly("PS-1703");
    }

    @Test
    void getInvoice_omitsDispatchReferencesWhenLegacySlipLinkageIsAmbiguous() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1801L);
        order.setCompany(company);
        order.setOrderNumber("SO-1801");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1802L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1802");
        invoice.setStatus("ISSUED");

        PackagingSlip firstLegacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(firstLegacySlip, "id", 1803L);
        firstLegacySlip.setSalesOrder(order);
        firstLegacySlip.setSlipNumber("PS-1803");
        firstLegacySlip.setStatus("DISPATCHED");

        PackagingSlip secondLegacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(secondLegacySlip, "id", 1804L);
        secondLegacySlip.setSalesOrder(order);
        secondLegacySlip.setSlipNumber("PS-1804");
        secondLegacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1802L)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1801L)))
                .thenReturn(List.of(firstLegacySlip, secondLegacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1802L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1802L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .isEmpty();
    }

    @Test
    void getInvoice_keepsSingleLegacyDispatchReferenceWhenLinkageIsUnambiguous() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1811L);
        order.setCompany(company);
        order.setOrderNumber("SO-1811");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1812L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1812");
        invoice.setStatus("ISSUED");

        PackagingSlip legacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(legacySlip, "id", 1813L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-1813");
        legacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1812L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(1811L))).thenReturn(List.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1811L)))
                .thenReturn(List.of(legacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1812L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1812L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .extracting(LinkedBusinessReferenceDto::documentNumber)
                .containsExactly("PS-1813");
    }

    @Test
    void getInvoice_omitsSingleLegacyDispatchReferenceWhenOrderHasMultipleInvoices() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1821L);
        order.setCompany(company);
        order.setOrderNumber("SO-1821");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1822L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1822");
        invoice.setStatus("ISSUED");

        Invoice otherInvoice = new Invoice();
        ReflectionTestUtils.setField(otherInvoice, "id", 1823L);
        otherInvoice.setCompany(company);
        otherInvoice.setSalesOrder(order);
        otherInvoice.setInvoiceNumber("INV-1823");
        otherInvoice.setStatus("ISSUED");

        PackagingSlip legacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(legacySlip, "id", 1824L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-1824");
        legacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1822L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(1821L)))
                .thenReturn(List.of(invoice, otherInvoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1821L)))
                .thenReturn(List.of(legacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1822L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1822L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .isEmpty();
    }

    @Test
    void getInvoice_keepsSingleLegacyDispatchReferenceWhenHistoricalInvoicesAreNotCurrent() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1831L);
        order.setCompany(company);
        order.setOrderNumber("SO-1831");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1832L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1832");
        invoice.setStatus("ISSUED");

        PackagingSlip legacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(legacySlip, "id", 1833L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-1833");
        legacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1832L)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1831L)))
                .thenReturn(List.of(legacySlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1832L)))
                .thenReturn(List.of());

        for (String historicalStatus : List.of("DRAFT", "VOID", "REVERSED")) {
            Invoice historicalInvoice = new Invoice();
            historicalInvoice.setCompany(company);
            historicalInvoice.setSalesOrder(order);
            historicalInvoice.setStatus(historicalStatus);
            when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(1831L)))
                    .thenReturn(List.of(invoice, historicalInvoice));

            assertThat(invoiceService.getInvoice(1832L).linkedReferences())
                    .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                    .extracting(LinkedBusinessReferenceDto::documentNumber)
                    .containsExactly("PS-1833");
        }
    }

    @Test
    void getInvoice_keepsLegacyDispatchWhenBatchInvoiceLookupReturnsNullForCurrentInvoice() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1841L);
        order.setCompany(company);
        order.setOrderNumber("SO-1841");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1842L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1842");
        invoice.setStatus("ISSUED");

        PackagingSlip legacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(legacySlip, "id", 1843L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-1843");
        legacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1842L)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1841L)))
                .thenReturn(List.of(legacySlip));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(1841L))).thenReturn(null);
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1842L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1842L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .extracting(LinkedBusinessReferenceDto::documentNumber)
                .containsExactly("PS-1843");
    }

    @Test
    void getInvoice_omitsLegacyDispatchWhenFallbackInvoiceStatusIsNotCurrent() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 1851L);
        order.setCompany(company);
        order.setOrderNumber("SO-1851");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1852L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        invoice.setInvoiceNumber("INV-1852");
        invoice.setStatus("VOID");

        PackagingSlip legacySlip = new PackagingSlip();
        ReflectionTestUtils.setField(legacySlip, "id", 1853L);
        legacySlip.setSalesOrder(order);
        legacySlip.setSlipNumber("PS-1853");
        legacySlip.setStatus("DISPATCHED");

        when(invoiceRepository.findByCompanyAndId(company, 1852L)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1851L)))
                .thenReturn(List.of(legacySlip));
        when(invoiceRepository.findByCompanyAndSalesOrder_IdIn(company, List.of(1851L))).thenReturn(null);
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1852L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1852L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .isEmpty();
    }

    @Test
    void helperMethods_coverLockedOrderAndSlipFallbackBranches() {
        Long orderId = 990L;
        SalesOrder lockedOrder = new SalesOrder();
        lockedOrder.setCompany(company);
        ReflectionTestUtils.setField(lockedOrder, "id", orderId);
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, orderId)).thenReturn(Optional.of(lockedOrder));

        @SuppressWarnings("unchecked")
        List<PackagingSlip> slips = (List<PackagingSlip>) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", company, orderId, true);
        SalesOrder resolved = ReflectionTestUtils.invokeMethod(invoiceService, "requireOrderForUpdate", company, orderId);

        assertThat(slips).isEmpty();
        assertThat(resolved).isSameAs(lockedOrder);
    }

    @Test
    void helperMethods_coverFallbackAndNoOpBranches() {
        Long orderId = 991L;
        when(salesOrderRepository.findWithItemsByCompanyAndIdForUpdate(company, orderId)).thenReturn(Optional.empty());

        SalesOrder fallbackOrder = new SalesOrder();
        fallbackOrder.setCompany(company);
        ReflectionTestUtils.setField(fallbackOrder, "id", orderId);
        when(salesOrderCrudService.getOrderWithItems(orderId)).thenReturn(fallbackOrder);

        PackagingSlip existingSlip = new PackagingSlip();
        ReflectionTestUtils.setField(existingSlip, "id", 911L);
        existingSlip.setStatus("CANCELLED");
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, orderId)).thenReturn(List.of(existingSlip));

        SalesOrder resolved = ReflectionTestUtils.invokeMethod(invoiceService, "requireOrderForUpdate", company, orderId);
        @SuppressWarnings("unchecked")
        List<PackagingSlip> slips = (List<PackagingSlip>) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", company, orderId, true);

        assertThat(resolved).isSameAs(fallbackOrder);
        assertThat(slips).containsExactly(existingSlip);
        assertThat((Long) ReflectionTestUtils.invokeMethod(invoiceService, "activeSlipCount", List.of(existingSlip))).isZero();
        assertThat((PackagingSlip) ReflectionTestUtils.invokeMethod(invoiceService, "findSingleActiveSlip", List.of(existingSlip))).isNull();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(invoiceService, "reconcileOrderInvoiceMarker", fallbackOrder, 1L, false)).isFalse();
    }

    @Test
    void listInvoices_returnsEmptyWhenNoIdsFound() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(invoiceService.listInvoices(0, 10)).isEmpty();
    }

    @Test
    void dealerListingAndLinkedReferenceHelpers_coverEmptyBranches() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 902L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireDealer(company, 902L)).thenReturn(dealer);
        when(invoiceRepository.findIdsByCompanyAndDealerOrderByIssueDateDescIdDesc(company, dealer, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(invoiceService.listDealerInvoices(902L, 0, 10)).isEmpty();
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "buildLinkedReferenceContext", null, List.of())).isNotNull();

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        ReflectionTestUtils.setField(order, "id", 903L);
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 904L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 905L);
        slip.setInvoiceId(904L);

        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", order, invoice, List.of(slip));

        verify(packagingSlipRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void helperMethods_coverOrderMarkerAndActiveSlipBranches() {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setFulfillmentInvoiceId(500L);
        ReflectionTestUtils.setField(order, "id", 950L);

        PackagingSlip activeSlip = new PackagingSlip();
        activeSlip.setStatus("DISPATCHED");
        ReflectionTestUtils.setField(activeSlip, "id", 951L);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(invoiceService, "reconcileOrderInvoiceMarker", order, 501L, false)).isTrue();
        assertThat(order.getFulfillmentInvoiceId()).isNull();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(invoiceService, "hasSingleActiveSlip", List.of(activeSlip))).isTrue();
        assertThat((PackagingSlip) ReflectionTestUtils.invokeMethod(invoiceService, "findSingleActiveSlip", List.of(activeSlip))).isSameAs(activeSlip);
    }

    @Test
    void unpagedAccessors_coverFallbackLookupAndLineMappingBranches() {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", 1500L);
        dealer.setName("Dealer 1500");
        dealer.setEmail("dealer1500@example.com");
        company.setName("BigBright 1500");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireDealer(company, 1500L)).thenReturn(dealer);

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1501L);
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-1501");
        invoice.setStatus("ISSUED");

        com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine line = new com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine();
        ReflectionTestUtils.setField(line, "id", 1502L);
        line.setProductCode("FG-1502");
        line.setDescription("Mapped line");
        line.setQuantity(new java.math.BigDecimal("2.00"));
        line.setUnitPrice(new java.math.BigDecimal("50.00"));
        line.setTaxRate(new java.math.BigDecimal("18.00"));
        line.setLineTotal(new java.math.BigDecimal("100.00"));
        line.setTaxableAmount(new java.math.BigDecimal("84.75"));
        line.setTaxAmount(new java.math.BigDecimal("15.25"));
        line.setDiscountAmount(new java.math.BigDecimal("5.00"));
        line.setCgstAmount(new java.math.BigDecimal("7.62"));
        line.setSgstAmount(new java.math.BigDecimal("7.63"));
        line.setIgstAmount(java.math.BigDecimal.ZERO);
        invoice.getLines().add(line);

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation orphanAllocation =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        orphanAllocation.setInvoice(new Invoice());

        when(invoiceRepository.findByCompanyOrderByIssueDateDesc(company)).thenReturn(List.of(invoice));
        when(invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer)).thenReturn(List.of(invoice));
        when(invoiceRepository.findByCompanyAndId(company, 1501L)).thenReturn(Optional.empty());
        when(companyEntityLookup.requireInvoice(company, 1501L)).thenReturn(invoice);
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1501L)))
                .thenReturn(List.of(orphanAllocation));

        assertThat(invoiceService.listInvoices()).singleElement().satisfies(dto -> {
            assertThat(dto.invoiceNumber()).isEqualTo("INV-1501");
            assertThat(dto.lines()).singleElement().satisfies(mappedLine -> {
                assertThat(mappedLine.productCode()).isEqualTo("FG-1502");
                assertThat(mappedLine.taxAmount()).isEqualByComparingTo("15.25");
            });
        });
        assertThat(invoiceService.listDealerInvoices(1500L)).singleElement().extracting(InvoiceDto::dealerId)
                .isEqualTo(1500L);
        assertThat(invoiceService.getInvoice(1501L).invoiceNumber()).isEqualTo("INV-1501");
        assertThat(invoiceService.getInvoiceWithDealerEmail(1501L).dealerEmail()).isEqualTo("dealer1500@example.com");
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "toDtos", company, null)).isEqualTo(List.of());
    }

    @Test
    void getInvoice_sourceOrderReferenceStaysNotEligibleBeforePostingTruthExists() {
        Long invoiceId = 401L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 88L);
        order.setCompany(company);
        order.setOrderNumber("SO-88");
        order.setStatus("READY_TO_SHIP");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-401");
        invoice.setStatus("DRAFT");
        invoice.setSalesOrder(order);

        when(invoiceRepository.findByCompanyAndId(company, invoiceId)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(88L))).thenReturn(List.of());

        InvoiceDto dto = invoiceService.getInvoice(invoiceId);

        LinkedBusinessReferenceDto sourceOrderReference = dto.linkedReferences().stream()
                .filter(reference -> "SOURCE_ORDER".equals(reference.relationType()))
                .findFirst()
                .orElseThrow();

        assertThat(sourceOrderReference.lifecycle().workflowStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(sourceOrderReference.lifecycle().accountingStatus()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void listInvoices_returnsEmptyWhenPageContainsNoIds() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, PageRequest.of(0, 1)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(invoiceService.listInvoices(0, 0)).isEmpty();

        verifyNoInteractions(packagingSlipRepository);
        verifyNoInteractions(settlementAllocationRepository);
    }

    @Test
    void listInvoices_skipsPackagingSlipBatchLookupWhenInvoicesLackSalesOrders() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findIdsByCompanyOrderByIssueDateDescIdDesc(company, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(77L)));

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 77L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-TRANSIENT");
        invoice.setStatus("DRAFT");

        when(invoiceRepository.findByCompanyAndIdInOrderByIssueDateDescIdDesc(company, List.of(77L)))
                .thenReturn(List.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(77L)))
                .thenReturn(List.of());

        List<InvoiceDto> invoices = invoiceService.listInvoices(0, 50);

        assertThat(invoices).singleElement().satisfies(invoiceDto -> {
            assertThat(invoiceDto.invoiceNumber()).isEqualTo("INV-TRANSIENT");
            assertThat(invoiceDto.linkedReferences()).isEmpty();
        });
        verifyNoInteractions(packagingSlipRepository);
        verify(settlementAllocationRepository).findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(77L));
    }

    @Test
    void getInvoice_includesAccountingEntryAndSettlementLinksWhenPresent() {
        Long invoiceId = 403L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        SalesOrder order = new SalesOrder();
        ReflectionTestUtils.setField(order, "id", 89L);
        order.setCompany(company);
        order.setOrderNumber("SO-89");
        order.setStatus("INVOICED");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-403");
        invoice.setStatus("ISSUED");
        invoice.setSalesOrder(order);

        com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry journalEntry =
                new com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 990L);
        journalEntry.setReferenceNumber("INV-403-JR");
        journalEntry.setStatus("POSTED");
        invoice.setJournalEntry(journalEntry);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 290L);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-290");
        slip.setStatus("DISPATCHED");

        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation allocation =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        ReflectionTestUtils.setField(allocation, "id", 603L);
        allocation.setInvoice(invoice);
        allocation.setIdempotencyKey("settlement-403");

        when(invoiceRepository.findByCompanyAndId(company, invoiceId)).thenReturn(Optional.of(invoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(89L))).thenReturn(List.of(slip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(invoiceId)))
                .thenReturn(List.of(allocation));

        InvoiceDto dto = invoiceService.getInvoice(invoiceId);

        assertThat(dto.lifecycle().accountingStatus()).isEqualTo("POSTED");
        assertThat(dto.linkedReferences())
                .extracting(LinkedBusinessReferenceDto::relationType, LinkedBusinessReferenceDto::documentType)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("SOURCE_ORDER", "SALES_ORDER"),
                        org.assertj.core.groups.Tuple.tuple("DISPATCH", "PACKAGING_SLIP"),
                        org.assertj.core.groups.Tuple.tuple("ACCOUNTING_ENTRY", "JOURNAL_ENTRY"),
                        org.assertj.core.groups.Tuple.tuple("SETTLEMENT", "SETTLEMENT_ALLOCATION")
                );
    }

    @Test
    void getInvoiceWithDealerEmail_handlesMissingLinkedReferences() {
        Long invoiceId = 402L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Dealer dealer = new Dealer();
        dealer.setName("Dealer 402");
        dealer.setEmail("dealer402@example.com");
        company.setName("BigBright 402");

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setInvoiceNumber("INV-402");
        invoice.setStatus("ISSUED");

        when(invoiceRepository.findByCompanyAndId(company, invoiceId)).thenReturn(Optional.of(invoice));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(invoiceId)))
                .thenReturn(List.of());

        InvoiceService.InvoiceWithEmail result = invoiceService.getInvoiceWithDealerEmail(invoiceId);

        assertThat(result.dealerEmail()).isEqualTo("dealer402@example.com");
        assertThat(result.companyName()).isEqualTo("BigBright 402");
        assertThat(result.invoice().linkedReferences()).isEmpty();
        verifyNoInteractions(packagingSlipRepository);
    }

    @Test
    void helperMethods_coverNullGuardsAndFilteredLinkedReferenceContext() throws Exception {
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", null, 1L, false)).isEqualTo(List.of());
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", company, null, false)).isEqualTo(List.of());

        SalesOrder orderWithoutId = new SalesOrder();
        orderWithoutId.setCompany(company);
        Invoice invoiceWithoutId = new Invoice();
        invoiceWithoutId.setCompany(company);
        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", orderWithoutId, invoiceWithoutId, List.of());

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        ReflectionTestUtils.setField(order, "id", 991L);
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 992L);
        invoice.setCompany(company);
        invoice.setSalesOrder(order);
        PackagingSlip existingSlip = new PackagingSlip();
        ReflectionTestUtils.setField(existingSlip, "id", 993L);
        existingSlip.setSalesOrder(order);
        existingSlip.setInvoiceId(992L);
        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", order, invoice, List.of(existingSlip));
        verify(packagingSlipRepository, org.mockito.Mockito.never()).save(existingSlip);

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(991L))).thenReturn(List.of(existingSlip));
        com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation orphanAllocation =
                new com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation();
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(992L)))
                .thenReturn(List.of(orphanAllocation));

        Object context = ReflectionTestUtils.invokeMethod(invoiceService, "buildLinkedReferenceContext", company, List.of(invoice));
        java.lang.reflect.Method packagingMethod = context.getClass().getDeclaredMethod("packagingSlipsBySalesOrderId");
        packagingMethod.setAccessible(true);
        java.lang.reflect.Method settlementMethod = context.getClass().getDeclaredMethod("settlementAllocationsByInvoiceId");
        settlementMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, List<PackagingSlip>> packagingMap = (java.util.Map<Long, List<PackagingSlip>>) packagingMethod.invoke(context);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>> settlementMap =
                (java.util.Map<Long, List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>>) settlementMethod.invoke(context);

        assertThat(packagingMap).containsKey(991L);
        assertThat(settlementMap).isEmpty();
        assertThat((Long) ReflectionTestUtils.invokeMethod(invoiceService, "activeSlipCount", (Object) null)).isZero();
    }

    @Test
    void helperMethods_coverRemainingGuardAndContextBranches() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(invoiceService, "reconcileOrderInvoiceMarker", null, 1L, true)).isFalse();

        SalesOrder orderWithoutCompany = new SalesOrder();
        ReflectionTestUtils.setField(orderWithoutCompany, "id", 1601L);
        Invoice invoiceWithoutId = new Invoice();
        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", orderWithoutCompany, invoiceWithoutId, List.of());

        SalesOrder orderWithoutId = new SalesOrder();
        orderWithoutId.setCompany(company);
        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 1602L);
        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", orderWithoutId, invoice, List.of());

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 1603L)).thenReturn(null);
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", company, 1603L, false)).isEqualTo(List.of());
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "toDtos", company, List.of())).isEqualTo(List.of());
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "buildLinkedReferenceContext", company, List.of())).isNotNull();

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("SO-1604");
        ReflectionTestUtils.setField(order, "id", 1604L);

        Invoice linkedInvoice = new Invoice();
        ReflectionTestUtils.setField(linkedInvoice, "id", 1605L);
        linkedInvoice.setCompany(company);
        linkedInvoice.setInvoiceNumber("INV-1605");
        linkedInvoice.setStatus("ISSUED");
        linkedInvoice.setSalesOrder(order);

        PackagingSlip filteredSlip = new PackagingSlip();
        ReflectionTestUtils.setField(filteredSlip, "id", 1606L);
        filteredSlip.setSalesOrder(new SalesOrder());
        filteredSlip.setSlipNumber("PS-FILTERED");

        PackagingSlip cogsSlip = new PackagingSlip();
        ReflectionTestUtils.setField(cogsSlip, "id", 1607L);
        cogsSlip.setSalesOrder(order);
        cogsSlip.setSlipNumber("PS-1607");
        cogsSlip.setStatus("DISPATCHED");
        cogsSlip.setCogsJournalEntryId(1707L);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findByCompanyAndId(company, 1605L)).thenReturn(Optional.of(linkedInvoice));
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1604L)))
                .thenReturn(List.of(filteredSlip, cogsSlip));
        when(settlementAllocationRepository.findByCompanyAndInvoice_IdInOrderByCreatedAtDesc(company, List.of(1605L)))
                .thenReturn(List.of());

        assertThat(invoiceService.getInvoice(1605L).linkedReferences())
                .filteredOn(reference -> "DISPATCH".equals(reference.relationType()))
                .singleElement()
                .satisfies(reference -> assertThat(reference.journalEntryId()).isEqualTo(1707L));
    }

    @Test
    void buildLinkedReferenceContext_filtersNullSlipSalesOrdersAndSkipsSettlementLookupWhenInvoiceIdsMissing() throws Exception {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("SO-1801");
        ReflectionTestUtils.setField(order, "id", 1801L);

        Invoice invoiceWithoutId = new Invoice();
        invoiceWithoutId.setCompany(company);
        invoiceWithoutId.setSalesOrder(order);
        invoiceWithoutId.setInvoiceNumber("INV-NO-ID");
        invoiceWithoutId.setStatus("ISSUED");

        PackagingSlip nullSalesOrderSlip = new PackagingSlip();
        ReflectionTestUtils.setField(nullSalesOrderSlip, "id", 1802L);
        nullSalesOrderSlip.setSlipNumber("PS-NULL-1802");

        PackagingSlip validSlip = new PackagingSlip();
        ReflectionTestUtils.setField(validSlip, "id", 1803L);
        validSlip.setSalesOrder(order);
        validSlip.setSlipNumber("PS-1803");
        validSlip.setStatus("DISPATCHED");

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdIn(company, List.of(1801L)))
                .thenReturn(List.of(nullSalesOrderSlip, validSlip));

        Object context = ReflectionTestUtils.invokeMethod(invoiceService, "buildLinkedReferenceContext", company, List.of(invoiceWithoutId));
        java.lang.reflect.Method packagingMethod = context.getClass().getDeclaredMethod("packagingSlipsBySalesOrderId");
        packagingMethod.setAccessible(true);
        java.lang.reflect.Method settlementMethod = context.getClass().getDeclaredMethod("settlementAllocationsByInvoiceId");
        settlementMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<Long, List<PackagingSlip>> packagingMap = (java.util.Map<Long, List<PackagingSlip>>) packagingMethod.invoke(context);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>> settlementMap =
                (java.util.Map<Long, List<com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation>>) settlementMethod.invoke(context);

        assertThat(packagingMap).containsKey(1801L);
        assertThat(packagingMap.get(1801L)).containsExactly(validSlip);
        assertThat(settlementMap).isEmpty();
        verifyNoInteractions(settlementAllocationRepository);
    }

    @Test
    void issueInvoiceForOrder_coversDispatchFailureBranchesAndSteadyStateHelpers() {
        Long nullResponseOrderId = 1201L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, nullResponseOrderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        ReflectionTestUtils.setField(order, "id", nullResponseOrderId);
        when(salesOrderCrudService.getOrderWithItems(nullResponseOrderId)).thenReturn(order);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 1202L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, nullResponseOrderId)).thenReturn(List.of(slip));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(nullResponseOrderId));

        Long missingIdOrderId = 1203L;
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, missingIdOrderId)).thenReturn(List.of());
        SalesOrder secondOrder = new SalesOrder();
        secondOrder.setCompany(company);
        secondOrder.setDealer(dealer);
        ReflectionTestUtils.setField(secondOrder, "id", missingIdOrderId);
        when(salesOrderCrudService.getOrderWithItems(missingIdOrderId)).thenReturn(secondOrder);

        PackagingSlip secondSlip = new PackagingSlip();
        ReflectionTestUtils.setField(secondSlip, "id", 1204L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, missingIdOrderId)).thenReturn(List.of(secondSlip));

        assertThrows(ApplicationException.class, () -> invoiceService.issueInvoiceForOrder(missingIdOrderId));

        SalesOrder steadyStateOrder = new SalesOrder();
        steadyStateOrder.setCompany(company);
        steadyStateOrder.setFulfillmentInvoiceId(1300L);
        ReflectionTestUtils.setField(steadyStateOrder, "id", 1301L);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(invoiceService, "reconcileOrderInvoiceMarker", steadyStateOrder, 1300L, true)).isFalse();

        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, 1301L)).thenReturn(null);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, 1301L)).thenReturn(List.of());
        assertThat((Object) ReflectionTestUtils.invokeMethod(invoiceService, "findOrderSlips", company, 1301L, true)).isEqualTo(List.of());

        Invoice existingInvoice = new Invoice();
        ReflectionTestUtils.setField(existingInvoice, "id", 1302L);
        existingInvoice.setCompany(company);
        PackagingSlip staleSlip = new PackagingSlip();
        ReflectionTestUtils.setField(staleSlip, "id", 1303L);
        staleSlip.setSalesOrder(steadyStateOrder);
        staleSlip.setInvoiceId(99L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderIdForUpdate(company, 1301L)).thenReturn(List.of(staleSlip));

        ReflectionTestUtils.invokeMethod(invoiceService, "linkInvoiceToPackagingSlip", steadyStateOrder, existingInvoice, null);
        verify(packagingSlipRepository).save(staleSlip);
    }

    @Test
    void getInvoiceWithDealerEmail_coversNullDealerAndCompanyBranches() {
        Long invoiceId = 1401L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        invoice.setInvoiceNumber("INV-1401");
        invoice.setStatus("DRAFT");
        invoice.setCompany(null);

        when(invoiceRepository.findByCompanyAndId(company, invoiceId)).thenReturn(Optional.of(invoice));

        InvoiceService.InvoiceWithEmail result = invoiceService.getInvoiceWithDealerEmail(invoiceId);

        assertThat(result.dealerEmail()).isNull();
        assertThat(result.companyName()).isNull();
    }
}
