package com.bigbrightpaints.erp.modules.accounting.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AccountingPeriodUpsertRequest(
        @Min(1900) @Max(9999) int year,
        @Min(1) @Max(12) int month,
        CostingMethod costingMethod
) {}
