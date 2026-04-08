package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record MonthlyProductionCostEntryDto(String month, BigDecimal totalCost) {}
