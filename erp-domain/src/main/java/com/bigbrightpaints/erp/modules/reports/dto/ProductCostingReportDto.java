package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record ProductCostingReportDto(
    Long itemId,
    BigDecimal materialCost,
    BigDecimal packagingCost,
    BigDecimal labourCost,
    BigDecimal overheadCost,
    BigDecimal totalUnitCost) {}
