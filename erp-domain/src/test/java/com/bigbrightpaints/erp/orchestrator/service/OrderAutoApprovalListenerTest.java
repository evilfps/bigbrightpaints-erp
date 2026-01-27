package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.modules.sales.event.SalesOrderCreatedEvent;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAutoApprovalListenerTest {

    @Mock
    private CommandDispatcher commandDispatcher;

    @Mock
    private SalesService salesService;

    @Mock
    private SystemSettingsService systemSettingsService;

    @Test
    void onOrderCreated_skipsWhenAutoApprovalDisabled() {
        when(systemSettingsService.isAutoApprovalEnabled()).thenReturn(false);
        OrderAutoApprovalListener listener = new OrderAutoApprovalListener(commandDispatcher, salesService, systemSettingsService);

        listener.onOrderCreated(new SalesOrderCreatedEvent(42L, "COMP", new BigDecimal("100.00")));

        verify(commandDispatcher, never()).autoApproveOrder(eq("42"), eq(new BigDecimal("100.00")), eq("COMP"));
        verify(salesService, never()).attachTraceId(eq(42L), anyString());
    }

    @Test
    void onOrderCreated_autoApprovesAndAttachesTraceIdWhenEnabled() {
        when(systemSettingsService.isAutoApprovalEnabled()).thenReturn(true);
        when(commandDispatcher.autoApproveOrder("42", new BigDecimal("100.00"), "COMP")).thenReturn("trace-123");
        OrderAutoApprovalListener listener = new OrderAutoApprovalListener(commandDispatcher, salesService, systemSettingsService);

        listener.onOrderCreated(new SalesOrderCreatedEvent(42L, "COMP", new BigDecimal("100.00")));

        verify(commandDispatcher).autoApproveOrder("42", new BigDecimal("100.00"), "COMP");
        verify(salesService).attachTraceId(42L, "trace-123");
    }
}
