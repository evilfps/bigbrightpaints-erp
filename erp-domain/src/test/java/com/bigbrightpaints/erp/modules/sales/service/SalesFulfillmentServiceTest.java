package com.bigbrightpaints.erp.modules.sales.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.PackagingSlipDto;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmResponse;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class SalesFulfillmentServiceTest {

    @Mock
    private SalesService salesService;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private InvoiceService invoiceService;

    private SalesFulfillmentService fulfillmentService;

    @BeforeEach
    void setup() {
        fulfillmentService = new SalesFulfillmentService(
                salesService,
                finishedGoodsService,
                packagingSlipRepository,
                invoiceService);
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
                null, null, order.getId(), null, null, List.of(), null, List.of());
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
                null, null, order.getId(), null, null, List.of(), null, List.of());
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
                null, null, order.getId(), null, null, List.of(), null, List.of());
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

    @Test
    void returnsCompletedWhenOrderAlreadyShipped() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 5L);
        order.setOrderNumber("SO-5");
        order.setStatus("SHIPPED");
        order.setSalesJournalEntryId(55L);
        order.setFulfillmentInvoiceId(66L);

        when(salesService.getOrderWithItems(5L)).thenReturn(order);

        var result = fulfillmentService.fulfillOrder(5L);

        org.junit.jupiter.api.Assertions.assertEquals(SalesFulfillmentService.FulfillmentStatus.COMPLETED, result.status());
        org.junit.jupiter.api.Assertions.assertEquals(55L, result.salesJournalId());
        org.junit.jupiter.api.Assertions.assertEquals(66L, result.invoiceId());
    }

    @Test
    void returnsFailedWhenOrderCancelled() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 6L);
        order.setOrderNumber("SO-6");
        order.setStatus("CANCELLED");

        when(salesService.getOrderWithItems(6L)).thenReturn(order);

        var result = fulfillmentService.fulfillOrder(6L);

        org.junit.jupiter.api.Assertions.assertEquals(SalesFulfillmentService.FulfillmentStatus.FAILED, result.status());
        org.junit.jupiter.api.Assertions.assertEquals("Order is CANCELLED", result.errorMessage());
    }

    @Test
    void reserveForOrder_updatesStatusBasedOnShortages() {
        SalesOrder order = new SalesOrder();
        setField(order, "id", 7L);
        order.setOrderNumber("SO-7");
        when(salesService.getOrderWithItems(7L)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(new InventoryReservationResult(
                null,
                List.of(new FinishedGoodsService.InventoryShortage("FG-1", BigDecimal.ONE, "Primer"))));

        fulfillmentService.reserveForOrder(7L);

        verify(salesService).updateStatusInternal(7L, "PENDING_INVENTORY");
    }

    @Test
    void dispatchOrder_returnsSlipScopedCogsJournalIds() {
        SalesOrder order = new SalesOrder();
        Company company = new Company();
        company.setTimezone("UTC");
        setField(order, "id", 8L);
        order.setCompany(company);
        order.setOrderNumber("SO-8");
        order.setStatus("BOOKED");
        when(salesService.getOrderWithItems(8L)).thenReturn(order);
        when(salesService.confirmDispatch(any())).thenReturn(
                new DispatchConfirmResponse(88L, 8L, 18L, 28L, List.of(), true, List.of(), null));
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setCogsJournalEntryId(128L);
        when(packagingSlipRepository.findByIdAndCompany(88L, company)).thenReturn(Optional.of(slip));

        var result = fulfillmentService.dispatchOrder(8L);

        org.junit.jupiter.api.Assertions.assertEquals(List.of(128L), result.cogsJournalIds());
        org.junit.jupiter.api.Assertions.assertEquals(BigDecimal.ZERO, result.totalCogs());
    }

    @Test
    void fulfillOrder_prefersReservedSlipIdAndRestoresSlipScopedCogsMarker() {
        SalesOrder order = new SalesOrder();
        Company company = new Company();
        company.setTimezone("UTC");
        setField(order, "id", 9L);
        order.setCompany(company);
        order.setOrderNumber("SO-9");
        order.setStatus("BOOKED");
        order.setTotalAmount(new BigDecimal("180.00"));

        PackagingSlipDto reservedSlip = new PackagingSlipDto(
                77L,
                UUID.randomUUID(),
                order.getId(),
                order.getOrderNumber(),
                "Dealer 9",
                "PS-77",
                "RESERVED",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());
        when(salesService.getOrderWithItems(9L)).thenReturn(order);
        when(finishedGoodsService.reserveForOrder(order)).thenReturn(new InventoryReservationResult(reservedSlip, List.of()));
        when(salesService.confirmDispatch(any())).thenReturn(
                new DispatchConfirmResponse(77L, order.getId(), null, 501L, List.of(), true, List.of(), null));
        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setCogsJournalEntryId(909L);
        when(packagingSlipRepository.findByIdAndCompany(77L, company)).thenReturn(Optional.of(slip));

        var result = fulfillmentService.fulfillOrder(9L);

        var captor = org.mockito.ArgumentCaptor.forClass(com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest.class);
        verify(salesService).confirmDispatch(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(77L, captor.getValue().packingSlipId());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().orderId());
        org.junit.jupiter.api.Assertions.assertNull(result.invoiceId());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(909L), result.cogsJournalIds());
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
