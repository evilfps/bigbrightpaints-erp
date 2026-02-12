package com.bigbrightpaints.erp.modules.invoice.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
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
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private InvoiceService invoiceService;
    private Company company;

    @BeforeEach
    void setup() {
        invoiceService = new InvoiceService(
                companyContextService,
                invoiceRepository,
                salesService,
                salesOrderRepository,
                invoiceNumberService,
                salesJournalService,
                companyEntityLookup,
                journalReferenceResolver,
                dealerLedgerService,
                packagingSlipRepository,
                companyClock
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

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);
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

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);
        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        assertThat(order.getFulfillmentInvoiceId()).isEqualTo(123L);
        assertThat(dto.id()).isEqualTo(123L);
        assertThat(slip.getInvoiceId()).isEqualTo(123L);
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

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);
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

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);
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
    void issueInvoiceForOrder_usesDispatchConfirmationWhenSlipExists() {
        Long orderId = 77L;
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(invoiceRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of());

        Dealer dealer = new Dealer();
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setDealer(dealer);
        order.setOrderNumber("SO-77");
        order.setCurrency("INR");

        when(salesService.getOrderWithItems(orderId)).thenReturn(order);

        PackagingSlip slip = new PackagingSlip();
        ReflectionTestUtils.setField(slip, "id", 99L);
        when(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)).thenReturn(List.of(slip));

        DispatchConfirmResponse response = new DispatchConfirmResponse(
                slip.getId(),
                orderId,
                123L,
                null,
                List.of(),
                true,
                List.of()
        );
        when(salesService.confirmDispatch(any())).thenReturn(response);

        Invoice invoice = new Invoice();
        ReflectionTestUtils.setField(invoice, "id", 123L);
        invoice.setCompany(company);
        invoice.setInvoiceNumber("INV-77");
        when(invoiceRepository.findByCompanyAndId(company, 123L)).thenReturn(Optional.of(invoice));

        InvoiceDto dto = invoiceService.issueInvoiceForOrder(orderId);

        ArgumentCaptor<DispatchConfirmRequest> requestCaptor = ArgumentCaptor.forClass(DispatchConfirmRequest.class);
        verify(salesService).confirmDispatch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().packingSlipId()).isEqualTo(99L);
        assertThat(dto.id()).isEqualTo(123L);

        verifyNoInteractions(invoiceNumberService);
    }
}
