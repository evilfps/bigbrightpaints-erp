package com.bigbrightpaints.erp.modules.sales.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsDispatchService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsReservationService;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesFulfillmentServiceTest {

    @Mock
    private SalesService salesService;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private FinishedGoodsReservationService finishedGoodsReservationService;
    @Mock
    private FinishedGoodsDispatchService finishedGoodsDispatchService;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private SalesJournalService salesJournalService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private CompanyClock companyClock;

    private SalesFulfillmentService fulfillmentService;

    @BeforeEach
    void setup() {
        fulfillmentService = new SalesFulfillmentService(
                salesService,
                salesOrderRepository,
                finishedGoodsReservationService,
                finishedGoodsDispatchService,
                packagingSlipRepository,
                salesJournalService,
                accountingFacade,
                invoiceService,
                companyClock);
    }

    @Test
    void skipsSalesJournalWhenIssuingInvoice() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 1L);
        order.setOrderNumber("SO-1");
        order.setStatus("BOOKED");
        order.setTotalAmount(new BigDecimal("100"));

        when(salesService.getOrderWithItems(1L)).thenReturn(order);
        InvoiceDto invoice = new InvoiceDto(10L, UUID.randomUUID(), "INV-1", "ISSUED",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "INR", LocalDate.now(), LocalDate.now().plusDays(15),
                null, null, order.getId(), null, null, List.of());
        when(salesService.confirmDispatch(any())).thenReturn(
                new DispatchConfirmResponse(20L, order.getId(), invoice.id(), 555L, List.of(), true, List.of(), null));
        when(invoiceService.getInvoice(invoice.id())).thenReturn(invoice);

        var options = SalesFulfillmentService.FulfillmentOptions.builder()
                .reserveInventory(false)
                .postCogsJournal(false)
                .postSalesJournal(true)
                .issueInvoice(true)
                .build();

        var result = fulfillmentService.fulfillOrder(1L, options);

        // Sales journal should be skipped to avoid double posting
        verify(salesJournalService, never()).postSalesJournal(any(), any(), anyString(), any(), anyString());
        verify(salesService).confirmDispatch(any());
        verify(invoiceService).getInvoice(10L);
        org.junit.jupiter.api.Assertions.assertEquals(555L, result.salesJournalId());
    }

    @Test
    void skipsSalesJournalWhenMarkerAlreadyPresent() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 2L);
        order.setOrderNumber("SO-2");
        order.setStatus("BOOKED");
        order.setTotalAmount(new BigDecimal("250"));
        order.setSalesJournalEntryId(999L);

        when(salesService.getOrderWithItems(2L)).thenReturn(order);
        InvoiceDto invoice = new InvoiceDto(11L, UUID.randomUUID(), "INV-2", "ISSUED",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "INR", LocalDate.now(), LocalDate.now().plusDays(15),
                null, null, order.getId(), null, null, List.of());
        when(salesService.confirmDispatch(any())).thenReturn(
                new DispatchConfirmResponse(21L, order.getId(), invoice.id(), 999L, List.of(), true, List.of(), null));
        when(invoiceService.getInvoice(invoice.id())).thenReturn(invoice);

        var options = SalesFulfillmentService.FulfillmentOptions.builder()
                .reserveInventory(false)
                .postCogsJournal(false)
                .postSalesJournal(true) // marker should prevent posting
                .issueInvoice(true)
                .build();

        var result = fulfillmentService.fulfillOrder(2L, options);

        verify(salesJournalService, never()).postSalesJournal(any(), any(), anyString(), any(), anyString());
        verify(salesService).confirmDispatch(any());
        verify(invoiceService).getInvoice(11L);
        org.junit.jupiter.api.Assertions.assertEquals(999L, result.salesJournalId());
    }

    @Test
    void forcesOrderLevelCogsPostingDisabled() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 3L);
        order.setOrderNumber("SO-3");
        order.setStatus("BOOKED");
        order.setTotalAmount(new BigDecimal("300"));

        when(salesService.getOrderWithItems(3L)).thenReturn(order);
        InvoiceDto invoice = new InvoiceDto(12L, UUID.randomUUID(), "INV-3", "ISSUED",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "INR", LocalDate.now(), LocalDate.now().plusDays(15),
                null, null, order.getId(), null, null, List.of());
        when(salesService.confirmDispatch(any())).thenReturn(
                new DispatchConfirmResponse(22L, order.getId(), invoice.id(), 123L, List.of(), true, List.of(), null));
        when(invoiceService.getInvoice(invoice.id())).thenReturn(invoice);

        var options = SalesFulfillmentService.FulfillmentOptions.builder()
                .reserveInventory(false)
                .postSalesJournal(false)
                .postCogsJournal(true)
                .issueInvoice(true)
                .build();

        fulfillmentService.fulfillOrder(3L, options);

        verify(salesService).confirmDispatch(any());
        verify(finishedGoodsDispatchService, never()).markSlipDispatched(anyLong());
        verify(accountingFacade, never()).postCogsJournal(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void rejectsFulfillmentWithoutDispatchInvoice() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 4L);
        order.setOrderNumber("SO-4");
        order.setStatus("BOOKED");
        order.setTotalAmount(new BigDecimal("120"));

        when(salesService.getOrderWithItems(4L)).thenReturn(order);

        var options = SalesFulfillmentService.FulfillmentOptions.builder()
                .reserveInventory(true)
                .postSalesJournal(false)
                .postCogsJournal(false)
                .issueInvoice(false)
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.bigbrightpaints.erp.core.exception.ApplicationException.class,
                () -> fulfillmentService.fulfillOrder(4L, options));
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
