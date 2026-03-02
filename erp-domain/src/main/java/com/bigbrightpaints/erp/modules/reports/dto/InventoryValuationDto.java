package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record InventoryValuationDto(BigDecimal totalValue,
                                    long lowStockItems,
                                    String costingMethod,
                                    List<InventoryValuationItemDto> items,
                                    List<InventoryValuationGroupDto> groupByCategory,
                                    List<InventoryValuationGroupDto> groupByBrand,
                                    ReportMetadata metadata) {}
