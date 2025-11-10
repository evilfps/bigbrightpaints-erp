package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderAutoApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoApprovalListener.class);

    private final CommandDispatcher commandDispatcher;
    private final SalesService salesService;

    public OrderAutoApprovalListener(CommandDispatcher commandDispatcher, SalesService salesService) {
        this.commandDispatcher = commandDispatcher;
        this.salesService = salesService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(SalesOrderCreatedEvent event) {
        String traceId = commandDispatcher.autoApproveOrder(
                String.valueOf(event.orderId()),
                event.totalAmount(),
                event.companyCode());
        salesService.attachTraceId(event.orderId(), traceId);
        log.info("Auto-approved order {} with trace {}", event.orderId(), traceId);
    }
}
