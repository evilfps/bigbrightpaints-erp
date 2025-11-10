package com.bigbrightpaints.erp.modules.sales.event;

import java.math.BigDecimal;

public record SalesOrderCreatedEvent(Long orderId, String companyCode, BigDecimal totalAmount) {}
