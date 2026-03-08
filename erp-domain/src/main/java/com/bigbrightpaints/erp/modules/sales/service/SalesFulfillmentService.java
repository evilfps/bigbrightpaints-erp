package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.DispatchPosting;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the complete sales fulfillment flow:
 * 1. Reserve inventory
 * 2. Dispatch inventory (FIFO/LIFO cost layers)
 * 3. Post revenue journal entry
 * 4. Post COGS journal entry (automatically from dispatch costs)
 * 5. Update dealer balance
 * 6. Issue invoice
 * 
 * This eliminates manual/hardcoded COGS postings and ensures
 * the full Sales → Inventory → Accounting chain is atomic.
 */
@Service
public class SalesFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(SalesFulfillmentService.class);

    private final SalesService salesService;
    private final FinishedGoodsService finishedGoodsService;
    private final PackagingSlipRepository packagingSlipRepository;
    private final InvoiceService invoiceService;

    public SalesFulfillmentService(SalesService salesService,
                                   FinishedGoodsService finishedGoodsService,
                                   PackagingSlipRepository packagingSlipRepository,
                                   InvoiceService invoiceService) {
        this.salesService = salesService;
        this.finishedGoodsService = finishedGoodsService;
        this.packagingSlipRepository = packagingSlipRepository;
        this.invoiceService = invoiceService;
    }

    /**
     * Complete fulfillment of a sales order in one atomic transaction.
     * 
     * @param orderId Sales order ID
     * @return Fulfillment result with all posted entries
     */
    @Transactional
    public FulfillmentResult fulfillOrder(Long orderId) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        return fulfillOrder(order, FulfillmentOptions.defaults());
    }

    /**
     * Fulfill order with custom options
     */
    @Transactional
    public FulfillmentResult fulfillOrder(Long orderId, FulfillmentOptions options) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        return fulfillOrder(order, options);
    }

    /**
     * Core fulfillment logic with idempotency protection.
     * 
     * IMPORTANT: Invoice ALWAYS owns AR/Revenue/Tax posting. If issueInvoice=true,
     * postSalesJournal is automatically disabled to prevent double posting.
     */
    @Transactional
    public FulfillmentResult fulfillOrder(SalesOrder order, FulfillmentOptions options) {
        Long orderId = order.getId();
        String orderNumber = order.getOrderNumber();
        log.info("Starting fulfillment for order {} ({})", orderId, orderNumber);

        // DEFENSIVE: Validate options to prevent conflicting requests
        options = validateAndNormalizeOptions(options);

        FulfillmentResult.Builder result = FulfillmentResult.builder()
                .orderId(orderId)
                .orderNumber(orderNumber);

        // Check if order is already shipped/fulfilled to prevent double posting
        String orderStatus = order.getStatus();
        if ("SHIPPED".equalsIgnoreCase(orderStatus) || "FULFILLED".equalsIgnoreCase(orderStatus) ||
            "COMPLETED".equalsIgnoreCase(orderStatus)) {
            log.info("Order {} already has status {}, skipping fulfillment", orderNumber, orderStatus);
            result.status(FulfillmentStatus.COMPLETED);
            // Return existing markers if available
            result.salesJournalId(order.getSalesJournalEntryId());
            result.invoiceId(order.getFulfillmentInvoiceId());
            return result.build();
        }
        
        // Block fulfillment for cancelled/rejected orders
        if ("CANCELLED".equalsIgnoreCase(orderStatus) || "REJECTED".equalsIgnoreCase(orderStatus)) {
            log.warn("Cannot fulfill order {} with status {}", orderNumber, orderStatus);
            result.status(FulfillmentStatus.FAILED);
            result.errorMessage("Order is " + orderStatus);
            return result.build();
        }

        try {
            Long slipId = null;
            // Step 1: Reserve inventory (if not already reserved)
            if (options.reserveInventory()) {
                InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
                result.reservation(reservation);
                if (reservation != null && reservation.packagingSlip() != null) {
                    slipId = reservation.packagingSlip().id();
                }
                
                if (reservation != null && !reservation.shortages().isEmpty()) {
                    log.warn("Order {} has shortages: {}", orderNumber, reservation.shortages());
                    if (!options.allowPartialFulfillment()) {
                        result.status(FulfillmentStatus.PENDING_INVENTORY);
                        return result.build();
                    }
                }
                log.info("Reserved inventory for order {}", orderNumber);
            }

            if (options.issueInvoice()) {
                DispatchConfirmRequest dispatchRequest = new DispatchConfirmRequest(
                        slipId,
                        slipId != null ? null : orderId,
                        null,
                        null,
                        null,
                        Boolean.FALSE,
                        null,
                        null);
                var dispatchResponse = salesService.confirmDispatch(dispatchRequest);
                List<DispatchPosting> dispatches = dispatchResponse.cogsPostings().stream()
                        .map(p -> new DispatchPosting(p.inventoryAccountId(), p.cogsAccountId(), p.cost()))
                        .toList();
                result.dispatches(dispatches);
                BigDecimal totalCogs = dispatches.stream()
                        .map(DispatchPosting::cost)
                        .filter(c -> c != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                result.cogsAmount(totalCogs);
                result.salesJournalId(dispatchResponse.arJournalEntryId());
                if (dispatchResponse.finalInvoiceId() != null) {
                    InvoiceDto invoice = invoiceService.getInvoice(dispatchResponse.finalInvoiceId());
                    result.invoiceId(invoice.id());
                    result.invoiceNumber(invoice.invoiceNumber());
                }
                List<Long> slipCogsIds = resolveSlipCogsJournalIds(dispatchResponse.packingSlipId(), order);
                if (!slipCogsIds.isEmpty()) {
                    result.cogsJournalIds(slipCogsIds);
                }
                result.status(FulfillmentStatus.COMPLETED);
                log.info("Completed fulfillment for order {} - Revenue: {}, COGS: {}, Gross Profit: {}",
                        orderNumber, order.getTotalAmount(), totalCogs,
                        order.getTotalAmount().subtract(totalCogs));
                return result.build();
            }

        } catch (Exception e) {
            log.error("Fulfillment failed for order {}: {}", orderNumber, e.getMessage(), e);
            result.status(FulfillmentStatus.FAILED);
            result.errorMessage(e.getMessage());
            throw e; // Re-throw to trigger transaction rollback
        }

        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                "Fulfillment requires dispatch confirmation; issueInvoice must be true");
    }

    /**
     * Validates and normalizes fulfillment options to prevent conflicting configurations.
     * 
     * RULE: Invoice ALWAYS owns AR/Revenue/Tax posting.
     * If issueInvoice=true, postSalesJournal is automatically disabled.
     */
    private FulfillmentOptions validateAndNormalizeOptions(FulfillmentOptions options) {
        if (!options.issueInvoice()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Fulfillment requires dispatch confirmation; issueInvoice must be true");
        }
        boolean reserveInventory = options.reserveInventory();
        boolean postCogsJournal = options.postCogsJournal();
        boolean changed = false;
        if (!reserveInventory) {
            log.warn("Fulfillment requires inventory reservation; forcing reserveInventory=true.");
            reserveInventory = true;
            changed = true;
        }
        if (postCogsJournal) {
            log.warn("Order-level COGS posting is disabled; dispatch confirmation owns slip-scoped COGS.");
            postCogsJournal = false;
            changed = true;
        }
        if (changed) {
            return FulfillmentOptions.builder()
                    .reserveInventory(reserveInventory)
                    .postSalesJournal(false)
                    .postCogsJournal(postCogsJournal)
                    .issueInvoice(true)
                    .allowPartialFulfillment(options.allowPartialFulfillment())
                    .entryDate(options.entryDate())
                    .build();
        }
        return options;
    }

    private List<Long> resolveSlipCogsJournalIds(Long packingSlipId, SalesOrder order) {
        if (packingSlipId == null || order == null || order.getCompany() == null) {
            return List.of();
        }
        return packagingSlipRepository.findByIdAndCompany(packingSlipId, order.getCompany())
                .map(PackagingSlip::getCogsJournalEntryId)
                .filter(id -> id != null)
                .map(List::of)
                .orElse(List.of());
    }

    /**
     * Reserve inventory only (step 1 of fulfillment)
     */
    @Transactional
    public InventoryReservationResult reserveForOrder(Long orderId) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        InventoryReservationResult result = finishedGoodsService.reserveForOrder(order);
        salesService.updateStatusInternal(orderId, result.shortages().isEmpty() ? "RESERVED" : "PENDING_INVENTORY");
        return result;
    }

    /**
     * Dispatch and post COGS only (for orders already reserved)
     */
    @Transactional
    public DispatchResult dispatchOrder(Long orderId) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        var response = salesService.confirmDispatch(
                new DispatchConfirmRequest(null, orderId, null, null, null, Boolean.FALSE, null, null)
        );
        List<DispatchPosting> dispatches = response.cogsPostings().stream()
                .map(p -> new DispatchPosting(p.inventoryAccountId(), p.cogsAccountId(), p.cost()))
                .toList();
        BigDecimal totalCogs = dispatches.stream()
                .map(DispatchPosting::cost)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> cogsIds = resolveSlipCogsJournalIds(response.packingSlipId(), order);
        return new DispatchResult(dispatches, cogsIds, totalCogs);
    }

    // Result DTOs
    public enum FulfillmentStatus {
        COMPLETED,
        PENDING_INVENTORY,
        PENDING_PRODUCTION,
        FAILED
    }

    public record FulfillmentOptions(
            boolean reserveInventory,
            boolean postSalesJournal,
            boolean postCogsJournal,
            boolean issueInvoice,
            boolean allowPartialFulfillment,
            LocalDate entryDate
    ) {
        public static FulfillmentOptions defaults() {
            return new FulfillmentOptions(true, true, true, true, false, null);
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private boolean reserveInventory = true;
            private boolean postSalesJournal = true;
            private boolean postCogsJournal = true;
            private boolean issueInvoice = true;
            private boolean allowPartialFulfillment = false;
            private LocalDate entryDate = null;
            
            public Builder reserveInventory(boolean v) { this.reserveInventory = v; return this; }
            public Builder postSalesJournal(boolean v) { this.postSalesJournal = v; return this; }
            public Builder postCogsJournal(boolean v) { this.postCogsJournal = v; return this; }
            public Builder issueInvoice(boolean v) { this.issueInvoice = v; return this; }
            public Builder allowPartialFulfillment(boolean v) { this.allowPartialFulfillment = v; return this; }
            public Builder entryDate(LocalDate d) { this.entryDate = d; return this; }
            
            public FulfillmentOptions build() {
                return new FulfillmentOptions(reserveInventory, postSalesJournal, postCogsJournal,
                        issueInvoice, allowPartialFulfillment, entryDate);
            }
        }
    }

    public record FulfillmentResult(
            Long orderId,
            String orderNumber,
            FulfillmentStatus status,
            InventoryReservationResult reservation,
            List<DispatchPosting> dispatches,
            BigDecimal cogsAmount,
            Long salesJournalId,
            List<Long> cogsJournalIds,
            Long invoiceId,
            String invoiceNumber,
            String errorMessage
    ) {
        public static Builder builder() {
            return new Builder();
        }
        
        public BigDecimal getGrossProfit(BigDecimal revenue) {
            return revenue.subtract(cogsAmount != null ? cogsAmount : BigDecimal.ZERO);
        }
        
        public static class Builder {
            private Long orderId;
            private String orderNumber;
            private FulfillmentStatus status = FulfillmentStatus.PENDING_INVENTORY;
            private InventoryReservationResult reservation;
            private List<DispatchPosting> dispatches = new ArrayList<>();
            private BigDecimal cogsAmount = BigDecimal.ZERO;
            private Long salesJournalId;
            private List<Long> cogsJournalIds = new ArrayList<>();
            private Long invoiceId;
            private String invoiceNumber;
            private String errorMessage;
            
            public Builder orderId(Long v) { this.orderId = v; return this; }
            public Builder orderNumber(String v) { this.orderNumber = v; return this; }
            public Builder status(FulfillmentStatus v) { this.status = v; return this; }
            public Builder reservation(InventoryReservationResult v) { this.reservation = v; return this; }
            public Builder dispatches(List<DispatchPosting> v) { this.dispatches = v; return this; }
            public Builder cogsAmount(BigDecimal v) { this.cogsAmount = v; return this; }
            public Builder salesJournalId(Long v) { this.salesJournalId = v; return this; }
            public Builder cogsJournalIds(List<Long> v) { this.cogsJournalIds = v; return this; }
            public Builder invoiceId(Long v) { this.invoiceId = v; return this; }
            public Builder invoiceNumber(String v) { this.invoiceNumber = v; return this; }
            public Builder errorMessage(String v) { this.errorMessage = v; return this; }
            
            public FulfillmentResult build() {
                return new FulfillmentResult(orderId, orderNumber, status, reservation, dispatches,
                        cogsAmount, salesJournalId, cogsJournalIds, invoiceId, invoiceNumber, errorMessage);
            }
        }
    }

    public record DispatchResult(
            List<DispatchPosting> dispatches,
            List<Long> cogsJournalIds,
            BigDecimal totalCogs
    ) {}
}
