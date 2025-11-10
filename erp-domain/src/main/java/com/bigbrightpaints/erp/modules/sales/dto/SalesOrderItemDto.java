package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;

public record SalesOrderItemDto(Long id,
                                String productCode,
                                String description,
                                BigDecimal quantity,
                                BigDecimal unitPrice) {}
