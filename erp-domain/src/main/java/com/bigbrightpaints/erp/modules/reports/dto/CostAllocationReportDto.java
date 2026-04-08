package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.util.List;

public record CostAllocationReportDto(
    List<String> allocationRules,
    List<CostAllocationBatchDto> amountsPerBatch,
    BigDecimal totalAllocated) {}
