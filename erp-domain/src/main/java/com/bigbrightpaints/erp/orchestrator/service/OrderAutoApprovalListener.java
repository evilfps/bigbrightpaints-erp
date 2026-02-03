package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;

@Component
public class OrderAutoApprovalListener {

    private static final Logger log = LoggerFactory.getLogger(OrderAutoApprovalListener.class);

    private final CommandDispatcher commandDispatcher;
    private final SalesService salesService;
    private final SystemSettingsService systemSettingsService;

    public OrderAutoApprovalListener(CommandDispatcher commandDispatcher,
                                     SalesService salesService,
                                     SystemSettingsService systemSettingsService) {
        this.commandDispatcher = commandDispatcher;
        this.salesService = salesService;
        this.systemSettingsService = systemSettingsService;
        log.info("Order auto-approval enabled={}", systemSettingsService.isAutoApprovalEnabled());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(SalesOrderCreatedEvent event) {
        if (!systemSettingsService.isAutoApprovalEnabled()) {
            log.info("Auto-approval disabled; skipping for order {}", event.orderId());
            return;
        }
        String previousCompany = CompanyContextHolder.getCompanyCode();
        CompanyContextHolder.setCompanyCode(event.companyCode());
        try {
            String idempotencyKey = "AUTO-APPROVE-" + event.orderId();
            String requestId = idempotencyKey;
            String traceId = commandDispatcher.autoApproveOrder(
                    String.valueOf(event.orderId()),
                    event.totalAmount(),
                    event.companyCode(),
                    idempotencyKey,
                    requestId);
            salesService.attachTraceId(event.orderId(), traceId);
            log.info("Auto-approved order {} with trace {}", event.orderId(), traceId);
        } finally {
            if (previousCompany != null) {
                CompanyContextHolder.setCompanyCode(previousCompany);
            } else {
                CompanyContextHolder.clear();
            }
        }
    }
}
