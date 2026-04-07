package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;

public record SalesDashboardDto(
    long recentOrdersCount,
    BigDecimal totalRevenue,
    BigDecimal totalReceivables,
    long pendingOrders) {}
