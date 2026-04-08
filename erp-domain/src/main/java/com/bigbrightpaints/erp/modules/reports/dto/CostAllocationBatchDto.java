package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CostAllocationBatchDto(
    String batchCode,
    String periodKey,
    BigDecimal allocatedAmount,
    LocalDate entryDate,
    Long journalEntryId) {}
