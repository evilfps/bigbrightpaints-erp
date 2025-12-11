package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.DispatchPosting;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
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
    private final SalesOrderRepository salesOrderRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final SalesJournalService salesJournalService;
    private final AccountingFacade accountingFacade;
    private final InvoiceService invoiceService;

    public SalesFulfillmentService(SalesService salesService,
                                   SalesOrderRepository salesOrderRepository,
                                   FinishedGoodsService finishedGoodsService,
                                   SalesJournalService salesJournalService,
                                   AccountingFacade accountingFacade,
                                   InvoiceService invoiceService) {
        this.salesService = salesService;
        this.salesOrderRepository = salesOrderRepository;
        this.finishedGoodsService = finishedGoodsService;
        this.salesJournalService = salesJournalService;
        this.accountingFacade = accountingFacade;
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
            // Step 1: Reserve inventory (if not already reserved)
            if (options.reserveInventory()) {
                InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
                result.reservation(reservation);
                
                if (!reservation.shortages().isEmpty()) {
                    log.warn("Order {} has shortages: {}", orderNumber, reservation.shortages());
                    if (!options.allowPartialFulfillment()) {
                        result.status(FulfillmentStatus.PENDING_INVENTORY);
                        return result.build();
                    }
                }
                log.info("Reserved inventory for order {}", orderNumber);
            }

            // Step 2: Dispatch inventory and get COGS from actual cost layers
            List<DispatchPosting> dispatches = finishedGoodsService.markSlipDispatched(orderId);
            result.dispatches(dispatches);
            
            BigDecimal totalCogs = dispatches.stream()
                    .map(DispatchPosting::cost)
                    .filter(c -> c != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.cogsAmount(totalCogs);
            log.info("Dispatched order {} - COGS: {}", orderNumber, totalCogs);

            // Step 3: Post sales/revenue journal entry (ONLY if NOT issuing invoice)
            // IDEMPOTENCY: Check marker first
            if (options.postSalesJournal() && !order.hasSalesJournalPosted()) {
                Long salesJournalId = salesJournalService.postSalesJournal(
                        order,
                        null,
                        null,
                        options.entryDate() != null ? options.entryDate() : LocalDate.now(),
                        "Sales fulfillment for " + orderNumber
                );
                result.salesJournalId(salesJournalId);
                order.setSalesJournalEntryId(salesJournalId);
                log.info("Posted sales journal {} for order {}", salesJournalId, orderNumber);
            } else if (order.hasSalesJournalPosted()) {
                log.info("Sales journal already posted for order {} (id={})", orderNumber, order.getSalesJournalEntryId());
                result.salesJournalId(order.getSalesJournalEntryId());
            }

            // Step 4: Post COGS journal entries (with idempotency check)
            if (options.postCogsJournal() && !dispatches.isEmpty() && !order.hasCogsJournalPosted()) {
                List<Long> cogsJournalIds = postCogsFromDispatches(order, dispatches);
                result.cogsJournalIds(cogsJournalIds);
                if (!cogsJournalIds.isEmpty()) {
                    order.setCogsJournalEntryId(cogsJournalIds.get(0)); // Store first COGS entry ID
                }
                log.info("Posted {} COGS journal entries for order {}", cogsJournalIds.size(), orderNumber);
            } else if (order.hasCogsJournalPosted()) {
                log.info("COGS journal already posted for order {} (id={})", orderNumber, order.getCogsJournalEntryId());
            }

            // Step 5: Issue invoice (IDEMPOTENCY: Check marker first)
            if (options.issueInvoice() && !order.hasInvoiceIssued()) {
                InvoiceDto invoice = invoiceService.issueInvoiceForOrder(orderId);
                result.invoiceId(invoice.id());
                result.invoiceNumber(invoice.invoiceNumber());
                order.setFulfillmentInvoiceId(invoice.id());
                log.info("Issued invoice {} for order {}", invoice.invoiceNumber(), orderNumber);
            } else if (order.hasInvoiceIssued()) {
                log.info("Invoice already issued for order {} (id={})", orderNumber, order.getFulfillmentInvoiceId());
                result.invoiceId(order.getFulfillmentInvoiceId());
            }

            // Step 6: Save idempotency markers and update order status
            salesOrderRepository.save(order);
            salesService.updateStatus(orderId, "SHIPPED");
            result.status(FulfillmentStatus.COMPLETED);
            
            log.info("Completed fulfillment for order {} - Revenue: {}, COGS: {}, Gross Profit: {}",
                    orderNumber, order.getTotalAmount(), totalCogs,
                    order.getTotalAmount().subtract(totalCogs));

            return result.build();

        } catch (Exception e) {
            log.error("Fulfillment failed for order {}: {}", orderNumber, e.getMessage(), e);
            result.status(FulfillmentStatus.FAILED);
            result.errorMessage(e.getMessage());
            throw e; // Re-throw to trigger transaction rollback
        }
    }

    /**
     * Validates and normalizes fulfillment options to prevent conflicting configurations.
     * 
     * RULE: Invoice ALWAYS owns AR/Revenue/Tax posting.
     * If issueInvoice=true, postSalesJournal is automatically disabled.
     */
    private FulfillmentOptions validateAndNormalizeOptions(FulfillmentOptions options) {
        // DEFENSIVE: If issuing invoice, NEVER post sales journal separately
        // Invoice will handle AR/Revenue/Tax posting
        if (options.issueInvoice() && options.postSalesJournal()) {
            log.warn("Conflicting options: issueInvoice=true AND postSalesJournal=true. " +
                     "Invoice owns AR/Revenue posting. Disabling postSalesJournal.");
            return FulfillmentOptions.builder()
                    .reserveInventory(options.reserveInventory())
                    .postSalesJournal(false) // FORCE disable - invoice will handle it
                    .postCogsJournal(options.postCogsJournal())
                    .issueInvoice(true)
                    .allowPartialFulfillment(options.allowPartialFulfillment())
                    .entryDate(options.entryDate())
                    .build();
        }
        return options;
    }

    /**
     * Post COGS journal entries from dispatch postings.
     * Uses actual costs from FIFO/LIFO layers, not hardcoded values.
     */
    private List<Long> postCogsFromDispatches(SalesOrder order, List<DispatchPosting> dispatches) {
        List<Long> journalIds = new ArrayList<>();
        String orderNumber = order.getOrderNumber();

        for (int i = 0; i < dispatches.size(); i++) {
            DispatchPosting dispatch = dispatches.get(i);
            
            if (dispatch.cogsAccountId() == null || dispatch.inventoryAccountId() == null) {
                log.warn("Skipping COGS posting for dispatch {} - missing account IDs", i);
                continue;
            }
            
            BigDecimal cost = dispatch.cost();
            if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Generate reference anchored to order number (first dispatch uses COGS-{orderNumber})
            // Additional layers use suffixed references to avoid missing multi-layer costs
            String baseRef = "COGS-" + orderNumber;
            String reference = i == 0 ? baseRef : baseRef + "-P" + (i + 1);
            
            // Check if already posted (idempotency)
            if (accountingFacade.hasCogsJournalFor(reference)) {
                log.info("COGS already posted for {}", reference);
                continue;
            }

            JournalEntryDto cogsEntry = accountingFacade.postCOGS(
                    reference,
                    dispatch.cogsAccountId(),
                    dispatch.inventoryAccountId(),
                    cost,
                    "COGS for " + orderNumber + " (Layer " + (i + 1) + ")"
            );
            
            journalIds.add(cogsEntry.id());
        }

        return journalIds;
    }

    /**
     * Reserve inventory only (step 1 of fulfillment)
     */
    @Transactional
    public InventoryReservationResult reserveForOrder(Long orderId) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        InventoryReservationResult result = finishedGoodsService.reserveForOrder(order);
        salesService.updateStatus(orderId, result.shortages().isEmpty() ? "RESERVED" : "PENDING_INVENTORY");
        return result;
    }

    /**
     * Dispatch and post COGS only (for orders already reserved)
     */
    @Transactional
    public DispatchResult dispatchOrder(Long orderId) {
        SalesOrder order = salesService.getOrderWithItems(orderId);
        List<DispatchPosting> dispatches = finishedGoodsService.markSlipDispatched(orderId);
        List<Long> cogsIds = postCogsFromDispatches(order, dispatches);
        
        BigDecimal totalCogs = dispatches.stream()
                .map(DispatchPosting::cost)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        salesService.updateStatus(orderId, "DISPATCHED");
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
